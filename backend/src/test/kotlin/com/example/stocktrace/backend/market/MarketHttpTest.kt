package com.example.stocktrace.backend.market

import com.example.stocktrace.backend.disclosures.DartArchiveRepository
import com.example.stocktrace.backend.disclosures.DisclosureList
import com.example.stocktrace.backend.serve
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

class MarketHttpTest {
    @get:Rule val folder = TemporaryFolder()

    private fun request(market: MarketIndicators, method: String = "GET", suffix: String = ""): HttpResponse<String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val repository = DartArchiveRepository(folder.root.toPath())
        server.createContext("/") { exchange -> exchange.use { serve(it, repository, DisclosureList()) { market } } }
        server.start()
        try {
            val uri = URI("http://127.0.0.1:${server.address.port}/api/v1/market/indicators$suffix")
            val request = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build()
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        } finally {
            server.stop(0)
        }
    }

    private val empty = MarketIndicators({ _, _ -> null })

    @Test fun servesOfficialResponseShapesThroughLocalProviders() {
        val provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        provider.createContext("/") { exchange ->
            exchange.use {
                val query = it.requestURI.query
                val body = when {
                    it.requestURI.path == "/index" -> {
                        val name = if ("idxNm=코스피" in query) "코스피" else "코스닥"
                        """{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[{"idxNm":"$name","basDt":"20261120","clpr":"2500","fltRt":"0.80"}]}}}}"""
                    }
                    "searchdate=20261123" in query -> """[{"result":1,"cur_unit":"USD","deal_bas_r":"1,392.00"}]"""
                    "searchdate=20261120" in query -> """[{"result":1,"cur_unit":"USD","deal_bas_r":"1,390.50"}]"""
                    else -> "[]"
                }
                val bytes = body.toByteArray()
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        provider.start()
        try {
            val base = "http://127.0.0.1:${provider.address.port}"
            val sources = MarketSources("test", "test", "$base/index", "$base/exchange")
            val result = request(MarketIndicators(sources::read) { Instant.parse("2026-11-23T04:00:00Z") })
            assertEquals(200, result.statusCode())
            val rows = JsonParser.parseString(result.body()).asJsonObject.getAsJsonArray("items").map { it.asJsonObject }
            assertEquals(listOf("AVAILABLE","AVAILABLE","AVAILABLE"), rows.map { it["status"].asString })
            assertEquals(listOf("2026-11-20","2026-11-20","2026-11-23"), rows.map { it["referenceDate"].asString })
            assertEquals("1392.00", rows.last()["value"].asString)
            assertEquals("1.50", rows.last()["changeValue"].asString)
        } finally {
            provider.stop(0)
        }
    }

    @Test fun marketRouteRejectsPost() {
        val response = request(empty, "POST")
        assertEquals(405, response.statusCode())
        assertEquals("GET", response.headers().firstValue("Allow").get())
        assertEquals("METHOD_NOT_ALLOWED", JsonParser.parseString(response.body()).asJsonObject["code"].asString)
    }

    @Test fun rejectsUnknownFilters() {
        val response = request(empty, suffix = "?days=1")
        assertEquals(400, response.statusCode())
        assertEquals("INVALID_ARGUMENT", JsonParser.parseString(response.body()).asJsonObject["code"].asString)
    }

    @Test fun totalFailureIsRetryable() {
        val response = request(empty)
        assertEquals(503, response.statusCode())
        val error = JsonParser.parseString(response.body()).asJsonObject
        assertEquals("SERVICE_UNAVAILABLE", error["code"].asString)
        assertTrue(error["requestId"].asString.isNotBlank())
        assertTrue(error["message"].asString.isNotBlank())
    }

    @Test fun partialFailureKeepsThreeTypedRows() {
        val market = MarketIndicators({ symbol, day ->
            if (symbol == Symbol.USD_KRW) null else Observation(day, "2500".toBigDecimal(), changePercent = "0.8".toBigDecimal())
        }, { Instant.parse("2026-11-23T01:00:00Z") })
        val response = request(market)
        assertEquals(200, response.statusCode())
        val items = JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("items")
        assertEquals(3, items.size())
        val index = items[0].asJsonObject
        assertTrue(index["value"].asJsonPrimitive.isNumber)
        assertEquals("0.8", index["changePercent"].asString)
        assertEquals("2026-11-23", index["referenceDate"].asString)
        assertTrue(index["asOf"].isJsonNull)
        val fx = items[2].asJsonObject
        assertEquals("KRW_PER_USD", fx["unit"].asString)
        assertEquals("UNAVAILABLE", fx["status"].asString)
        assertTrue(fx["referenceDate"].isJsonNull)
    }
}
