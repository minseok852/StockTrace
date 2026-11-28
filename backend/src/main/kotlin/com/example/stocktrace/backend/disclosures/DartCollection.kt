package com.example.stocktrace.backend.disclosures

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.net.ssl.SSLException

internal val seoul: ZoneId = ZoneId.of("Asia/Seoul")
private const val INDEX = "disclosures.json"
private val gson: Gson = GsonBuilder().serializeNulls().create()

internal fun readSnapshot(directory: Path): DisclosureSnapshot? {
    val path = directory.resolve(INDEX)
    if (Files.notExists(path)) return null
    return Files.newBufferedReader(path).use { gson.fromJson(it, DisclosureSnapshot::class.java) }
}

private data class Filing(val id: String, val date: String, val code: String, val corp: String, val name: String,
    val correction: Boolean, val excluded: Boolean)

private class DartHttp(private val key: String) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun request(endpoint: String, query: String): ByteArray {
        val uri = URI.create("https://opendart.fss.or.kr/api/$endpoint?crtfc_key=$key&$query")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() == 200) { "OpenDART HTTP " + response.statusCode() }
        return response.body()
    }
}

private class DartDownloadFailed(message: String, cause: IOException) : IOException(message, cause)

private class OpenDart(val send: (String,String) -> ByteArray) {
    private fun request(endpoint: String, query: String): ByteArray {
        val delays = listOf(1L, 2L, 4L)
        var retries = 0
        while (true) {
            try {
                return send(endpoint, query)
            } catch (failure: IOException) {
                if (failure is SSLException || Thread.currentThread().isInterrupted) throw failure
                val resource = if (endpoint == "document.xml") "공시 " + query.substringAfter("rcept_no=") else endpoint
                if (retries == delays.size) {
                    throw DartDownloadFailed("OpenDART $resource 다운로드 실패 (총 4회 시도).", failure)
                }
                val seconds = delays[retries++]
                System.err.println("OpenDART $resource 통신 실패. ${seconds}초 후 재시도 ($retries/${delays.size})")
                Thread.sleep(seconds * 1000)
            }
        }
    }

    fun issuers(directory: Path, today: LocalDate): Map<String,String> {
        val cache = directory.resolve("issuer-cache").resolve("corpCode.zip")
        if (Files.isRegularFile(cache) && Files.getLastModifiedTime(cache).toInstant().atZone(seoul).toLocalDate() == today) {
            return parseIssuers(Files.readAllBytes(cache))
        }
        val bytes = request("corpCode.xml", "")
        val issuers = parseIssuers(bytes)
        Files.createDirectories(cache.parent)
        val temp = Files.createTempFile(cache.parent, "corp-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.setLastModifiedTime(temp, FileTime.from(today.atStartOfDay(seoul).toInstant()))
            Files.move(temp, cache, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        return issuers
    }

    private fun parseIssuers(bytes: ByteArray): Map<String,String> {
        if (bytes.size < 2 || bytes[0] != 80.toByte() || bytes[1] != 75.toByte()) {
            val status = Regex("<status>([0-9]+)</status>").find(bytes.decodeToString())?.groupValues?.get(1)
            error("OpenDART corpCode.xml: " + (status ?: "기업코드 ZIP 응답을 확인할 수 없습니다."))
        }
        val result = mutableMapOf<String,String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            check(generateSequence { zip.nextEntry }.any { it.name.equals("CORPCODE.xml", ignoreCase = true) })
            val xml = XMLInputFactory.newFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            }.createXMLStreamReader(zip)
            var corp = ""
            var stock = ""
            while (xml.hasNext()) {
                when (xml.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (xml.localName) {
                        "list" -> { corp = ""; stock = "" }
                        "corp_code" -> corp = xml.elementText.trim()
                        "stock_code" -> stock = xml.elementText.trim()
                    }
                    XMLStreamConstants.END_ELEMENT -> if (xml.localName == "list" && stock.matches(stockCodePattern)) {
                        check(corp.matches(Regex("[0-9]{8}")))
                        val previous = result.put(stock, corp)
                        check(previous == null || previous == corp)
                    }
                }
            }
            xml.close()
        }
        check(result.isNotEmpty())
        return result
    }

