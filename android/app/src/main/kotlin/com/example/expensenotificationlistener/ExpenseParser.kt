package com.example.expensenotificationlistener

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedExpense(
    val timestamp: String,
    val merchant: String,
    val amount: Long,
    val source: String,
    val raw: String,
)

object ExpenseParser {
    private val expenseTerms = listOf(
        "pembayaran", "pembelian", "transaksi debit", "anda membayar",
        "telah dibayar", "payment", "purchase", "paid", "debit",
        "spent", "qris",
    )
    private val rejectTerms = listOf(
        "refund", "pengembalian", "dikembalikan", "uang masuk", "diterima",
        "credit", "credited", "deposit", "incoming", "gagal", "failed",
        "dibatalkan", "cancelled", "canceled",
    )
    private val amountPattern = Regex(
        """(?i)(?:rp\.?\s*|idr\s*)?([0-9][0-9.,]{0,18})\s*(?:rupiah)?""",
    )
    private val amountCue = Regex(
        """(?i)(?:sebesar|senilai|amount|total|paid|payment|pembayaran|pembelian|debit|qris)\s*(?:[:=]\s*)?(?:rp\.?\s*|idr\s*)?([0-9][0-9.,]{0,18})""",
    )
    private val currencyAmounts = Regex(
        """(?i)(?:rp\.?\s*|idr\s*)([0-9][0-9.,]{0,18})""",
    )

    fun isExpenseCandidate(title: String, body: String): Boolean {
        val text = listOf(title, body)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .lowercase(Locale.ROOT)
        return expenseTerms.any(text::contains) &&
            rejectTerms.none(text::contains)
    }

    fun parse(
        title: String,
        body: String,
        source: String,
        notificationTimeMillis: Long,
        rawConsent: Boolean,
    ): ParsedExpense? {
        val text = listOf(title, body)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        if (text.isBlank()) return null
        if (!isExpenseCandidate(title, body)) return null

        val cueMatch = amountCue.find(text)
        val candidate = cueMatch?.groupValues?.get(1) ?: run {
            val explicitAmounts = currencyAmounts.findAll(text).toList()
            if (explicitAmounts.size != 1) return null
            explicitAmounts.single().groupValues[1]
        }
        val amount = parseAmount(candidate) ?: return null
        if (amount <= 0L || amount > MAX_AMOUNT_IDR) return null

        val cleanedTitle = title.trim().take(MAX_MERCHANT_LENGTH)
        val merchant = if (cleanedTitle.isNotEmpty() &&
            amountPattern.containsMatchIn(cleanedTitle).not()
        ) cleanedTitle else "Unknown merchant"
        val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(notificationTimeMillis)
                .atZone(ZoneId.systemDefault())
                .toOffsetDateTime(),
        )
        return ParsedExpense(
            timestamp = timestamp,
            merchant = merchant,
            amount = amount,
            source = source.take(MAX_SOURCE_LENGTH),
            raw = if (rawConsent) text.take(MAX_RAW_LENGTH) else "",
        )
    }

    private fun parseAmount(value: String): Long? {
        var normalized = value.replace(" ", "")
        if (normalized.contains('.') && normalized.contains(',')) {
            normalized = if (normalized.lastIndexOf(',') > normalized.lastIndexOf('.')) {
                normalized.replace(".", "").replace(",", ".")
            } else {
                normalized.replace(",", "")
            }
        } else if (normalized.contains(',') || normalized.contains('.')) {
            val separator = if (normalized.contains(',')) ',' else '.'
            val parts = normalized.split(separator)
            normalized = if (parts.size == 2 && parts[1].length == 2) {
                parts.joinToString("")
            } else {
                parts.joinToString("")
            }
        }
        return try {
            BigDecimal(normalized).longValueExact()
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private const val MAX_AMOUNT_IDR = 1_000_000_000_000L
    private const val MAX_RAW_LENGTH = 3_500
    private const val MAX_MERCHANT_LENGTH = 120
    private const val MAX_SOURCE_LENGTH = 100
}
