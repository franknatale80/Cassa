package it.francesconatale.cassa.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val database = db
        val accounts = mapOf(
            "checking" to "00000000-0000-4000-8000-000000000001",
            "cash" to "00000000-0000-4000-8000-000000000002",
        )
        val categories = mapOf(
            "groceries" to "20000000-0000-4000-8000-000000000001",
            "home" to "20000000-0000-4000-8000-000000000002",
            "health" to "20000000-0000-4000-8000-000000000004",
            "transport" to "20000000-0000-4000-8000-000000000005",
            "leisure" to "20000000-0000-4000-8000-000000000007",
            "other-expense" to "20000000-0000-4000-8000-000000000009",
            "salary" to "20000000-0000-4000-8000-000000000010",
            "refund" to "20000000-0000-4000-8000-000000000011",
            "other-income" to "20000000-0000-4000-8000-000000000012",
        )
        accounts.forEach { (old, new) -> replaceId(database, "accounts", old, new) }
        categories.forEach { (old, new) -> replaceId(database, "categories", old, new) }

        accounts.forEach { (old, new) ->
            database.execSQL("UPDATE transactions SET accountId=? WHERE accountId=?", arrayOf(new, old))
            database.execSQL("UPDATE transactions SET destinationAccountId=? WHERE destinationAccountId=?", arrayOf(new, old))
            replaceOutbox(database, old, new)
        }
        categories.forEach { (old, new) ->
            database.execSQL("UPDATE transactions SET categoryId=? WHERE categoryId=?", arrayOf(new, old))
            replaceOutbox(database, old, new)
        }
    }

    private fun replaceId(database: SupportSQLiteDatabase, table: String, old: String, new: String) {
        database.execSQL("UPDATE $table SET id=? WHERE id=?", arrayOf(new, old))
    }

    private fun replaceOutbox(database: SupportSQLiteDatabase, old: String, new: String) {
        database.execSQL("UPDATE sync_outbox SET entityId=? WHERE entityId=?", arrayOf(new, old))
        database.execSQL(
            "UPDATE sync_outbox SET payloadJson=replace(payloadJson, ?, ?) WHERE instr(payloadJson, ?) > 0",
            arrayOf(old, new, old),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN receiptPaperlessId INTEGER")
        db.execSQL("ALTER TABLE transactions ADD COLUMN receiptUrl TEXT")
        db.execSQL("ALTER TABLE transactions ADD COLUMN localReceiptPath TEXT")
    }
}
