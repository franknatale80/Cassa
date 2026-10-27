package it.francesconatale.cassa.analytics

import it.francesconatale.cassa.data.AccountEntity
import it.francesconatale.cassa.data.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CashFlowPredictorTest {

    private val account = AccountEntity(
        id = "acc-1",
        name = "Conto Corrente",
        kind = "bank",
        openingBalanceCents = 200_000L, // 2.000 € iniziale
        active = true,
        revision = 1L
    )

    private fun createTx(
        id: String,
        type: String,
        cents: Long,
        effectiveAt: String
    ) = TransactionEntity(
        id = id,
        transactionType = type,
        amountCents = cents,
        accountId = "acc-1",
        effectiveAt = effectiveAt,
        createdAt = effectiveAt,
        updatedAt = effectiveAt
    )

    @Test
    fun testMidMonthProjectionWithHealthySavings() {
        val today = LocalDate.of(2026, 10, 20) // Giorno 20 di 31 (11 giorni rimanenti)
        val targetMonth = YearMonth.of(2026, 10)

        // Entrata di 1.500 € a inizio mese
        val income = createTx("tx-inc", "income", 150_000L, "2026-10-01T10:00:00Z")
        // Spese per 600 € totali nei primi 20 giorni -> 30 € / giorno
        val expenses = listOf(
            createTx("tx-exp-1", "expense", 30_000L, "2026-10-05T12:00:00Z"),
            createTx("tx-exp-2", "expense", 30_000L, "2026-10-15T12:00:00Z")
        )

        val txs = listOf(income) + expenses
        val projection = CashFlowPredictor.calculate(listOf(account), txs, targetMonth, today)

        assertEquals(31, projection.daysInMonth)
        assertEquals(20, projection.daysElapsed)
        assertEquals(11, projection.daysRemaining)

        // Spese effettive: 600 €
        assertEquals(60_000L, projection.actualExpensesCents)
        // Entrate effettive: 1.500 €
        assertEquals(150_000L, projection.actualIncomeCents)

        // Burn rate giornaliero: 600 € / 20 gg = 30 € / giorno (3.000 centesimi)
        assertEquals(3_000L, projection.dailyBurnRateCents)

        // Spesa rimanente stimata: 30 € * 11 gg = 330 € (33.000 centesimi)
        assertEquals(33_000L, projection.projectedRemainingExpenseCents)

        // Spesa totale stimata per il mese: 600 € + 330 € = 930 € (93.000 centesimi)
        assertEquals(93_000L, projection.projectedTotalExpenseCents)

        // Saldo attuale: 2.000 € (iniziale) + 1.500 € (entrata) - 600 € (spese) = 2.900 €
        assertEquals(290_000L, projection.currentTotalBalanceCents)

        // Saldo stimato a fine mese: 2.900 € - 330 € = 2.570 € (257.000 centesimi)
        assertEquals(257_000L, projection.projectedEndOfMonthBalanceCents)

        // Risparmio netto stimato: 1.500 € (entrate) - 930 € (spese tot) = +570 € (57.000 centesimi)
        assertEquals(57_000L, projection.projectedNetSavingsCents)

        assertEquals(CashFlowHealth.HEALTHY, projection.health)
        assertNotNull(projection.runwayDays)
        assertTrue(projection.runwayDays!! > 90) // Oltre 3 mesi di autonomia
    }

    @Test
    fun testWarningWhenExpensesExceedIncome() {
        val today = LocalDate.of(2026, 10, 10) // Giorno 10 di 31 (21 gg rimanenti)
        val targetMonth = YearMonth.of(2026, 10)

        // Entrata di 500 €
        val income = createTx("tx-inc", "income", 50_000L, "2026-10-01T10:00:00Z")
        // Spese già a 300 € in 10 giorni -> 30 € / giorno
        // Rimanenti 21 giorni * 30 € = 630 €
        // Totale spese stimate = 930 € > Entrate 500 € -> Disavanzo di -430 €
        val expense = createTx("tx-exp", "expense", 30_000L, "2026-10-05T12:00:00Z")

        val projection = CashFlowPredictor.calculate(listOf(account), listOf(income, expense), targetMonth, today)

        assertEquals(CashFlowHealth.WARNING, projection.health)
        assertTrue(projection.projectedNetSavingsCents < 0)
        // Ma il saldo a fine mese è ancora positivo perché c'era liquidità di partenza (2.000 €)
        assertTrue(projection.projectedEndOfMonthBalanceCents > 0)
    }

    @Test
    fun testCriticalWhenBalanceRunsNegative() {
        val lowAccount = account.copy(openingBalanceCents = 10_000L) // Solo 100 € di saldo iniziale
        val today = LocalDate.of(2026, 10, 10)
        val targetMonth = YearMonth.of(2026, 10)

        // Spesa di 80 € nei primi 10 giorni -> 8 € / giorno
        // Nei restanti 21 giorni spenderà 168 € -> saldo finale andrà sottozero!
        val expense = createTx("tx-exp", "expense", 8_000L, "2026-10-05T12:00:00Z")

        val projection = CashFlowPredictor.calculate(listOf(lowAccount), listOf(expense), targetMonth, today)

        assertEquals(CashFlowHealth.CRITICAL, projection.health)
        assertTrue(projection.projectedEndOfMonthBalanceCents < 0)
    }

    @Test
    fun testPastMonthHasZeroRemaining() {
        val today = LocalDate.of(2026, 10, 22)
        val pastMonth = YearMonth.of(2026, 9) // Settembre (30 giorni)

        val expense = createTx("tx-exp", "expense", 90_000L, "2026-09-15T12:00:00Z")

        val projection = CashFlowPredictor.calculate(listOf(account), listOf(expense), pastMonth, today)

        assertTrue(projection.isPastMonth)
        assertEquals(0, projection.daysRemaining)
        assertEquals(30, projection.daysElapsed)
        assertEquals(0L, projection.projectedRemainingExpenseCents)
        assertEquals(90_000L, projection.projectedTotalExpenseCents)
        assertNull(projection.runwayDays)
    }
}
