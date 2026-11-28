package com.example.stocktrace.backend.disclosures

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

internal val stockCodePattern = Regex("[0-9A-Z]{6}")

data class TradePreview(val rowNo: Int, val reason: String, val changedOn: String?, val deltaShares: Long?, val unitPriceKrw: BigDecimal?)
data class DisclosureItem(
    val receiptNo: String, val filedOn: String, val stockCode: String, val companyName: String,
    val reporterName: String?, val position: String?, val isCorrection: Boolean, val holdingDeltaShares: Long?,
    val tradePreview: List<TradePreview>, val tradeRowCount: Int?,
) {
    val direction = when {
        holdingDeltaShares == null -> "UNKNOWN"
        holdingDeltaShares > 0 -> "INCREASE"
        holdingDeltaShares < 0 -> "DECREASE"
        else -> "UNCHANGED"
    }
}
data class DisclosureSnapshot(val from: String, val to: String, val items: List<DisclosureItem>,
    val issuers: Map<String,String> = emptyMap())
data class Counts(val total: Int, val increase: Int, val decrease: Int, val unchanged: Int, val unknown: Int)
data class Period(val from: String, val to: String)
data class DisclosurePage(val period: Period?, val counts: Counts, val items: List<DisclosureItem>, val nextCursor: String?)
class CoverageUnavailable : RuntimeException()
class CursorExpired : RuntimeException()

private data class Filter(val days: Int?, val stockCode: String?, val direction: String, val limit: Int)
private data class Selection(val period: Period?, val counts: Counts, val rows: List<DisclosureItem>) {
    val cursors = mutableMapOf<Int, UUID>()
}
private data class Continuation(val filter: Filter, val selection: Selection, val offset: Int)

private fun <K,V> boundedMap(capacity: Int) = object : LinkedHashMap<K,V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K,V>?) = size > capacity
}

class DisclosureList {
    private var snapshot: DisclosureSnapshot? = null
    private var knownCodes: Set<String> = emptySet()
    private val selections = boundedMap<Pair<Filter, LocalDate>, Selection>(64)
    private val continuations = boundedMap<UUID, Continuation>(512)

    @Synchronized fun publish(data: DisclosureSnapshot) {
        snapshot = data.copy(items = data.items.sortedWith(compareByDescending<DisclosureItem> { it.filedOn }.thenByDescending { it.receiptNo }))
        knownCodes = data.issuers.keys + data.items.map { it.stockCode }
        selections.clear()
    }

    @Synchronized fun summaryDetail(receipt: String): DisclosureDetail? {
        val item = snapshot?.items?.find { it.receiptNo == receipt } ?: return null
        return DisclosureDetail(receipt, item.filedOn, null, item.stockCode, item.companyName,
            item.reporterName, null, item.position, null, item.isCorrection, Holdings(null,null,null,null), null)
    }

    @Synchronized fun page(params: Map<String,String>, today: LocalDate): DisclosurePage {
        require(params.keys.all { it in setOf("days", "stockCode", "direction", "limit", "cursor") })
        val days = params["days"]?.let { require(it in setOf("7","30","90")); it.toInt() }
        val code = params["stockCode"]?.also { require(it.matches(stockCodePattern)) }
        val direction = params["direction"] ?: "ALL"
        require(direction in setOf("ALL","INCREASE","DECREASE"))
        val limit = (params["limit"] ?: "20").toIntOrNull()
        require(limit != null && limit in 1..50)
        val filter = Filter(days, code, direction, limit)
        val continuation = params["cursor"]?.let {
            val token = UUID.fromString(it)
            require(token.toString() == it)
            val saved = continuations[token] ?: throw CursorExpired()
            require(saved.filter == filter)
            saved
        }
        val selection = continuation?.selection ?: selections.getOrPut(filter to today) { select(filter, today) }
        val start = continuation?.offset ?: 0
        val end = minOf(start + limit, selection.rows.size)
        val next = if (end < selection.rows.size) {
            selection.cursors.getOrPut(end) { UUID.randomUUID() }.also {
                continuations[it] = Continuation(filter, selection, end)
            }.toString()
        } else null
        return DisclosurePage(selection.period, selection.counts, selection.rows.subList(start,end), next)
    }

    private fun select(filter: Filter, today: LocalDate): Selection {
        val data = snapshot ?: throw CoverageUnavailable()
        val period = filter.days?.let { Period(today.minusDays(it - 1L).toString(), today.toString()) }
        if (period != null && (period.from < data.from || period.to > data.to)) throw CoverageUnavailable()
        if (filter.stockCode != null && filter.stockCode !in knownCodes) throw CoverageUnavailable()
        val rows = data.items.filter {
            val issuer = data.issuers[it.stockCode]
            val matchesStock = filter.stockCode == null || filter.stockCode == it.stockCode ||
                (issuer != null && issuer == data.issuers[filter.stockCode])
            matchesStock && (period == null || it.filedOn in period.from..period.to)
        }
        val counts = rows.groupingBy { it.direction }.eachCount()
        return Selection(period, Counts(rows.size, counts["INCREASE"] ?: 0, counts["DECREASE"] ?: 0,
            counts["UNCHANGED"] ?: 0, counts["UNKNOWN"] ?: 0),
            if (filter.direction == "ALL") rows else rows.filter { it.direction == filter.direction })
    }
}
