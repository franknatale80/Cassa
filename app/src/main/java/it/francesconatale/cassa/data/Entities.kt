package it.francesconatale.cassa.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val openingBalanceCents: Long,
    val active: Boolean,
    val revision: Long,
    val deletedAt: String? = null,
)

@Entity(tableName = "categories", indices = [Index(value = ["transactionType", "sortOrder"])])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val transactionType: String,
    val sortOrder: Int,
    val active: Boolean,
    val revision: Long,
    val deletedAt: String? = null,
)

@Entity(
    tableName = "transactions",
    indices = [Index("effectiveAt"), Index(value = ["accountId", "effectiveAt"])],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val transactionType: String,
    val amountCents: Long,
    val accountId: String,
    val destinationAccountId: String? = null,
    val categoryId: String? = null,
    val paymentMethodId: String? = null,
    val effectiveAt: String,
    val note: String? = null,
    val receiptPaperlessId: Long? = null,
    val receiptUrl: String? = null,
    val localReceiptPath: String? = null,
    val revision: Long = 0,
    val createdAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Entity(tableName = "sync_outbox", indices = [Index(value = ["state", "createdAt"])])
data class OutboxEntity(
    @PrimaryKey val mutationId: String,
    val entityType: String,
    val operation: String,
    val entityId: String,
    val baseRevision: Long,
    val payloadJson: String,
    val state: String = "pending",
    val attemptCount: Int = 0,
    val createdAt: String,
    val lastAttemptAt: String? = null,
    val errorCode: String? = null,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val singletonId: Int = 1,
    val lastServerRevision: Long = 0,
    val lastSuccessfulSyncAt: String? = null,
)
