package com.example.stocktrace.backend

import com.example.stocktrace.backend.disclosures.*
import com.example.stocktrace.backend.market.MarketIndicators
import com.example.stocktrace.backend.market.MarketSources
import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.Executors
import java.nio.file.Path
import java.util.UUID

private const val DISCLOSURES = "/api/v1/disclosures/"
private val receiptPattern = Regex("[0-9]{14}")
private val json = GsonBuilder().serializeNulls().create()
private val market by lazy { MarketIndicators(MarketSources()::read) }

fun main(args: Array<String>) {
    val archiveDirectory = Path.of(System.getenv("DART_ARCHIVE_DIR") ?: "../build/dart-check")
    if (args.contentEquals(arrayOf("collect"))) return collectDisclosures(archiveDirectory)
    if (args.contentEquals(arrayOf("collect-windows"))) return collectDisclosures(archiveDirectory, windowsHttp = true)
    require(args.isEmpty()) { "지원하는 명령은 collect, collect-windows입니다." }
    val repository = DartArchiveRepository(archiveDirectory)
    val index = DisclosureList()
    readSnapshot(archiveDirectory)?.let(index::publish)
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()
    server.createContext("/") { exchange -> exchange.use { serve(it, repository, index) } }
    server.start()
    println("StockTrace API http://127.0.0.1:" + server.address.port)
}

internal fun serve(exchange: HttpExchange, repository: DartArchiveRepository, index: DisclosureList, indicators: () -> MarketIndicators = { market }) {
    val path = exchange.requestURI.path
    val marketRoute = path == "/api/v1/market/indicators"
    val listing = path == DISCLOSURES.removeSuffix("/")
    if (!marketRoute && !listing && (!path.startsWith(DISCLOSURES) || '/' in path.removePrefix(DISCLOSURES))) {
        return exchange.error(404, "NOT_FOUND", "요청 경로를 찾을 수 없습니다.")
    }
    if (exchange.requestMethod != "GET") {
        exchange.responseHeaders.set("Allow", "GET")
        return exchange.error(405, "METHOD_NOT_ALLOWED", "GET 요청을 사용해 주세요.")
    }
    if (marketRoute) {
        if (!exchange.requestURI.rawQuery.isNullOrEmpty()) {
            return exchange.error(400, "INVALID_ARGUMENT", "시장 지표에는 조회 조건이 없습니다.")
        }
        val items = indicators().items()
        if (items.all { it.value == null }) {
            return exchange.error(503, "SERVICE_UNAVAILABLE", "시장 지표를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.")
        }
        return exchange.respond(200, mapOf("items" to items))
    }
    if (listing) {
        val page = try {
            val params = mutableMapOf<String,String>()
            exchange.requestURI.rawQuery?.takeIf { it.isNotEmpty() }?.split('&')?.forEach { pair ->
                val name = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
                val value = URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
                require(params.put(name, value) == null)
            }
            index.page(params, LocalDate.now(seoul))
        } catch (_: IllegalArgumentException) {
            return exchange.error(400, "INVALID_ARGUMENT", "조회 조건을 확인해 주세요.")
        } catch (_: CursorExpired) {
            return exchange.error(410, "CURSOR_EXPIRED", "첫 페이지부터 다시 조회해 주세요.")
        } catch (_: CoverageUnavailable) {
            return exchange.error(503, "SERVICE_UNAVAILABLE", "해당 범위의 공시 자료를 준비 중입니다.")
        }
        return exchange.respond(200, page)
    }
    val receipt = path.removePrefix(DISCLOSURES)
    if (!receiptPattern.matches(receipt)) {
        return exchange.error(400, "INVALID_ARGUMENT", "접수번호는 숫자 14자리입니다.")
    }
    val report = try {
        repository.find(receipt) ?: index.summaryDetail(receipt)
    } catch (failure: Exception) {
        val requestId = UUID.randomUUID().toString()
        System.err.println("Failed to read disclosure $receipt [$requestId]: " + failure.javaClass.simpleName)
        return exchange.error(503, "SERVICE_UNAVAILABLE", "공시 자료를 읽을 수 없습니다.", requestId)
    }
    if (report == null) return exchange.error(404, "NOT_FOUND", "저장된 공시를 찾을 수 없습니다.")
    exchange.respond(200, report)
}

private fun HttpExchange.error(status: Int, code: String, message: String, requestId: String = UUID.randomUUID().toString()) {
    respond(status, mapOf("code" to code, "message" to message, "requestId" to requestId))
}

private fun HttpExchange.respond(status: Int, value: Any) {
    val bytes = json.toJson(value).toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.write(bytes)
}
