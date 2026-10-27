package it.francesconatale.cassa.data

import org.json.JSONObject

class TransactionJsonEncoder : TransactionJson {
    override fun encode(transaction: TransactionEntity): String = JSONObject().apply {
        put("id", transaction.id)
        put("transaction_type", transaction.transactionType)
        put("amount_cents", transaction.amountCents)
        put("account_id", transaction.accountId)
        put("destination_account_id", transaction.destinationAccountId ?: JSONObject.NULL)
        put("category_id", transaction.categoryId ?: JSONObject.NULL)
        put("payment_method_id", transaction.paymentMethodId ?: JSONObject.NULL)
        put("effective_at", transaction.effectiveAt)
        put("note", transaction.note ?: JSONObject.NULL)
        put("receipt_paperless_id", transaction.receiptPaperlessId ?: JSONObject.NULL)
        put("receipt_url", transaction.receiptUrl ?: JSONObject.NULL)
        put("revision", transaction.revision)
        put("created_at", transaction.createdAt)
        put("updated_at", transaction.updatedAt)
        put("deleted_at", transaction.deletedAt ?: JSONObject.NULL)
    }.toString()
}
