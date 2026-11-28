package com.example.stocktrace.backend

import com.example.stocktrace.backend.disclosures.DartArchiveRepository
import com.google.gson.GsonBuilder
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.UUID

private const val DISCLOSURES = "/api/v1/disclosures/"
private val receiptPattern = Regex("[0-9]{14}")
private val json = GsonBuilder().serializeNulls().create()

fun main() {
    val archiveDirectory = Path.of(System.getenv("DART_ARCHIVE_DIR") ?: "../build/dart-check")
    val repository = DartArchiveRepository(archiveDirectory)
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/") { exchange -> exchange.use { serve(it, repository) } }
    server.start()
    println("StockTrace API http://127.0.0.1:" + server.address.port)
}

private fun serve(exchange: HttpExchange, repository: DartArchiveRepository) {
    val path = exchange.requestURI.path
    if (!path.startsWith(DISCLOSURES) || '/' in path.removePrefix(DISCLOSURES)) {
        return exchange.error(404, "NOT_FOUND", "요청 경로를 찾을 수 없습니다.")
    }
    if (exchange.requestMethod != "GET") {
        exchange.responseHeaders.set("Allow", "GET")
        return exchange.error(405, "METHOD_NOT_ALLOWED", "GET 요청을 사용해 주세요.")
    }
    val receipt = path.removePrefix(DISCLOSURES)
    if (!receiptPattern.matches(receipt)) {
        return exchange.error(400, "INVALID_ARGUMENT", "접수번호는 숫자 14자리입니다.")
    }
    val report = try {
        repository.find(receipt)
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
