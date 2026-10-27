package it.francesconatale.cassa.notification

import java.io.Serializable

data class ParsedBankNotification(
    val amountCents: Long,
    val merchantOrTitle: String,
    val transactionType: String = "expense", // "expense" or "income"
    val suggestedCategory: String,
    val packageName: String,
    val rawText: String
) : Serializable
