package com.example.stocktrace.backend.market

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class Symbol(val unit: String, val source: String, val indexName: String? = null) {
    KOSPI("POINT", "금융위원회", "코스피"),
    KOSDAQ("POINT", "금융위원회", "코스닥"),
    USD_KRW("KRW_PER_USD", "한국수출입은행")
}

internal data class Observation(
    val date: LocalDate,
    val value: BigDecimal,
    val changeValue: BigDecimal? = null,
    val changePercent: BigDecimal? = null
)

internal data class Indicator(
    val symbol: String, val unit: String, val source: String, val status: String,
    val value: BigDecimal?, val changeValue: BigDecimal?, val changePercent: BigDecimal?,
    val referenceDate: String?, val fetchedAt: String?, val asOf: String? = null
)

internal class MarketIndicators(
    private val read: (Symbol, LocalDate) -> Observation?,
    private val clock: () -> Instant = Instant::now
) {
    private var refreshAfter = Instant.MIN
    private var snapshot = emptyList<Indicator>()

    @Synchronized fun items(): List<Indicator> {
        val now = clock()
        if (now < refreshAfter) return snapshot
        val day = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val previous = snapshot.associateBy { it.symbol }
        snapshot = Symbol.entries.map { symbol ->
            val old = previous[symbol.name]
            val observation = try {
                read(symbol, day)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            } catch (_: Exception) {
                null
            }
            when {
                observation != null && (old?.referenceDate == null || observation.date.toString() >= old.referenceDate) ->
                    Indicator(symbol.name, symbol.unit, symbol.source, "AVAILABLE", observation.value,
                        observation.changeValue, observation.changePercent, observation.date.toString(), clock().toString())
                old?.value != null -> old.copy(status = "DELAYED")
                else -> Indicator(symbol.name, symbol.unit, symbol.source, "UNAVAILABLE", null, null, null, null, null)
            }
        }
        val interval = if (snapshot.all { it.status == "AVAILABLE" }) Duration.ofHours(1) else Duration.ofMinutes(5)
        refreshAfter = clock().plus(interval)
        return snapshot
    }
}
