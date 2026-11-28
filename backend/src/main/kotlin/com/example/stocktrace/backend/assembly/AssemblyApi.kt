package com.example.stocktrace.backend.assembly

import com.example.stocktrace.backend.disclosures.CursorExpired
import com.example.stocktrace.backend.disclosures.stockCodePattern
import com.example.stocktrace.backend.error
import com.example.stocktrace.backend.respond
import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID

internal const val ASSEMBLY = "/api/v1/assembly/"
private class NotFound : RuntimeException()
private data class PageKey(val route: String, val filters: Map<String, String>, val size: Int)
private data class PageStart(val key: PageKey, val offset: Int)

// All requests on this service read the same immutable annual editions.
internal class AssemblyApi(private val load: () -> AssemblyReports) {
    private val cursors = object : LinkedHashMap<UUID, PageStart>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, PageStart>?) = size > 512
    }

    @Synchronized private fun page(route: String, params: Map<String, String>, rows: List<Fields>): Fields {
        val size = (params["limit"] ?: "20").toIntOrNull()
        require(size != null && size in 1..50)
        val key = PageKey(route, params - "cursor" - "limit", size)
        val start = params["cursor"]?.let {
            val id = UUID.fromString(it)
            require(id.toString() == it)
            val saved = cursors[id] ?: throw CursorExpired()
            require(saved.key == key)
            saved.offset
        } ?: 0
        val end = minOf(start + size, rows.size)
        val next = if (end == rows.size) null else {
            val target = PageStart(key, end)
            cursors.entries.find { it.value == target }?.key ?: UUID.randomUUID().also { cursors[it] = target }
        }
        return mapOf("items" to rows.subList(start, end), "nextCursor" to next)
    }

    fun serve(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") {
            exchange.responseHeaders.set("Allow", "GET")
            return exchange.error(405, "METHOD_NOT_ALLOWED", "GET 요청을 사용해 주세요.")
        }
        val result = try {
            val params = mutableMapOf<String, String>()
            exchange.requestURI.rawQuery?.takeIf { it.isNotEmpty() }?.split('&')?.forEach { field ->
                val name = URLDecoder.decode(field.substringBefore('='), Charsets.UTF_8)
                val value = URLDecoder.decode(field.substringAfter('=', ""), Charsets.UTF_8)
                require(params.put(name, value) == null)
            }
            query(exchange.requestURI.path.removePrefix(ASSEMBLY), params)
        } catch (_: IllegalArgumentException) {
            return exchange.error(400, "INVALID_ARGUMENT", "조회 조건을 확인해 주세요.")
        } catch (_: NotFound) {
            return exchange.error(404, "NOT_FOUND", "조회 대상을 찾을 수 없습니다.")
        } catch (_: CursorExpired) {
            return exchange.error(410, "CURSOR_EXPIRED", "첫 페이지부터 다시 조회해 주세요.")
        } catch (_: IOException) {
            return exchange.error(503, "SERVICE_UNAVAILABLE", "재산공개 자료를 읽을 수 없습니다.")
        }
        exchange.respond(200, result)
    }

    private fun query(path: String, params: Map<String, String>): Fields {
        val parts = path.split('/')
        val allowed = when {
            path == "years" -> emptySet()
            path == "parties" -> setOf("year")
            path == "stocks" -> setOf("year", "partyId", "limit", "cursor")
            path == "members" -> setOf("year", "partyId", "stockCode", "reportStatus", "q", "limit", "cursor")
            parts.size == 3 && ((parts.first() == "stocks" && parts.last() == "parties") ||
                (parts.first() == "members" && parts.last() == "holdings")) -> setOf("year")
            else -> throw NotFound()
        }
        require(params.keys.all { it in allowed })
        val data = load()
        if (path == "years") return mapOf("items" to data.years.sortedByDescending { it.year }.map { it.metadata() })
        val year = params["year"]
        require(year != null && year.matches(Regex("[0-9]{4}")))
        val report = data.years.find { it.year == year.toInt() } ?: throw NotFound()
        val party = params["partyId"]?.also { require(it in parties) }
        fun stock(value: String): String {
            require(value.matches(stockCodePattern))
            if (value !in data.stocks) throw NotFound()
            return value
        }
        val fields = when (path) {
            "parties" -> mapOf("items" to data.groups(report))
            "stocks" -> page(path, params, data.stockRows(report, party)) + mapOf("coverage" to report.coverage(party))
            "members" -> {
                val code = params["stockCode"]?.let(::stock)
                val status = params["reportStatus"]?.also { require(it in setOf("LINKED", "UNLINKED")) }
                val q = params["q"].orEmpty().trim()
                val rows = report.roster(party).filter { it.name.startsWith(q) && (code == null || it.self(code) > 0) &&
                    (status == null || it.status == status) }.sortedWith(compareByDescending<Member> { it.name == q }
                    .thenBy { it.name }.thenBy { it.id }).map { it.fields() }
                page(path, params, rows) + mapOf("total" to rows.size)
            }
            else -> if (parts.first() == "stocks") data.stockParties(report, stock(parts[1])) else {
                require(parts[1].isNotBlank())
                data.detail(report, report.members.find { it.id == parts[1] } ?: throw NotFound())
            }
        }
        return report.metadata() + fields
    }
}

internal val assemblyApi = AssemblyApi { assemblyExamples }
