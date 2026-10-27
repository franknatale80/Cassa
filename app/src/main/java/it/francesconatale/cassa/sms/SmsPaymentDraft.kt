package it.francesconatale.cassa.sms

import java.io.Serializable

data class SmsPaymentDraft(
    val amountCents: Long,
    val merchant: String,
    val suggestedCategory: String,
    val rawMessage: String,
    val transactionType: String = "expense"
) : Serializable
