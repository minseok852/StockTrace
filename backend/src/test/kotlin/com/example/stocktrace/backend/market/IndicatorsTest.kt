package com.example.stocktrace.backend.market

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class IndicatorsTest {
    private val monday = LocalDate.parse("2026-11-23")

    @Test fun selectsLatestExactCompositeWithoutFillingMissingChange() {
        val json = """{"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
            {"idxNm":"코스피 200","basDt":"20261123","clpr":"400"},
            {"idxNm":"코스피","basDt":"20261120","clpr":"2450"},
            {"idxNm":"코스피","basDt":"20261123","clpr":"2500","fltRt":"0.80"},
            {"idxNm":"코스닥","basDt":"20261123","clpr":"800","vs":"-2","fltRt":"-0.25"}
        ]}}}}"""
        val kospi = indexObservation(json, "코스피", monday)!!
        assertEquals(monday, kospi.date)
        assertEquals("2500", kospi.value.toPlainString())
        assertEquals("0.80", kospi.changePercent.toString())
        assertNull(kospi.changeValue)
        assertEquals("-0.25", indexObservation(json, "코스닥", monday)!!.changePercent.toString())
    }

    @Test fun usesPreviousPublicationAcrossHoliday() {
        val rates = mapOf(monday to "1,392.00", monday.minusDays(3) to "1,390.50")
        val fx = usdRate(monday) { day -> rates[day]?.let { """[{"result":1,"cur_unit":"USD","deal_bas_r":"$it"}]""" } ?: "[]" }!!
        assertEquals("1392.00", fx.value.toPlainString())
        assertEquals("1.50", fx.changeValue.toString())
        assertEquals(monday, fx.date)
    }

    @Test fun previousMissingOrFailedDoesNotInventAChange() {
        val row = """[{"result":1,"cur_unit":"USD","deal_bas_r":"1,392.00"}]"""
        val fx = usdRate(monday) { if (it == monday) row else error("lookup failed") }!!
        assertNull(fx.changeValue)
        assertEquals(monday, fx.date)
    }

    @Test fun sharesSnapshotAndPreservesOnlyFailedRows() {
        var now = Instant.parse("2026-11-23T07:00:00Z")
        var calls = 0
        var fail = false
        val service = MarketIndicators({ symbol, day ->
            calls++
            when {
                symbol == Symbol.KOSDAQ -> null
                fail && symbol == Symbol.USD_KRW -> error("offline")
                else -> Observation(day, "123.45".toBigDecimal())
            }
        }, { now })
        val first = service.items()
        assertEquals(listOf("KOSPI","KOSDAQ","USD_KRW"), first.map { it.symbol })
        assertEquals(listOf("POINT","POINT","KRW_PER_USD"), first.map { it.unit })
        assertEquals(listOf("AVAILABLE","UNAVAILABLE","AVAILABLE"), first.map { it.status })
        assertNull(first[1].value)
        assertEquals(first, service.items())
        assertEquals(3, calls)
        now = now.plusSeconds(86400)
        fail = true
        val next = service.items()
        assertEquals("2026-11-24", next.first().referenceDate)
        assertEquals(first.last().copy(status = "DELAYED"), next.last())
    }
}
