package com.example.stocktrace.backend.insiders

import com.example.stocktrace.backend.disclosures.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class TradeRow(
    val receiptNo: String, val rowNo: Int, val filedOn: String, val stockCode: String?,
    val companyName: String, val reporterName: String?, val position: String?,
    val reason: String, val tradeType: String, val changedOn: String?, val deltaShares: Long?,
    val unitPriceKrw: BigDecimal?, val amountKrw: BigDecimal?,
    @Transient val executiveKey: String?,
)

private fun rows(item: DisclosureItem, detail: DisclosureDetail): List<TradeRow> =
    (detail.transactions ?: throw CoverageUnavailable()).map { tx ->
        val type = when (tx.reason) {
            "장내매수" -> "BUY"
            "장내매도" -> "SELL"
            else -> "OTHER"
        }
        // D002 identifies the issuer's common stock. Other classes require a verified listing match.
        val code = item.stockCode.takeIf { tx.securityType == "보통주" && it == detail.stockCode }
        val amount = tx.unitPriceKrw?.takeIf { it.signum() > 0 }?.let { price ->
            tx.deltaShares?.toBigDecimal()?.abs()?.multiply(price)
        }
        TradeRow(item.receiptNo, tx.rowNo, item.filedOn, code, item.companyName, detail.reporterName,
            detail.position, tx.reason, type, tx.changedOn, tx.deltaShares, tx.unitPriceKrw, amount,
            detail.executiveKey.takeIf { code != null && type != "OTHER" && amount != null })
    }

private data class Query(val ranking: Boolean, val days: Int, val type: String, val code: String?,
    val executives: Boolean, val to: LocalDate?, val limit: Int)

private fun query(ranking: Boolean, params: Map<String,String>, today: LocalDate): Query {
    val allowed = if (ranking) setOf("side","limit","to","cursor")
        else setOf("days","tradeType","stockCode","scope","limit","to","cursor")
    require(params.keys.all { it in allowed })
    val days = params["days"] ?: "30"
    require(days in setOf("7","30","90"))
    val type = if (ranking) params["side"] ?: "BUY" else params["tradeType"] ?: "ALL"
    require(type in setOf("BUY","SELL") || (!ranking && type == "ALL"))
    val scope = params["scope"] ?: "ALL_REPORTERS"
    require(scope in setOf("ALL_REPORTERS","RANKED_EXECUTIVES"))
    val code = params["stockCode"]?.also { require(it.matches(stockCodePattern)) }
    val executives = scope == "RANKED_EXECUTIVES" || ranking
    require(scope != "RANKED_EXECUTIVES" || (code != null && days == "30" && type != "ALL"))
    val to = params["to"]?.let {
        require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        try { LocalDate.parse(it) } catch (e: DateTimeParseException) { throw IllegalArgumentException(e) }
    }
    require(to == null || to <= today)
    val limit = (params["limit"] ?: "20").toIntOrNull()
    require(limit != null && limit in 1..50)
    return Query(ranking, days.toInt(), type, code, executives, to, limit)
}

private data class Selection(val period: Period, val items: List<Any>, val asOf: String?) {
    val tokens = mutableMapOf<Int,String>()
}
private data class Cursor(val query: Query, val selection: Selection, val offset: Int)

private fun <K,V> cache(capacity: Int) = object : LinkedHashMap<K,V>(16,0.75f,true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K,V>?) = size > capacity
}

// One instance per published disclosure/catalog snapshot. The injected lookup reads that same catalog version.
internal class InsiderTrades(private val snapshot: DisclosureSnapshot?, private val repository: DartArchiveRepository,
    private val stockSummary: ((String) -> Any?)? = null) {
    private val documents = ConcurrentHashMap<String,List<TradeRow>>()
    private val selections = cache<Pair<Query,LocalDate>,Lazy<Selection>>(64)
    private val cursors = cache<String,Cursor>(512)

    fun page(ranking: Boolean, params: Map<String,String>, today: LocalDate): Map<String,Any?> {
        val filter = query(ranking, params, today)
        val continuation = params["cursor"]?.let {
            require(UUID.fromString(it).toString() == it)
            val saved = synchronized(cursors) { cursors[it] } ?: throw CursorExpired()
            require(saved.query == filter)
            saved
        }
        val to = filter.to ?: today
        val selection = continuation?.selection ?: synchronized(selections) {
            selections.getOrPut(filter to to) { lazy { select(filter,to) } }
        }.value
        val offset = continuation?.offset ?: 0
        val end = minOf(offset + filter.limit, selection.items.size)
        val next = if (end == selection.items.size) null else synchronized(cursors) {
            selection.tokens.getOrPut(end) { UUID.randomUUID().toString() }.also {
                cursors[it] = Cursor(filter,selection,end)
            }
        }
        return buildMap {
            put("period",selection.period)
            put("items",selection.items.subList(offset,end))
            put("nextCursor",next)
            if (ranking) put("asOf",selection.asOf)
        }
    }

    private fun select(filter: Query, to: LocalDate): Selection {
        if (filter.ranking && stockSummary == null) throw CoverageUnavailable()
        val data = snapshot ?: throw CoverageUnavailable()
        val period = Period(to.minusDays(filter.days - 1L).toString(),to.toString())
        if (period.from < data.from || period.to > data.to) throw CoverageUnavailable()
        val selected = data.items.asSequence()
            .filter { it.filedOn in period.from..period.to && (filter.code == null || it.stockCode == filter.code) }
            .sortedWith(compareByDescending<DisclosureItem> { it.filedOn }.thenByDescending { it.receiptNo })
            .flatMap { item -> documents.computeIfAbsent(item.receiptNo) {
                val detail = try { repository.find(item.receiptNo) } catch (_: java.io.IOException) { null }
                    ?: throw CoverageUnavailable()
                rows(item,detail)
            } }
            .filter { (filter.code == null || it.stockCode == filter.code) &&
                (filter.type == "ALL" || it.tradeType == filter.type) && (!filter.executives || it.executiveKey != null) }
            .toList()
        return if (filter.ranking) Selection(period,rank(selected),Instant.now().toString())
            else Selection(period,selected,null)
    }

    private data class Total(val code: String, val amount: BigDecimal, val people: Int)
    private fun rank(selected: List<TradeRow>): List<Any> =
        selected.groupBy { it.stockCode!! }.map { (code, trades) ->
            Total(code,trades.sumOf { it.amountKrw!! },trades.map { it.executiveKey }.distinct().size)
        }.sortedWith(compareByDescending<Total> { it.amount }.thenBy { it.code }).mapIndexed { index, total ->
            mapOf("rank" to index + 1, "stock" to (stockSummary?.invoke(total.code) ?: throw CoverageUnavailable()),
                "amountKrw" to total.amount, "executiveCount" to total.people)
        }
}
