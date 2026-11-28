package com.example.stocktrace.backend.disclosures

import java.math.BigDecimal

data class DisclosureDetail(
    val receiptNo: String,
    val filedOn: String,
    val reportAsOf: String?,
    val stockCode: String,
    val companyName: String,
    val reporterName: String?,
    val relationship: String?,
    val position: String?,
    val ownershipPercent: BigDecimal?,
    val isCorrection: Boolean,
    val holdings: Holdings,
    val transactions: List<Transaction>?,
    @Transient internal val executiveKey: String? = null,
) {
    val sourceUrl = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=$receiptNo"
    // The local DART archives do not include the correction chain or daily quotes.
    val latestReceiptNo: String? = null
    val comparisonQuote: Nothing? = null
}

data class Holdings(
    val previousShares: Long?,
    val currentShares: Long?,
    val deltaShares: Long?,
    val changePercent: BigDecimal?,
)

data class Transaction(
    val rowNo: Int,
    val changedOn: String?,
    val securityType: String,
    val reason: String,
    val deltaShares: Long?,
    val unitPriceKrw: BigDecimal?,
    val priceComparison: PriceComparison,
)

data class PriceComparison(
    val status: String,
    val differenceKrw: BigDecimal? = null,
    val differencePercent: BigDecimal? = null,
)
