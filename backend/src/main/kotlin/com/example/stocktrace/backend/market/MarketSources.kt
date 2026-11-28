package com.example.stocktrace.backend.market

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter.BASIC_ISO_DATE

internal class MarketSources(
    private val indexKey: String? = System.getenv("DATA_GO_KR_SERVICE_KEY"),
    private val exchangeKey: String? = System.getenv("KOREAEXIM_AUTH_KEY"),
    private val indexUrl: String = "https://apis.data.go.kr/1160100/GetMarketIndexInfoService_V2/getStockMarketIndex_V2",
    private val exchangeUrl: String = "https://oapi.koreaexim.go.kr/site/program/financial/exchangeJSON"
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    fun read(symbol: Symbol, day: LocalDate): Observation? {
        val name = symbol.indexName
        if (name != null) {
            if (indexKey.isNullOrBlank()) return null
            val body = get(indexUrl,
                mapOf("serviceKey" to indexKey, "idxNm" to name, "resultType" to "json", "numOfRows" to "100",
                    "beginBasDt" to day.minusDays(30).format(BASIC_ISO_DATE), "endBasDt" to day.format(BASIC_ISO_DATE)))
            return indexObservation(body, name, day)
        }
        if (exchangeKey.isNullOrBlank()) return null
        return usdRate(day) { date ->
            get(exchangeUrl,
                mapOf("authkey" to exchangeKey, "searchdate" to date.format(BASIC_ISO_DATE), "data" to "AP01"))
        }
    }

    private fun get(url: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (name, value) ->
            name + "=" + URLEncoder.encode(value, Charsets.UTF_8)
        }
        val request = HttpRequest.newBuilder(URI("$url?$query")).timeout(Duration.ofSeconds(4)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        check(response.statusCode() == 200)
        return response.body()
    }
}

private fun JsonObject.field(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString

internal fun indexObservation(json: String, name: String, day: LocalDate): Observation? {
    val response = JsonParser.parseString(json).asJsonObject.getAsJsonObject("response")
    check(response.getAsJsonObject("header").field("resultCode") in setOf("00", "0000"))
    val rows = response.getAsJsonObject("body").getAsJsonObject("items").getAsJsonArray("item")
    val latest = rows.map { it.asJsonObject }.filter { it.field("idxNm") == name }
        .map { LocalDate.parse(it.field("basDt"), BASIC_ISO_DATE) to it }
        .filter { it.first <= day }.maxByOrNull { it.first } ?: return null
    val (date, row) = latest
    val close = row.field("clpr")?.toBigDecimalOrNull() ?: return null
    return Observation(date, close, row.field("vs")?.toBigDecimalOrNull(), row.field("fltRt")?.toBigDecimalOrNull())
}

internal fun usdRate(day: LocalDate, load: (LocalDate) -> String): Observation? {
    var latest: Observation? = null
    for (offset in 0L..14L) {
        val date = day.minusDays(offset)
        val value = try {
            val rows = JsonParser.parseString(load(date)).asJsonArray
            if (rows.isEmpty) continue
            val usd = rows.map { it.asJsonObject }.single { it.field("cur_unit") == "USD" }
            check(usd.field("result") == "1")
            requireNotNull(usd.field("deal_bas_r")).replace(",", "").toBigDecimal()
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            // A failed date could be the previous publication. Never calculate across that gap.
            return latest
        }
        if (latest != null) return latest.copy(changeValue = latest.value - value)
        latest = Observation(date, value)
    }
    return latest
}
