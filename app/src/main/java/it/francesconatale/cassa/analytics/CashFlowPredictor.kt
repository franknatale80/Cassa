package it.francesconatale.cassa.analytics

import it.francesconatale.cassa.data.AccountEntity
import it.francesconatale.cassa.data.TransactionEntity
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

enum class CashFlowHealth {
    HEALTHY,   // Risparmio previsto e liquidità solida
    WARNING,   // Disavanzo mensile previsto o ritmo di spesa aumentato >20%
    CRITICAL   // Rischio saldo negativo prima di fine mese
}

data class CashFlowProjection(
    val targetMonth: YearMonth,
    val isCurrentMonth: Boolean,
    val isPastMonth: Boolean,
    val isFutureMonth: Boolean,
    val daysInMonth: Int,
    val daysElapsed: Int,
    val daysRemaining: Int,
    val actualIncomeCents: Long,
    val actualExpensesCents: Long,
    val currentTotalBalanceCents: Long,
    val dailyBurnRateCents: Long,
    val prevMonthDailyBurnRateCents: Long?,
    val burnRateDeltaPercent: Int?,
    val projectedRemainingExpenseCents: Long,
    val projectedTotalExpenseCents: Long,
    val projectedEndOfMonthBalanceCents: Long,
    val projectedNetSavingsCents: Long,
    val runwayDays: Int?,
    val runwayMonths: Double?,
    val health: CashFlowHealth
)

object CashFlowPredictor {

    fun calculateAccountBalance(account: AccountEntity, transactions: List<TransactionEntity>): Long =
        account.openingBalanceCents + transactions.sumOf {
            when {
                it.transactionType == "income" && it.accountId == account.id -> it.amountCents
                it.transactionType == "expense" && it.accountId == account.id -> -it.amountCents
                it.transactionType == "transfer" && it.accountId == account.id -> -it.amountCents
                it.transactionType == "transfer" && it.destinationAccountId == account.id -> it.amountCents
                else -> 0L
            }
        }

    fun calculateTotalBalance(accounts: List<AccountEntity>, transactions: List<TransactionEntity>): Long =
        accounts.sumOf { calculateAccountBalance(it, transactions) }

    fun filterTransactionsByMonth(transactions: List<TransactionEntity>, month: YearMonth): List<TransactionEntity> =
        transactions.filter {
            runCatching {
                YearMonth.from(ZonedDateTime.parse(it.effectiveAt)) == month
            }.getOrDefault(false)
        }

