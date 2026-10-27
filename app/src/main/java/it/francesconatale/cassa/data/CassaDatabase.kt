package it.francesconatale.cassa.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, TransactionEntity::class,
        OutboxEntity::class, SyncStateEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class CassaDatabase : RoomDatabase() {
    abstract fun cassaDao(): CassaDao

    companion object {
        @Volatile
        private var INSTANCE: CassaDatabase? = null

        fun getInstance(context: Context): CassaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    CassaDatabase::class.java,
                    "cassa.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .enableMultiInstanceInvalidation()
                .build().also { INSTANCE = it }
            }
        }
    }
}
