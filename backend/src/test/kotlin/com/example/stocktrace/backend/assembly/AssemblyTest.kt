package com.example.stocktrace.backend.assembly

import com.example.stocktrace.backend.serve
import com.example.stocktrace.backend.disclosures.DartArchiveRepository
import com.example.stocktrace.backend.disclosures.DisclosureList
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AssemblyTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        val repo = DartArchiveRepository(Path.of("build/unused-assembly-test"))
        val index = DisclosureList()
        createContext("/") { exchange -> exchange.use { serve(it, repo, index) } }
        start()
    }
    private val client = HttpClient.newHttpClient()
    @After fun close() { server.stop(0); client.close() }

    private fun get(path: String, status: Int = 200): JsonObject {
        val uri = URI("http://127.0.0.1:" + server.address.port + "/api/v1/assembly/" + path)
        val result = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(result.body(), status, result.statusCode())
        return JsonParser.parseString(result.body()).asJsonObject
    }

    @Test fun exampleTotalsAndYearlyAffiliation() {
        val years = get("years").getAsJsonArray("items")
        assertEquals(listOf(2026, 2025), years.map { it.asJsonObject["year"].asInt })
        assertTrue(years.all { it.asJsonObject["dataMode"].asString == "EXAMPLE" })
        val totals = get("stocks/005930/parties?year=2026")
        assertEquals(1920L, totals["shares"].asLong)
        assertEquals(2, totals["holderCount"].asInt)
        val filtered = get("stocks?year=2026&partyId=reform").getAsJsonArray("items").first().asJsonObject
        assertEquals(680L, filtered["deltaShares"].asLong)
        assertEquals(6, get("parties?year=2026").getAsJsonArray("items").size())
    }

    @Test fun detailPreservesFamilyAndUnknownAndUnlinked() {
        val rows = get("members/ex-1/holdings?year=2026").getAsJsonArray("items").map { it.asJsonObject }
        assertEquals(80L, rows.single { it["relationship"].asString == "SPOUSE" }["shares"].asLong)
        val lg = rows.single { it["stockCode"].asString == "066570" }
        assertEquals(320L, lg["previousShares"].asLong)
        assertTrue(lg["shares"].isJsonNull && lg["deltaShares"].isJsonNull)
        assertEquals("UNKNOWN", lg["comparisonStatus"].asString)
        val missing = get("members/ex-3/holdings?year=2026")
        assertEquals("UNLINKED", missing["reportStatus"].asString)
        assertTrue(missing["sourceUrl"].isJsonNull && missing.getAsJsonArray("items").size() == 0)
        assertEquals("UNAVAILABLE", get("stocks?year=2026&partyId=other")["coverage"].asString)
    }

    @Test fun pagingFiltersAndErrors() {
        val first = get("members?year=2026&limit=1")
        val cursor = first["nextCursor"].asString
        val next = get("members?year=2026&limit=1&cursor=" + cursor)
        assertNotEquals(first["items"], next["items"])
        assertEquals("INVALID_ARGUMENT", get("members?year=2025&limit=1&cursor=" + cursor, 400)["code"].asString)
        assertEquals(0, get("members?year=2026&reportStatus=UNLINKED&stockCode=005930")["total"].asInt)
        assertEquals("INVALID_ARGUMENT", get("members", 400)["code"].asString)
        assertEquals("NOT_FOUND", get("members?year=1999", 404)["code"].asString)
        assertEquals("NOT_FOUND", get("stocks/999999/parties?year=2026", 404)["code"].asString)
    }

    @Test fun verifiedEmptyAndMissingDataDiffer() {
        val parties = get("parties?year=2026").getAsJsonArray("items").map { it.asJsonObject }
        val missing = parties.single { it["partyId"].asString == "other" }
        assertEquals(1, missing["memberCount"].asInt)
        assertTrue(missing["stockCount"].isJsonNull)
        val empty = parties.single { it["partyId"].asString == "progressive" }
        assertEquals("COMPLETE", empty["coverage"].asString)
        assertEquals(0, empty["stockCount"].asInt)
        assertEquals("PARTIAL", get("stocks?year=2026")["coverage"].asString)
    }

    @Test fun readFailureAndEmptyCatalog() {
        val unavailable = AssemblyApi { throw java.io.IOException("test") }
        // Mount on a supported route so the failure occurs during the repository read.
        server.createContext("/api/v1/assembly/years") { exchange ->
            exchange.use { unavailable.serve(it) }
        }
        assertEquals("SERVICE_UNAVAILABLE", get("years", 503)["code"].asString)
        server.removeContext("/api/v1/assembly/years")
        val empty = AssemblyApi { AssemblyReports(emptyList(), emptyMap()) }
        server.createContext("/api/v1/assembly/years") { exchange -> exchange.use { empty.serve(it) } }
        assertEquals(0, get("years").getAsJsonArray("items").size())
    }

    @Test fun unconfirmedAffiliationLimitsPartyBreakdown() {
        val report = assemblyExamples.years.first()
        val unknown = report.members.first().copy(party = null)
        val snapshot = report.copy(members = listOf(unknown))
        val breakdown = assemblyExamples.stockParties(snapshot, "005930")
        assertEquals("PARTIAL", breakdown["coverage"])
        assertEquals(1, breakdown["holderCount"])
        assertEquals(emptyList<Fields>(), breakdown["items"])
    }
}
