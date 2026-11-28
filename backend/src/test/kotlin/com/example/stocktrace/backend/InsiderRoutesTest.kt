package com.example.stocktrace.backend

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.example.stocktrace.backend.disclosures.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.http.*
import java.nio.file.Files
import java.util.zip.*

class InsiderRoutesTest {
    @get:Rule val folder = TemporaryFolder()
    private val dir get() = folder.root.toPath()
    private val receipt = "20261125000001"
    private val date = "2026-11-25"
    private fun item(id: String = receipt, filed: String = date, code: String = "261200") =
        DisclosureItem(id, filed, code, "예시 회사", null, null, false, null, emptyList(), null)

    private fun snapshot(items: List<DisclosureItem> = listOf(item())) {
        Files.writeString(dir.resolve("disclosures.json"), Gson().toJson(DisclosureSnapshot("2026-08-01",date,items)))
    }

    private fun archive(report: DisclosureItem = item(), kind: String = "개인(국내)", identity: String = "800101") {
        fun cell(code: String, value: String) = """<TE ACODE="$code">$value</TE>"""
        fun trade(reason: String, qty: Int, price: Int) = "<TR>" + cell("RPT_RSN", reason) +
            cell("STR_KND","보통주") + cell("MDF_STK_CNT",qty.toString()) + cell("ACI_AMT2",price.toString()) + "</TR>"
        val xml = "<DOCUMENT><BODY>" + cell("CRP_CD",report.stockCode) + cell("CRP_NM",report.companyName) +
            cell("IFR_TP",kind) + cell("STF_RYN","등기임원") + cell("IFR_NM","테스트") + cell("IFR_ID2",identity) +
            """<TABLE-GROUP ACLASS="RPT_RSN">""" + trade("장내매수",2000,2365) + trade("수증",3,10) +
            trade("장내매수",1200,2480) + trade("장내매도",-10,2500) + "</TABLE-GROUP></BODY></DOCUMENT>"
        ZipOutputStream(Files.newOutputStream(dir.resolve("${report.receiptNo}.zip"))).use {
            it.putNextEntry(ZipEntry("${report.receiptNo}.xml"))
            it.write(xml.toByteArray())
        }
    }

    private fun checkApi(catalogConnected: Boolean = true, block: ((String, Int) -> JsonObject) -> Unit) {
        val server = createServer(dir,0, if (catalogConnected) { code -> mapOf("stockCode" to code) } else null)
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            block { suffix, expected ->
                val url = "http://127.0.0.1:${server.address.port}/api/v1/insider-trades$suffix"
                val response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(response.body(), expected, response.statusCode())
                JsonParser.parseString(response.body()).asJsonObject
            }
        } finally { server.stop(0) }
    }

    @Test fun rankingReconcilesWithEveryContributingOriginalRow() {
        snapshot()
        archive()
        checkApi { get ->
            val rank = get("/rankings?to=$date",200)["items"].asJsonArray.single().asJsonObject
            assertEquals(7706000,rank["amountKrw"].asInt)
            assertEquals(1,rank["executiveCount"].asInt)
            val query = "?to=$date&stockCode=261200&scope=RANKED_EXECUTIVES&tradeType=BUY&days=30&limit=1"
            val first = get(query,200)
            val continuation = query + "&cursor=" + first["nextCursor"].asString
            val second = get(continuation,200)
            assertEquals(second,get(continuation,200))
            assertEquals("INVALID_ARGUMENT",get(continuation.replace("tradeType=BUY","tradeType=SELL"),400)["code"].asString)
            val rows = listOf(first, second).map { it["items"].asJsonArray.single().asJsonObject }
            assertEquals(listOf(1,3),rows.map { it["rowNo"].asInt })
            assertEquals(rank["amountKrw"].asInt,rows.sumOf { it["amountKrw"].asInt })
            assertTrue(second["nextCursor"].isJsonNull)
            assertEquals("OTHER",get("?to=$date",200)["items"].asJsonArray[1].asJsonObject["tradeType"].asString)
            assertEquals(25000,get("/rankings?side=SELL&to=$date",200)["items"].asJsonArray.single().asJsonObject["amountKrw"].asInt)
        }
    }

    @Test fun unavailableRangeAndInvalidRequestsAreDistinctFromEmpty() {
        snapshot()
        checkApi { get ->
            assertEquals("SERVICE_UNAVAILABLE",get("/rankings?to=$date",503)["code"].asString)
            assertEquals("SERVICE_UNAVAILABLE",get("?to=$date",503)["code"].asString)
            assertEquals("INVALID_ARGUMENT",get("?scope=RANKED_EXECUTIVES",400)["code"].asString)
            assertEquals("INVALID_ARGUMENT",get("/rankings?limit=51",400)["code"].asString)
            assertEquals("CURSOR_EXPIRED",get("?cursor=00000000-0000-4000-8000-000000000000",410)["code"].asString)
            assertEquals("INVALID_ARGUMENT",get("?to=2099-01-01",400)["code"].asString)
            assertEquals(0,get("/rankings?to=2026-11-24",200)["items"].asJsonArray.size())
        }
    }

    @Test fun countsPeopleAcrossFilingsAndRanksTiesByCodeWithinInclusivePeriod() {
        val reports = listOf(item(), item("20261027000001","2026-10-27"),
            item("20261026000001","2026-10-26"), item("20261125000002",date,"000001"),
            item("20261125000003",date,"000001"), item("20261125000004"), item("20261125000005"))
        snapshot(reports)
        reports.take(5).forEach { archive(it) }
        archive(reports[5],kind="국내법인")
        archive(reports[6],identity="-")
        checkApi { get ->
            val ranks = get("/rankings?to=$date",200)["items"].asJsonArray
            assertEquals(listOf("000001","261200"), ranks.map { it.asJsonObject["stock"].asJsonObject["stockCode"].asString })
            for (row in ranks) {
                assertEquals(15412000,row.asJsonObject["amountKrw"].asInt)
                assertEquals(1,row.asJsonObject["executiveCount"].asInt)
            }
        }
    }

    @Test fun unconnectedCatalogDisablesOnlyRanking() {
        snapshot()
        archive()
        checkApi(catalogConnected = false) { get ->
            assertEquals("SERVICE_UNAVAILABLE",get("/rankings?to=$date",503)["code"].asString)
            assertEquals(4,get("?to=$date",200)["items"].asJsonArray.size())
        }
    }

    @Test fun missingArchiveDirectoryReturnsRetryableJson() {
        snapshot()
        checkApi { get ->
            Files.move(dir, dir.resolveSibling(dir.fileName.toString() + "-moved")).also { moved ->
                try {
                    assertEquals("SERVICE_UNAVAILABLE",get("?to=$date",503)["code"].asString)
                } finally { Files.move(moved,dir) }
            }
        }
    }
}
