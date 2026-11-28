package com.example.stocktrace.backend.assembly

internal val parties = linkedMapOf(
    "democratic" to "더불어민주당", "people-power" to "국민의힘", "rebuilding" to "조국혁신당",
    "reform" to "개혁신당", "progressive" to "진보당", "other" to "그 외·무소속"
)
internal typealias Fields = Map<String, Any?>

// The owner key identifies the same disclosed person across annual reports.
internal data class Position(val code: String?, val name: String, val owner: String, val relationship: String)
internal data class Holding(val position: Position, val quantity: Long?)
internal data class Member(val id: String, val name: String, val party: String?, val holdings: List<Holding>?, val url: String? = null) {
    val status get() = if (holdings == null) "UNLINKED" else "LINKED"
    fun fields(): Fields = mapOf("memberId" to id, "name" to name, "partyId" to party,
        "partyName" to parties[party], "reportStatus" to status)
    fun positions() = holdings.orEmpty().groupBy { it.position }.mapValues { (_, rows) ->
        if (rows.any { it.quantity == null }) null else rows.sumOf { it.quantity!! }
    }
    fun self(code: String) = positions().filterKeys { it.code == code && it.relationship == "SELF" }
        .values.filterNotNull().sum()
}

internal data class AnnualReport(val year: Int, val referenceDate: String, val mode: String, val members: List<Member>) {
    fun metadata(): Fields = mapOf("year" to year, "referenceDate" to referenceDate, "dataMode" to mode)
    fun roster(party: String?) = members.filter { party == null || it.party == party }

    fun coverage(party: String?, code: String? = null): String {
        val roster = roster(party)
        if (roster.isNotEmpty() && roster.none { it.holdings != null }) return "UNAVAILABLE"
        val incomplete = roster.any { member -> member.holdings == null || member.positions().any { (key, amount) ->
            key.relationship == "SELF" && (key.code == null || (amount == null && (code == null || key.code == code)))
        } }
        return if (incomplete || (party != null && members.any { it.party == null })) "PARTIAL" else "COMPLETE"
    }

    fun totals(party: String?, code: String): Fields {
        val amounts = roster(party).map { it.self(code) }.filter { it > 0 }
        val known = amounts.isNotEmpty() || coverage(party, code) == "COMPLETE"
        return mapOf("shares" to amounts.sum().takeIf { known }, "holderCount" to amounts.size.takeIf { known })
    }
}

internal class AssemblyReports(val years: List<AnnualReport>, val stocks: Map<String, String>) {
    fun previous(report: AnnualReport) = years.filter { it.year < report.year && it.mode == report.mode }.maxByOrNull { it.year }

    fun groups(report: AnnualReport) = parties.entries.mapIndexed { index, (id, name) ->
        val coverage = report.coverage(id)
        val stockCount = stocks.keys.count { code -> report.roster(id).any { it.self(code) > 0 } }
        mapOf("partyId" to id, "partyName" to name, "displayOrder" to index + 1,
            "memberCount" to report.roster(id).size, "stockCount" to stockCount.takeIf { it > 0 || coverage == "COMPLETE" },
            "coverage" to coverage)
    }

    fun stockRows(report: AnnualReport, party: String?): List<Fields> {
        val prior = previous(report)
        return stocks.mapNotNull { (code, name) ->
            val totals = report.totals(party, code)
            val shares = totals["shares"] as Long?
            if (shares == null || shares == 0L) return@mapNotNull null
            val before = prior?.totals(party, code)?.get("shares") as Long?
            val comparable = before != null && prior?.coverage(party, code) == "COMPLETE" && report.coverage(party, code) == "COMPLETE"
            totals + mapOf("stockCode" to code, "stockName" to name, "previousShares" to before,
                "deltaShares" to if (comparable) shares - before!! else null)
        }.sortedWith(compareByDescending<Fields> { it["holderCount"] as Int }.thenBy { it["stockCode"] as String })
    }

    fun stockParties(report: AnnualReport, code: String): Fields {
        val groups = parties.mapNotNull { (id, name) ->
            val totals = report.totals(id, code)
            if ((totals["holderCount"] as Int? ?: 0) == 0) null
            else totals + mapOf("partyId" to id, "partyName" to name)
        }
        val coverage = report.coverage(null, code)
        val missingParty = report.members.any { it.party == null && it.self(code) > 0 }
        return report.totals(null, code) + mapOf("stockCode" to code, "stockName" to stocks[code],
            "coverage" to if (missingParty) "PARTIAL" else coverage, "items" to groups)
    }

    fun detail(report: AnnualReport, member: Member): Fields {
        val prior = previous(report)
        val old = prior?.members?.find { it.id == member.id }?.positions().orEmpty()
        val now = member.positions()
        val items = if (member.holdings == null) emptyList() else (now.keys + old.keys).map { key ->
            val before = old[key]
            val current = now[key]
            val delta = if (before != null && current != null) current - before else null
            val status = when {
                delta == null -> "UNKNOWN"
                delta > 0 -> "INCREASE"
                delta < 0 -> "DECREASE"
                else -> "UNCHANGED"
            }
            mapOf("stockCode" to key.code, "stockName" to key.name, "relationship" to key.relationship,
                "previousShares" to before, "shares" to current, "deltaShares" to delta, "comparisonStatus" to status)
        }
        return member.fields() + mapOf("previousYear" to prior?.year, "sourceUrl" to member.url, "items" to items)
    }
}

internal val assemblyExamples: AssemblyReports by lazy {
    val samsung = Position("005930", "삼성전자", "self", "SELF")
    val lg = Position("066570", "LG전자", "self", "SELF")
    val spouse = samsung.copy(owner = "spouse-1", relationship = "SPOUSE")
    val first = Member("ex-1", "가예시", "democratic", listOf(Holding(samsung, 1000), Holding(lg, 320)))
    val second = Member("ex-2", "나예시", "people-power", listOf(Holding(samsung, 680)))
    val unlinked = Member("ex-3", "다예시", "other", null)
    AssemblyReports(listOf(
        AnnualReport(2026, "2025-12-31", "EXAMPLE", listOf(
            first.copy(holdings = listOf(Holding(samsung, 1200), Holding(samsung, 40), Holding(spouse, 80), Holding(lg, null))),
            second.copy(party = "reform"), unlinked)),
        AnnualReport(2025, "2024-12-31", "EXAMPLE", listOf(first, second, unlinked))
    ), mapOf("005930" to "삼성전자", "066570" to "LG전자"))
}
