package com.yanhuozhang.offline.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        LedgerRecordEntity::class,
        TransactionEventEntity::class,
        PersonalRuleEntity::class,
        IgnoredFingerprintEntity::class,
        MonthlyAnalysisReportEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun eventDao(): EventDao
    abstract fun ruleDao(): RuleDao
    abstract fun ignoredFingerprintDao(): IgnoredFingerprintDao
    abstract fun monthlyAnalysisDao(): MonthlyAnalysisDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transaction_events ADD COLUMN counterparty TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS monthly_analysis_reports (
                        monthKey TEXT NOT NULL,
                        totalExpenseCents INTEGER NOT NULL,
                        summary TEXT NOT NULL,
                        observation1 TEXT,
                        observation2 TEXT,
                        observation3 TEXT,
                        suggestion TEXT,
                        lightComment TEXT,
                        model TEXT NOT NULL,
                        thinkingMode TEXT NOT NULL,
                        inputFingerprint TEXT NOT NULL,
                        generatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(monthKey)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE categories ADD COLUMN colorHex TEXT")
                database.execSQL("ALTER TABLE categories ADD COLUMN emoji TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yanhuo-ledger.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { database -> instance = database }
            }
        }
    }
}
