package com.example.stocktrace.backend.disclosures

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class DisclosureListTest {
    @get:Rule val folder = TemporaryFolder()
    private val today = LocalDate.parse("2026-11-28")
    private fun row(n: Int, delta: Long?) = DisclosureItem(
        "2026112800000$n", "2026-11-28", "005930", "삼성전자", null, null, false, delta, emptyList(), null
    )
    private val rows = listOf(row(1, null), row(3, -10), row(2, 5))
    private val snapshot = DisclosureSnapshot("2026-08-31", "2026-11-28", rows)

    @Test fun readsLiteralAmpersandAlongsideEscapedAndCdataText() {
        val receipt = "20260922000201"
        val directory = folder.root.toPath()
        val xml = """<DOCUMENT><BODY>
            <TE ACODE="CRP_CD">005930</TE>
            <TE ACODE="CRP_NM">NEXTURN&ROLLKOREA &amp; Co. &#38; 한글</TE>
            <TE ACODE="IFR_NM"><![CDATA[A&B]]></TE>
            </BODY></DOCUMENT>"""
        ZipOutputStream(Files.newOutputStream(directory.resolve("$receipt.zip"))).use {
            it.putNextEntry(ZipEntry("$receipt.xml"))
            it.write(xml.toByteArray())
        }
        val detail = DartArchiveRepository(directory).find(receipt)!!
        assertEquals("NEXTURN&ROLLKOREA & Co. & 한글", detail.companyName)
        assertEquals("A&B", detail.reporterName)
    }

    @Test fun homeAndContinuationUseTheSameSnapshot() {
        val index = DisclosureList()
        index.publish(snapshot)
        val first = index.page(mapOf("limit" to "2"), today)
        assertEquals(listOf(row(3,-10), row(2,5)), first.items)
        val query = mapOf("limit" to "2", "cursor" to first.nextCursor!!)
        index.publish(snapshot.copy(items = emptyList()))
        assertEquals(listOf(row(1,null)), index.page(query, today.plusDays(1)).items)
        assertEquals(index.page(query, today), index.page(query, today))
        assertThrows(IllegalArgumentException::class.java) { index.page(query + ("days" to "30"), today) }
    }

    @Test fun countsAreComputedBeforeDirectionFilter() {
        val index = DisclosureList()
        index.publish(snapshot)
        val result = index.page(mapOf("days" to "30", "direction" to "DECREASE"), today)
        assertEquals(Counts(3,1,1,0,1), result.counts)
        assertEquals(listOf(row(3,-10)), result.items)
        assertEquals("2026-10-30", result.period!!.from)
    }

    @Test fun incompleteCoverageAndConfirmedEmptyAreDistinct() {
        val index = DisclosureList()
        assertThrows(CoverageUnavailable::class.java) { index.page(emptyMap(), today) }
        assertThrows(IllegalArgumentException::class.java) { index.page(mapOf("days" to "2"), today) }
        index.publish(snapshot.copy(items = emptyList()))
        assertTrue(index.page(emptyMap(), today).items.isEmpty())
        assertThrows(CoverageUnavailable::class.java) { index.page(mapOf("days" to "7"), today.plusDays(1)) }
    }

    @Test fun collectionPublishesOnlyCompletedHistoryAndKeepsItsStartDate() {
        checkCollection("005930", "005930")
    }

    @Test fun collectionAcceptsAlphanumericStockCodes() {
        checkCollection("0001A0", "0001A0")
    }

    @Test fun missingStockCodeUsesUnambiguousIssuerMapping() {
        checkCollection("0001A0", " ")
    }

    @Test fun disconnectedDocumentRequestResumesSameCollection() {
        checkCollection("005930", "005930", resets = 1)
    }

    @Test fun repeatedCollectionReusesVerifiedIssuerArchiveOnSameDate() {
        checkCollection("005930", "005930", repeatOnSameDate = true)
    }

    @Test fun repeatedDocumentDisconnectsKeepVerifiedListAvailable() {
        checkCollection("005930", "005930", resets = 4)
    }

    private fun xmlZip(name: String, xml: String) = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
            }
        }.toByteArray()

    private fun checkCollection(catalogCode: String, reportedCode: String, resets: Int = 0, repeatOnSameDate: Boolean = false) {
        val directory = folder.root.toPath()
        val corpZip = xmlZip("CORPCODE.xml",
            "<result><list><corp_code>00126380</corp_code><stock_code>$catalogCode</stock_code></list></result>")
        val receipt = "20261128000001"
        val report = """{"corp_cls":"Y","rm":"","rcept_no":"$receipt","rcept_dt":"20261128","stock_code":"$reportedCode","corp_code":"00126380","corp_name":"삼성전자","report_nm":"소유보고"}"""
        val response = """{"status":"000","page_no":1,"total_page":1,"total_count":1,"list":[$report]}"""
        var documentCalls = 0
        val request: (String,String) -> ByteArray = { endpoint, query ->
            when (endpoint) {
                "corpCode.xml" -> corpZip
                "list.json" -> if ("end_de=20261128" in query) response.toByteArray() else """{"status":"013"}""".toByteArray()
                "document.xml" -> {
                    assertEquals(receipt, readSnapshot(directory)?.items?.single()?.receiptNo)
                    assertEquals("rcept_no=$receipt", query)
                    if (++documentCalls <= resets) throw IOException("header parser received no bytes", SocketException("Connection reset"))
                    "<result><status>014</status></result>".toByteArray()
                }
                else -> error(endpoint)
            }
        }
        collectDisclosures(directory, today, request)
        assertEquals(minOf(resets + 1, 4), documentCalls)
        assertFalse(Files.exists(directory.resolve("$receipt.zip")))
        val saved = readSnapshot(directory)!!
        if (repeatOnSameDate) {
            collectDisclosures(directory, today) { endpoint, query ->
                assertNotEquals("corpCode.xml", endpoint)
                request(endpoint, query)
            }
            assertEquals(saved, readSnapshot(directory))
        }
        assertEquals(today.minusDays(89).toString(), saved.from)
        assertEquals(receipt, saved.items.single().receiptNo)
        assertEquals("UNKNOWN", saved.items.single().direction)
        assertNull(saved.items.single().tradeRowCount)
        val index = DisclosureList()
        index.publish(saved)
        assertEquals(catalogCode, index.page(mapOf("stockCode" to catalogCode), today).items.single().stockCode)
        assertThrows(IllegalStateException::class.java) {
            collectDisclosures(directory, today.plusDays(1)) { endpoint, query ->
                if (endpoint == "list.json") error("모의 수집 실패")
                request(endpoint, query)
            }
        }
        assertEquals(saved, readSnapshot(directory))
        if (resets == 4) {
            val xml = """<DOCUMENT><BODY><TE ACODE="CRP_CD">$catalogCode</TE><TE ACODE="CRP_NM">삼성전자</TE>
                <TE ACODE="BFR_UN_CNT">10</TE><TE ACODE="AFR_UN_CNT">15</TE></BODY></DOCUMENT>"""
            collectDisclosures(directory, today) { endpoint, query ->
                if (endpoint == "document.xml") xmlZip("$receipt.xml", xml) else request(endpoint, query)
            }
            val recovered = readSnapshot(directory)!!
            assertEquals(5L, recovered.items.single().holdingDeltaShares)
            collectDisclosures(directory, today) { endpoint, query ->
                assertNotEquals("document.xml", endpoint)
                request(endpoint, query)
            }
            assertEquals(recovered, readSnapshot(directory))
        }
    }
}
