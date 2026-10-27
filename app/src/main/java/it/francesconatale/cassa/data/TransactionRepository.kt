package it.francesconatale.cassa.data

import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

class TransactionRepository(
    private val database: CassaDatabase,
    private val json: TransactionJson,
) {
    suspend fun initializeDefaults() {
        database.withTransaction {
            val dao = database.cassaDao()
            if (dao.accountCount() == 0) {
                dao.upsertAccount(AccountEntity("00000000-0000-4000-8000-000000000001", "Conto corrente", "bank", 0, true, 0))
                dao.upsertAccount(AccountEntity("00000000-0000-4000-8000-000000000002", "Contanti", "cash", 0, true, 0))
            }
            if (dao.categoryCount() == 0) {
                listOf(
                    CategoryEntity("20000000-0000-4000-8000-000000000001", "Alimentari", "expense", 10, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000002", "Casa", "expense", 20, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000003", "Utenze", "expense", 30, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000004", "Salute", "expense", 40, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000005", "Auto e trasporti", "expense", 50, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000006", "Ristoranti e bar", "expense", 60, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000007", "Tempo libero", "expense", 70, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000008", "Abbigliamento", "expense", 80, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000009", "Altro", "expense", 90, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000010", "Stipendio", "income", 10, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000011", "Rimborsi", "income", 20, true, 0),
                    CategoryEntity("20000000-0000-4000-8000-000000000012", "Altre entrate", "income", 30, true, 0),
                ).forEach { category -> dao.upsertCategory(category) }
            }
        }
    }

    suspend fun saveOffline(transaction: TransactionEntity) {
        require(transaction.amountCents > 0) { "L'importo deve essere positivo" }
        validateShape(transaction)
        val now = Instant.now().toString()
        database.withTransaction {
            val current = database.cassaDao().transactionById(transaction.id)
            val updated = transaction.copy(
                revision = current?.revision ?: transaction.revision,
                createdAt = current?.createdAt ?: transaction.createdAt,
                updatedAt = now,
            )
            database.cassaDao().upsertTransaction(updated)
            database.cassaDao().enqueue(
                OutboxEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "transaction",
                    operation = "upsert",
                    entityId = transaction.id,
                    baseRevision = updated.revision,
                    payloadJson = json.encode(updated),
                    createdAt = now,
                )
            )
        }
    }

    suspend fun deleteOffline(transaction: TransactionEntity) {
        val now = Instant.now().toString()
        database.withTransaction {
            val current = database.cassaDao().transactionById(transaction.id) ?: transaction
            current.localReceiptPath?.let { path -> runCatching { java.io.File(path).delete() } }
            val deleted = current.copy(updatedAt = now, deletedAt = now)
            database.cassaDao().upsertTransaction(deleted)
            database.cassaDao().enqueue(
                OutboxEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "transaction",
                    operation = "delete",
                    entityId = transaction.id,
                    baseRevision = deleted.revision,
                    payloadJson = "{}",
                    createdAt = now,
                )
            )
        }
    }

    suspend fun updateOpeningBalance(account: AccountEntity, openingBalanceCents: Long) {
        val updated = account.copy(openingBalanceCents = openingBalanceCents)
        val now = Instant.now().toString()
        val payload = JSONObject().apply {
            put("id", updated.id)
            put("name", updated.name)
            put("kind", updated.kind)
            put("opening_balance_cents", updated.openingBalanceCents)
            put("active", updated.active)
            put("revision", updated.revision)
            put("deleted_at", updated.deletedAt ?: JSONObject.NULL)
        }.toString()
        database.withTransaction {
            database.cassaDao().upsertAccount(updated)
            database.cassaDao().enqueue(
                OutboxEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "account",
                    operation = "upsert",
                    entityId = updated.id,
                    baseRevision = updated.revision,
                    payloadJson = payload,
                    createdAt = now,
                )
            )
        }
    }

    suspend fun saveCategory(category: CategoryEntity) {
        require(category.name.isNotBlank()) { "Il nome della categoria è obbligatorio" }
        require(category.transactionType == "expense" || category.transactionType == "income")
        val now = Instant.now().toString()
        val payload = categoryPayload(category)
        database.withTransaction {
            database.cassaDao().upsertCategory(category)
            database.cassaDao().enqueue(
                OutboxEntity(
                    mutationId = UUID.randomUUID().toString(), entityType = "category",
                    operation = "upsert", entityId = category.id,
                    baseRevision = category.revision, payloadJson = payload, createdAt = now,
                )
            )
        }
    }

    suspend fun deleteCategory(category: CategoryEntity): Boolean {
        val now = Instant.now().toString()
        return database.withTransaction {
            if (database.cassaDao().categoryUsageCount(category.id) > 0) return@withTransaction false
            val deleted = category.copy(active = false, deletedAt = now)
            database.cassaDao().upsertCategory(deleted)
            database.cassaDao().enqueue(
                OutboxEntity(
                    mutationId = UUID.randomUUID().toString(), entityType = "category",
                    operation = "delete", entityId = category.id,
                    baseRevision = category.revision, payloadJson = categoryPayload(deleted), createdAt = now,
                )
            )
            true
        }
    }

    private fun categoryPayload(category: CategoryEntity): String = JSONObject().apply {
        put("id", category.id)
        put("name", category.name)
        put("transaction_type", category.transactionType)
        put("sort_order", category.sortOrder)
        put("active", category.active)
        put("revision", category.revision)
        put("deleted_at", category.deletedAt ?: JSONObject.NULL)
    }.toString()

    private fun validateShape(value: TransactionEntity) {
        if (value.transactionType == "transfer") {
            require(value.destinationAccountId != null && value.destinationAccountId != value.accountId)
            require(value.categoryId == null)
        } else {
            require(value.transactionType == "expense" || value.transactionType == "income")
            require(value.destinationAccountId == null && value.categoryId != null)
        }
    }
}

interface TransactionJson {
    fun encode(transaction: TransactionEntity): String
}