    fun filings(start: LocalDate, end: LocalDate, latest: Boolean, uniqueCodes: Map<String,String?>): Map<String,Filing> {
        val reports = mutableMapOf<String,Filing>()
        val dates = "bgn_de=" + start.format(BASIC_ISO_DATE) + "&end_de=" + end.format(BASIC_ISO_DATE)
        val query = "$dates&pblntf_detail_ty=D002&page_count=100&last_reprt_at=" + if (latest) "Y" else "N"
        var page = 1
        var total: Int? = null
        var received = 0
        do {
            val json = JsonParser.parseString(request("list.json", "$query&page_no=$page").decodeToString()).asJsonObject
            val status = json["status"].asString
            if (status == "013" && page == 1) return emptyMap()
            check(status == "000") { "OpenDART list status $status" }
            val count = json["total_count"].asInt
            check(total == null || count == total) { "조회 중 공시 건수가 바뀌었습니다. 다시 수집해 주세요." }
            total = count
            check(json["page_no"].asInt == page)
            val batch = json["list"].asJsonArray
            received += batch.size()
            for (element in batch) {
                val row = element.asJsonObject
                if (row["corp_cls"].asString !in setOf("Y","K","N")) continue
                val remark = row["rm"]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (latest && ('철' in remark || '정' in remark)) continue
                val id = row["rcept_no"]?.takeUnless { it.isJsonNull }?.asString.orEmpty().trim()
                val corp = row["corp_code"].asString.trim()
                check(id.matches(Regex("[0-9]{14}"))) { "공시 접수번호 형식 오류: 법인 $corp, 접수번호 '$id', 페이지 $page" }
                val reported = row["stock_code"]?.takeUnless { it.isJsonNull }?.asString.orEmpty().trim()
                val code = reported.ifEmpty { uniqueCodes[corp].orEmpty() }
                check(code.matches(stockCodePattern)) { "종목코드 확인 필요: 접수번호 $id, 법인 $corp, 원문 종목코드 '$reported'" }
                val date = LocalDate.parse(row["rcept_dt"].asString, BASIC_ISO_DATE)
                check(date in start..end)
                val filing = Filing(id, date.toString(), code, corp, row["corp_name"].asString,
                    "정정" in row["report_nm"].asString, '철' in remark || '정' in remark)
                check(reports.put(id, filing) == null) { "중복된 접수번호 $id" }
            }
            page++
        } while (page <= json["total_page"].asInt)
        check(received == total)
        return reports
    }

