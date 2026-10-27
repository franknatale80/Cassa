package it.francesconatale.cassa.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.time.Instant

object BackupManager {

    fun exportToJson(
        context: Context,
        uri: Uri,
        accounts: List<AccountEntity>,
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>
    ): Result<Int> = runCatching {
        val root = JSONObject().apply {
            put("version", 1)
            put("exported_at", Instant.now().toString())
            put("app", "Cassa Android Standalone")

            val accountsArray = JSONArray()
            accounts.forEach { a ->
                accountsArray.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name)
                    put("kind", a.kind)
                    put("opening_balance_cents", a.openingBalanceCents)
                    put("active", a.active)
                    put("revision", a.revision)
                })
            }
            put("accounts", accountsArray)

            val categoriesArray = JSONArray()
            categories.forEach { c ->
                categoriesArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("transaction_type", c.transactionType)
                    put("sort_order", c.sortOrder)
                    put("active", c.active)
                    put("revision", c.revision)
                })
            }
            put("categories", categoriesArray)

            val transactionsArray = JSONArray()
            transactions.forEach { t ->
                transactionsArray.put(JSONObject().apply {
                    put("id", t.id)
                    put("transaction_type", t.transactionType)
                    put("amount_cents", t.amountCents)
                    put("account_id", t.accountId)
                    put("destination_account_id", t.destinationAccountId ?: JSONObject.NULL)
                    put("category_id", t.categoryId ?: JSONObject.NULL)
                    put("effective_at", t.effectiveAt)
                    put("note", t.note ?: JSONObject.NULL)
                    put("local_receipt_path", t.localReceiptPath ?: JSONObject.NULL)
                    put("created_at", t.createdAt)
                    put("updated_at", t.updatedAt)
                    put("deleted_at", t.deletedAt ?: JSONObject.NULL)
                })
            }
            put("transactions", transactionsArray)
        }

        context.contentResolver.openOutputStream(uri)?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                writer.write(root.toString(2))
            }
        } ?: error("Impossibile aprire il file di destinazione")

        transactions.size
    }

    suspend fun importFromJson(
        context: Context,
        uri: Uri,
        dao: CassaDao
    ): Result<Int> = runCatching {
        val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } ?: error("Impossibile leggere il file selezionato")

        val root = JSONObject(jsonString)

        val accountsArray = root.optJSONArray("accounts")
        if (accountsArray != null) {
            for (i in 0 until accountsArray.length()) {
                val a = accountsArray.getJSONObject(i)
                dao.upsertAccount(
                    AccountEntity(
                        id = a.getString("id"),
                        name = a.getString("name"),
                        kind = a.optString("kind", "bank"),
                        openingBalanceCents = a.getLong("opening_balance_cents"),
                        active = a.optBoolean("active", true),
                        revision = a.optLong("revision", 0L)
                    )
                )
            }
        }

        val categoriesArray = root.optJSONArray("categories")
        if (categoriesArray != null) {
            for (i in 0 until categoriesArray.length()) {
                val c = categoriesArray.getJSONObject(i)
                dao.upsertCategory(
                    CategoryEntity(
                        id = c.getString("id"),
                        name = c.getString("name"),
                        transactionType = c.optString("transaction_type", "expense"),
                        sortOrder = c.optInt("sort_order", 0),
                        active = c.optBoolean("active", true),
                        revision = c.optLong("revision", 0L)
                    )
                )
            }
        }

        var importedTxCount = 0
        val txArray = root.optJSONArray("transactions")
        if (txArray != null) {
            for (i in 0 until txArray.length()) {
                val t = txArray.getJSONObject(i)
                val destAcc = if (t.isNull("destination_account_id")) null else t.getString("destination_account_id")
                val catId = if (t.isNull("category_id")) null else t.getString("category_id")
                val note = if (t.isNull("note")) null else t.getString("note")
                val localReceipt = if (t.isNull("local_receipt_path")) null else t.getString("local_receipt_path")
                val deletedAt = if (t.isNull("deleted_at")) null else t.getString("deleted_at")

                dao.upsertTransaction(
                    TransactionEntity(
                        id = t.getString("id"),
                        transactionType = t.getString("transaction_type"),
                        amountCents = t.getLong("amount_cents"),
                        accountId = t.getString("account_id"),
                        destinationAccountId = destAcc,
                        categoryId = catId,
                        paymentMethodId = null,
                        effectiveAt = t.getString("effective_at"),
                        note = note,
                        receiptPaperlessId = null,
                        receiptUrl = null,
                        localReceiptPath = localReceipt,
                        revision = 0,
                        createdAt = t.optString("created_at", Instant.now().toString()),
                        updatedAt = t.optString("updated_at", Instant.now().toString()),
                        deletedAt = deletedAt
                    )
                )
                importedTxCount++
            }
        }

        importedTxCount
    }
}
