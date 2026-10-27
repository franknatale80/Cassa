package it.francesconatale.cassa.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class TransactionRepositoryTest {
    private lateinit var database: CassaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CassaDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun saveOfflineWritesMovementAndOutboxAtomically() = runBlocking {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val repository = TransactionRepository(database, object : TransactionJson {
            override fun encode(transaction: TransactionEntity) = "{\"id\":\"${transaction.id}\"}"
        })
        repository.saveOffline(
            TransactionEntity(
                id = id, transactionType = "expense", amountCents = 1250,
                accountId = UUID.randomUUID().toString(), categoryId = UUID.randomUUID().toString(),
                effectiveAt = now, createdAt = now, updatedAt = now,
            )
        )
        assertNotNull(database.cassaDao().transactionById(id))
        assertEquals(1, database.cassaDao().pendingCount(id))
    }

    @Test
    fun updateAndDeleteKeepOneMovementAndQueueEveryMutation() = runBlocking {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val repository = repository()
        val original = TransactionEntity(id, "expense", 1250, "checking", categoryId = "groceries", effectiveAt = now, createdAt = now, updatedAt = now)
        repository.saveOffline(original)
        repository.saveOffline(original.copy(amountCents = 1750, note = "Corretto"))
        assertEquals(1750L, database.cassaDao().transactionById(id)?.amountCents)
        assertEquals(2, database.cassaDao().pendingCount(id))
        repository.deleteOffline(database.cassaDao().transactionById(id)!!)
        assertNotNull(database.cassaDao().transactionById(id)?.deletedAt)
        assertEquals(3, database.cassaDao().pendingCount(id))
    }

    @Test
    fun categoryDeletionIsAllowedOnlyWhenUnused() = runBlocking {
        val repository = repository()
        val unused = CategoryEntity("unused", "Prova", "expense", 1, true, 0)
        repository.saveCategory(unused)
        assertEquals(true, repository.deleteCategory(unused))
        assertEquals(false, database.cassaDao().categoryById("unused")?.active)

        val used = CategoryEntity("used", "Usata", "expense", 2, true, 0)
        repository.saveCategory(used)
        val now = Instant.now().toString()
        repository.saveOffline(TransactionEntity(UUID.randomUUID().toString(), "expense", 100, "checking", categoryId = used.id, effectiveAt = now, createdAt = now, updatedAt = now))
        assertEquals(false, repository.deleteCategory(used))
        assertEquals(true, database.cassaDao().categoryById("used")?.active)
    }

    private fun repository() = TransactionRepository(database, object : TransactionJson {
        override fun encode(transaction: TransactionEntity) = "{\"id\":\"${transaction.id}\"}"
    })
}
