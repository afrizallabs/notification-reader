package com.example.expensenotificationlistener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpenseParserTest {
    @Test
    fun parsesIndonesianRupiahAndOmitsRawByDefault() {
        val parsed = ExpenseParser.parse(
            title = "STARBUCKS",
            body = "Pembayaran QRIS sebesar Rp125.000",
            source = "Bank Example",
            notificationTimeMillis = 1_759_660_200_000L,
            rawConsent = false,
        )

        assertEquals(125_000L, parsed?.amount)
        assertEquals("STARBUCKS", parsed?.merchant)
        assertEquals("", parsed?.raw)
    }

    @Test
    fun rejectsIncomeAndFailedTransactions() {
        assertNull(
            ExpenseParser.parse(
                title = "Dana masuk",
                body = "Transfer diterima Rp125.000",
                source = "Bank Example",
                notificationTimeMillis = 1_759_660_200_000L,
                rawConsent = true,
            ),
        )
        assertNull(
            ExpenseParser.parse(
                title = "Pembayaran gagal",
                body = "Pembayaran Rp125.000 gagal",
                source = "Bank Example",
                notificationTimeMillis = 1_759_660_200_000L,
                rawConsent = true,
            ),
        )
    }

    @Test
    fun rejectsExpenseKeywordWithUnlabeledAmbiguousNumbers() {
        assertNull(
            ExpenseParser.parse(
                title = "Pembayaran merchant",
                body = "Nomor referensi 12345 dan saldo 67890",
                source = "Bank Example",
                notificationTimeMillis = 1_759_660_200_000L,
                rawConsent = true,
            ),
        )
    }

    @Test
    fun includesRawTextOnlyAfterConsent() {
        val parsed = ExpenseParser.parse(
            title = "Merchant",
            body = "Pembayaran Rp25.000",
            source = "Bank Example",
            notificationTimeMillis = 1_759_660_200_000L,
            rawConsent = true,
        )
        assertEquals("Merchant\nPembayaran Rp25.000", parsed?.raw)
    }

    @Test
    fun parsesIndonesianThousandsAndDecimalSeparators() {
        val parsed = ExpenseParser.parse(
            title = "Merchant",
            body = "Pembayaran sebesar Rp1.250.000,00",
            source = "Bank Example",
            notificationTimeMillis = 1_759_660_200_000L,
            rawConsent = false,
        )
        assertEquals(1_250_000L, parsed?.amount)
    }
}
