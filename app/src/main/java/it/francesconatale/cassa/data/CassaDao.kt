package it.francesconatale.cassa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CassaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(mutation: OutboxEntity)

    @Query("SELECT * FROM transactions WHERE deletedAt IS NULL ORDER BY effectiveAt DESC")
    fun observeTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM accounts WHERE active = 1 AND deletedAt IS NULL ORDER BY CASE kind WHEN 'bank' THEN 0 WHEN 'cash' THEN 1 ELSE 2 END, name")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM categories WHERE active = 1 AND deletedAt IS NULL ORDER BY sortOrder, name")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun accountCount(): Int

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun categoryUsageCount(categoryId: String): Int

    @Query("SELECT * FROM categories WHERE id = :categoryId")
    suspend fun categoryById(categoryId: String): CategoryEntity?

    @Query("SELECT * FROM accounts WHERE id = :accountId")
    suspend fun accountById(accountId: String): AccountEntity?

    @Query("SELECT * FROM sync_outbox WHERE state = 'pending' ORDER BY createdAt LIMIT :limit")
    suspend fun pendingMutations(limit: Int = 100): List<OutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE state = 'pending' ORDER BY createdAt LIMIT 1")
    suspend fun nextPendingMutation(): OutboxEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun transactionById(id: String): TransactionEntity?

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE entityId = :entityId AND state = 'pending'")
    suspend fun pendingCount(entityId: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state = 'pending'")
    suspend fun totalPendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE sync_outbox SET state = 'sent', errorCode = NULL WHERE mutationId = :mutationId")
    suspend fun markSent(mutationId: String)

    @Query("UPDATE transactions SET revision = :revision WHERE id = :id")
    suspend fun setTransactionRevision(id: String, revision: Long)

    @Query("UPDATE accounts SET revision = :revision WHERE id = :id")
    suspend fun setAccountRevision(id: String, revision: Long)

    @Query("UPDATE categories SET revision = :revision WHERE id = :id")
    suspend fun setCategoryRevision(id: String, revision: Long)

    @Query("UPDATE transactions SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun markRemoteDeleted(id: String, deletedAt: String, revision: Long)

    @Query("SELECT * FROM transactions WHERE localReceiptPath IS NOT NULL AND receiptUrl IS NULL AND deletedAt IS NULL")
    suspend fun pendingReceiptTransactions(): List<TransactionEntity>

    @Query("UPDATE transactions SET receiptPaperlessId = :paperlessId, receiptUrl = :receiptUrl WHERE id = :id")
    suspend fun setReceiptInfo(id: String, paperlessId: Long?, receiptUrl: String?)

    @Query("UPDATE transactions SET localReceiptPath = :path WHERE id = :id")
    suspend fun setLocalReceiptPath(id: String, path: String?)

    @Query("UPDATE sync_outbox SET attemptCount = attemptCount + 1, lastAttemptAt = :at, errorCode = :code WHERE mutationId = :mutationId")
    suspend fun markAttemptFailed(mutationId: String, at: String, code: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE singletonId = 1")
    suspend fun syncState(): SyncStateEntity?

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN transactionType = 'income' AND accountId = :accountId THEN amountCents
            WHEN transactionType = 'expense' AND accountId = :accountId THEN -amountCents
            WHEN transactionType = 'transfer' AND accountId = :accountId THEN -amountCents
            WHEN transactionType = 'transfer' AND destinationAccountId = :accountId THEN amountCents
            ELSE 0 END), 0)
        FROM transactions
        WHERE deletedAt IS NULL AND (accountId = :accountId OR destinationAccountId = :accountId)
    """)
    fun observeAccountDelta(accountId: String): Flow<Long>
}