    fun prepareArchive(directory: Path, id: String) {
        val destination = directory.resolve("$id.zip")
        if (Files.isRegularFile(destination)) return
        val bytes = request("document.xml", "rcept_no=$id")
        if (bytes.take(2) != listOf(80.toByte(), 75.toByte())) {
            val status = Regex("<status>([0-9]+)</status>").find(bytes.decodeToString())?.groupValues?.get(1)
            if (status == "014") return
            error("OpenDART document status $status")
        }
        val temp = Files.createTempFile(directory, "archive-", ".tmp")
        try {
            Files.write(temp, bytes)
            ZipFile(temp.toFile()).use { checkNotNull(it.getEntry("$id.xml")) }
            Files.move(temp, destination, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
    }
}

private fun collectWithWindowsHttp(directory: Path, today: LocalDate, key: String) {
    val responseFile = Files.createTempFile("stocktrace-dart-", ".tmp")
    try {
        val script = Path.of("../tools/resume-dart-collection.ps1").toAbsolutePath().normalize()
        val worker = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-File", script.toString(),
            "-ResponseFile", responseFile.toString()).apply {
            environment()["OPENDART_API_KEY"] = key
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()
        try {
            worker.outputWriter(Charsets.US_ASCII).use { requests ->
                worker.inputReader(Charsets.US_ASCII).use { replies ->
                    collectDisclosures(directory, today) { endpoint, query ->
                        requests.write("$endpoint?$query")
                        requests.newLine()
                        requests.flush()
                        val status = checkNotNull(replies.readLine()) { "Windows HTTP 프로세스가 응답 전에 종료됐습니다." }
                        if (status != "OK") {
                            check(status.startsWith("OpenDART request failed")) { "Windows HTTP 응답 형식 오류" }
                            throw IOException(status)
                        }
                        Files.readAllBytes(responseFile)
                    }
                }
            }
        } finally {
            if (!worker.waitFor(3, TimeUnit.SECONDS)) worker.destroyForcibly().waitFor()
        }
    } finally { Files.deleteIfExists(responseFile) }
}

internal fun collectDisclosures(directory: Path, windowsHttp: Boolean = false) {
    val envFile = Path.of("../.env")
    val key = System.getenv("OPENDART_API_KEY") ?: Files.readAllLines(envFile)
        .single { it.substringBefore('=').trim() == "OPENDART_API_KEY" }.substringAfter('=').trim().trim('"', '\'')
    require(key.matches(Regex("[A-Za-z0-9]{40}"))) { "OPENDART_API_KEY 형식을 확인해 주세요." }
    val today = LocalDate.now(seoul)
    if (windowsHttp) collectWithWindowsHttp(directory, today, key)
    else collectDisclosures(directory, today, DartHttp(key)::request)
}

private fun Filing.toItem(detail: DisclosureDetail?): DisclosureItem {
    val preview = detail?.transactions?.take(2)?.map {
        TradePreview(it.rowNo, it.reason, it.changedOn, it.deltaShares, it.unitPriceKrw)
    }.orEmpty()
    return DisclosureItem(id, date, code, name, detail?.reporterName, detail?.position, correction,
        detail?.holdings?.deltaShares, preview, detail?.transactions?.size)
}

private fun writeSnapshot(directory: Path, snapshot: DisclosureSnapshot) {
    val temp = Files.createTempFile(directory, "disclosures-", ".tmp")
    try {
        Files.newBufferedWriter(temp).use { gson.toJson(snapshot, it) }
        Files.move(temp, directory.resolve(INDEX), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}

internal fun collectDisclosures(directory: Path, today: LocalDate, request: (String,String) -> ByteArray) {
    val dart = OpenDart(request)
    Files.createDirectories(directory)
    FileChannel.open(directory.resolve("collect.lock"), CREATE, WRITE).use { channel ->
        checkNotNull(channel.tryLock()) { "이미 공시 수집이 진행 중입니다." }.use {
            val startFile = directory.resolve("collection-start.txt")
            if (Files.notExists(startFile)) {
                val initial = readSnapshot(directory)?.from ?: today.minusDays(89).toString()
                val temp = Files.createTempFile(directory, "start-", ".tmp")
                try {
                    Files.writeString(temp, initial)
                    Files.move(temp, startFile, ATOMIC_MOVE)
                } finally { Files.deleteIfExists(temp) }
            }
            val start = LocalDate.parse(Files.readString(startFile).trim())
            check(!start.isAfter(today))
            val issuers = dart.issuers(directory, today).toMutableMap()
            val uniqueCodes = issuers.entries.groupBy({ it.value }, { it.key })
                .mapValues { (_, codes) -> codes.singleOrNull() }
            val archive = DartArchiveRepository(directory)
            val effective = mutableMapOf<String,Filing>()
            val missing = mutableListOf<String>()
            var historyCount = 0
            var from = start
            while (!from.isAfter(today)) {
                val to = minOf(from.plusDays(29), today)
                val history = dart.filings(from, to, false, uniqueCodes)
                val latest = dart.filings(from, to, true, uniqueCodes)
                check(history.keys.containsAll(latest.keys)) { "전체 목록과 최종보고서 목록이 다릅니다. 다시 수집해 주세요." }
                historyCount += history.size
                for (filing in history.values) {
                    if (!Files.isRegularFile(directory.resolve("${filing.id}.zip"))) missing += filing.id
                    if (filing.id !in latest || filing.excluded) continue
                    check(issuers.getOrPut(filing.code) { filing.corp } == filing.corp)
                    effective[filing.id] = filing
                }
                from = to.plusDays(1)
            }
            val items = effective.mapValues { (id, filing) -> filing.toItem(archive.find(id)) }.toMutableMap()
            fun publish() = writeSnapshot(directory, DisclosureSnapshot(start.toString(), today.toString(), items.values.toList(), issuers))
            publish()
            println("목록 저장 완료 $start ~ $today · 유효 공시 ${items.size}건")

            var remaining = missing.size
            var updated = false
            for (id in missing) {
                try {
                    dart.prepareArchive(directory, id)
                } catch (failure: DartDownloadFailed) {
                    System.err.println("${failure.message} 이번 원문 수집을 중단합니다.")
                    break
                }
                if (!Files.isRegularFile(directory.resolve("$id.zip"))) continue
                remaining--
                val filing = effective[id] ?: continue
                items[id] = filing.toItem(archive.find(id))
                updated = true
            }
            if (updated) publish()
            println("원문 ZIP 확보 ${historyCount - remaining}/${historyCount}건 · 미확보 ${remaining}건 (정정·철회 이력 포함)")
        }
    }
}
