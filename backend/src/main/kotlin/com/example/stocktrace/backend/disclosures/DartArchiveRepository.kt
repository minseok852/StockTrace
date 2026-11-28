package com.example.stocktrace.backend.disclosures

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.LocalDate
import java.time.DateTimeException
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import java.util.zip.ZipException
import javax.xml.parsers.DocumentBuilderFactory

internal class InvalidDartArchive(message: String, cause: Exception? = null) : IOException(message, cause)

class DartArchiveRepository(private val directory: Path) {
    fun find(receiptNo: String): DisclosureDetail? = try {
        read(receiptNo)
    } catch (failure: SAXException) {
        throw InvalidDartArchive("원문 XML 구문 오류", failure)
    } catch (failure: ZipException) {
        throw InvalidDartArchive("원문 ZIP 형식 오류", failure)
    } catch (failure: DateTimeException) {
        throw InvalidDartArchive("원문 날짜 형식 오류", failure)
    }

    private fun read(receiptNo: String): DisclosureDetail? {
        require(receiptNo.matches(Regex("[0-9]{14}")))
        check(Files.isDirectory(directory) && Files.isReadable(directory))
        val archive = try {
            ZipFile(directory.resolve("$receiptNo.zip").toFile())
        } catch (_: NoSuchFileException) {
            return null
        }
        return archive.use { zip ->
            val entry = zip.getEntry("$receiptNo.xml") ?: throw InvalidDartArchive("접수번호에 해당하는 XML 누락")
            val builder = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
            val document = try {
                zip.getInputStream(entry).use { builder.parse(it) }
            } catch (failure: SAXParseException) {
                // A one-to-one byte mapping keeps the XML declaration and original encoding intact.
                val original = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                val repaired = xmlTokens.replace(original) { if (it.value == "&") "&amp;" else it.value }
                if (repaired == original) throw failure
                builder.parse(repaired.byteInputStream(Charsets.ISO_8859_1))
            }
            parseDetail(receiptNo, document)
        }
    }
}

// Comments, CDATA and processing instructions can contain literal ampersands.
private val xmlTokens = Regex(
    """<!\[CDATA\[.*?]]>|<!--.*?-->|<\?.*?\?>|&(?!(?:amp|lt|gt|apos|quot|#\d+|#x[\da-fA-F]+);)""",
    RegexOption.DOT_MATCHES_ALL,
)

private fun parseDetail(receiptNo: String, document: Document): DisclosureDetail {
    val body = document.getElementsByTagName("BODY").item(0) as? Element ?: throw InvalidDartArchive("BODY 누락")
    val fields = mutableMapOf<String, Element>()
    val transactions = mutableListOf<Transaction>()
    var foundTradeTable = false
    // Older and corrected reports can reproduce previous tables in CORRECTION.
    fun visit(element: Element) {
        if (element.tagName == "CORRECTION") return
        if (element.tagName == "TABLE-GROUP" && element.getAttribute("ACLASS") == "RPT_RSN") {
            val rows = element.getElementsByTagName("TR")
            for (index in 0 until rows.length) {
                val cells = (rows.item(index) as Element).children().associateBy { it.fieldCode() }
                if ("RPT_RSN" in cells) {
                    transactions += transaction(transactions.size + 1, cells)
                    foundTradeTable = true
                }
                if ("MDF_STK_SUM" in cells) foundTradeTable = true
            }
            return
        }
        element.fieldCode().takeIf { it.isNotEmpty() }?.let { fields.putIfAbsent(it, element) }
        element.children().forEach(::visit)
    }
    visit(body)
    val previous = fields["BFR_UN_CNT"].quantity()
    val current = fields["AFR_UN_CNT"].quantity()
    val delta = if (previous != null && current != null) current - previous else null
    val change = if (delta != null && previous != null && previous != 0L) {
        delta.toBigDecimal().movePointRight(2).divide(previous.toBigDecimal(), 2, RoundingMode.HALF_UP)
    } else null
    return DisclosureDetail(
        receiptNo = receiptNo,
        filedOn = LocalDate.parse(receiptNo.take(8), DateTimeFormatter.BASIC_ISO_DATE).toString(),
        reportAsOf = fields["RPT_REF_DT"].date(),
        stockCode = fields["CRP_CD"].value() ?: throw InvalidDartArchive("CRP_CD 누락"),
        companyName = fields["CRP_NM"].value() ?: throw InvalidDartArchive("CRP_NM 누락"),
        reporterName = fields["IFR_NM"].value(),
        relationship = fields["STF_RYN"].value() ?: fields["MAIN_SH"].value(),
        position = fields["STF_PSM"].value(),
        ownershipPercent = fields["AFR_UN_RT"].value()?.toBigDecimalOrNull(),
        isCorrection = body.getElementsByTagName("CORRECTION").length > 0,
        holdings = Holdings(previous, current, delta, change),
        transactions = transactions.takeIf { foundTradeTable },
    )
}

private fun Element.children() = (0 until childNodes.length).asSequence()
    .mapNotNull { childNodes.item(it) as? Element }

private fun Element.fieldCode() = getAttribute("ACODE").ifEmpty { getAttribute("AUNIT") }
private fun Element?.value() = this?.textContent?.trim()?.takeUnless { it == "-" || it.isEmpty() }
private fun Element?.quantity() = value()?.replace(",", "")?.toLongOrNull()
private fun Element?.date() = this?.getAttribute("AUNITVALUE")?.takeIf { it.matches(Regex("[0-9]{8}")) }
    ?.let { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE).toString() }

private fun transaction(rowNo: Int, cells: Map<String, Element>): Transaction {
    val reason = cells["RPT_RSN"].value().orEmpty().removeSuffix("(+)").removeSuffix("(-)").trim()
    val price = cells["ACI_AMT2"].value()?.replace(Regex("[,\\s]"), "")
        ?.replace(Regex("(?:\\(원\\)|원|\\(\\))$"), "")?.toBigDecimalOrNull()
    val status = when {
        reason !in setOf("장내매수", "장내매도") -> "INELIGIBLE_REASON"
        price == null || price <= BigDecimal.ZERO -> "NO_UNIT_PRICE"
        else -> "NO_QUOTE"
    }
    return Transaction(rowNo, cells["MDF_DM"].date(), cells["STR_KND"].value().orEmpty(),
        reason, cells["MDF_STK_CNT"].quantity(), price, PriceComparison(status))
}