    fun calculate(
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        targetMonth: YearMonth = YearMonth.now(),
        today: LocalDate = LocalDate.now()
    ): CashFlowProjection {
        val currentMonth = YearMonth.from(today)
        val isCurrentMonth = targetMonth == currentMonth
        val isPastMonth = targetMonth < currentMonth
        val isFutureMonth = targetMonth > currentMonth

        val daysInMonth = targetMonth.lengthOfMonth()
        val daysElapsed = when {
            isPastMonth -> daysInMonth
            isFutureMonth -> 0
            else -> today.dayOfMonth.coerceIn(1, daysInMonth)
        }
        val daysRemaining = (daysInMonth - daysElapsed).coerceAtLeast(0)

        val monthTxs = filterTransactionsByMonth(transactions, targetMonth)
        val actualIncomeCents = monthTxs.filter { it.transactionType == "income" }.sumOf { it.amountCents }
        val actualExpensesCents = monthTxs.filter { it.transactionType == "expense" }.sumOf { it.amountCents }
        val currentTotalBalanceCents = calculateTotalBalance(accounts, transactions)

        // Statistiche mese precedente per confronto ritmo di spesa
        val prevMonth = targetMonth.minusMonths(1)
        val prevMonthTxs = filterTransactionsByMonth(transactions, prevMonth)
        val prevExpenses = prevMonthTxs.filter { it.transactionType == "expense" }.sumOf { it.amountCents }
        val prevDays = prevMonth.lengthOfMonth()
        val prevMonthDailyBurnRateCents = if (prevDays > 0 && prevExpenses > 0) prevExpenses / prevDays else null

        val dailyBurnRateCents: Long
        val projectedRemainingExpenseCents: Long
        val projectedTotalExpenseCents: Long
        val projectedEndOfMonthBalanceCents: Long
        val projectedNetSavingsCents: Long

        when {
            isPastMonth -> {
                dailyBurnRateCents = if (daysInMonth > 0) actualExpensesCents / daysInMonth else 0L
                projectedRemainingExpenseCents = 0L
                projectedTotalExpenseCents = actualExpensesCents
                projectedEndOfMonthBalanceCents = currentTotalBalanceCents
                projectedNetSavingsCents = actualIncomeCents - actualExpensesCents
            }
            isFutureMonth -> {
                dailyBurnRateCents = prevMonthDailyBurnRateCents ?: 0L
                projectedRemainingExpenseCents = dailyBurnRateCents * daysInMonth
                projectedTotalExpenseCents = projectedRemainingExpenseCents
                projectedEndOfMonthBalanceCents = currentTotalBalanceCents - projectedRemainingExpenseCents
                projectedNetSavingsCents = actualIncomeCents - projectedTotalExpenseCents
            }
            else -> {
                // Mese corrente
                dailyBurnRateCents = if (daysElapsed > 0) actualExpensesCents / daysElapsed else 0L
                projectedRemainingExpenseCents = dailyBurnRateCents * daysRemaining
                projectedTotalExpenseCents = actualExpensesCents + projectedRemainingExpenseCents
                projectedEndOfMonthBalanceCents = currentTotalBalanceCents - projectedRemainingExpenseCents
                projectedNetSavingsCents = actualIncomeCents - projectedTotalExpenseCents
            }
        }

        val burnRateDeltaPercent: Int? = if (prevMonthDailyBurnRateCents != null && prevMonthDailyBurnRateCents > 0 && dailyBurnRateCents > 0) {
            (((dailyBurnRateCents - prevMonthDailyBurnRateCents).toDouble() / prevMonthDailyBurnRateCents) * 100).toInt()
        } else null

        val runwayDays: Int?
        val runwayMonths: Double?
        if (isCurrentMonth && dailyBurnRateCents > 0 && currentTotalBalanceCents > 0) {
            val days = (currentTotalBalanceCents / dailyBurnRateCents).toInt()
            runwayDays = days
            runwayMonths = days / 30.4
        } else {
            runwayDays = null
            runwayMonths = null
        }

        val health = when {
            projectedEndOfMonthBalanceCents < 0 -> CashFlowHealth.CRITICAL
            projectedNetSavingsCents < 0 || (burnRateDeltaPercent != null && burnRateDeltaPercent > 20) -> CashFlowHealth.WARNING
            else -> CashFlowHealth.HEALTHY
        }

        return CashFlowProjection(
            targetMonth = targetMonth,
            isCurrentMonth = isCurrentMonth,
            isPastMonth = isPastMonth,
            isFutureMonth = isFutureMonth,
            daysInMonth = daysInMonth,
            daysElapsed = daysElapsed,
            daysRemaining = daysRemaining,
            actualIncomeCents = actualIncomeCents,
            actualExpensesCents = actualExpensesCents,
            currentTotalBalanceCents = currentTotalBalanceCents,
            dailyBurnRateCents = dailyBurnRateCents,
            prevMonthDailyBurnRateCents = prevMonthDailyBurnRateCents,
            burnRateDeltaPercent = burnRateDeltaPercent,
            projectedRemainingExpenseCents = projectedRemainingExpenseCents,
            projectedTotalExpenseCents = projectedTotalExpenseCents,
            projectedEndOfMonthBalanceCents = projectedEndOfMonthBalanceCents,
            projectedNetSavingsCents = projectedNetSavingsCents,
            runwayDays = runwayDays,
            runwayMonths = runwayMonths,
            health = health
        )
    }
}
