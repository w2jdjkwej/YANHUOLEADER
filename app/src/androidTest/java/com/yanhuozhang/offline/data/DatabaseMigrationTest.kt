package com.yanhuozhang.offline.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
    )

    @Test
    fun migration1To2KeepsRecordsAndAddsEventCounterparty() {
        helper.createDatabase(TEST_DATABASE, 1).use { database ->
            database.execSQL(
                "INSERT INTO categories (id,name,accountingType,sortOrder,isActive,isSystem) " +
                    "VALUES (1,'未分类','EXPENSE',0,1,1)",
            )
            database.execSQL(
                "INSERT INTO ledger_records " +
                    "(id,amountCents,direction,accountingType,statisticsEffect,categoryId,paymentChannel," +
                    "counterparty,transactionTimeMillis,statisticsTimeMillis,relatedRecordId,note,status," +
                    "createdAtMillis,updatedAtMillis) " +
                    "VALUES (1,1000,'OUT','EXPENSE','ADD_EXPENSE',1,'WECHAT',NULL,1000,1000,NULL,'','ACTIVE',1000,1000)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT amountCents FROM ledger_records WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1_000L, cursor.getLong(0))
            }
            database.query("PRAGMA table_info(transaction_events)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var counterpartyColumnFound = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "counterparty") {
                        counterpartyColumnFound = true
                    }
                }
                assertTrue(counterpartyColumnFound)
            }
        }
    }

    @Test
    fun migration2To3KeepsRecordsAndAddsMonthlyAnalysisTable() {
        helper.createDatabase(TEST_DATABASE_2_3, 2).use { database ->
            database.execSQL(
                "INSERT INTO categories (id,name,accountingType,sortOrder,isActive,isSystem) " +
                    "VALUES (1,'餐饮','EXPENSE',1,1,1)",
            )
            database.execSQL(
                "INSERT INTO ledger_records " +
                    "(id,amountCents,direction,accountingType,statisticsEffect,categoryId,paymentChannel," +
                    "counterparty,transactionTimeMillis,statisticsTimeMillis,relatedRecordId,note,status," +
                    "createdAtMillis,updatedAtMillis) " +
                    "VALUES (1,2350,'OUT','EXPENSE','ADD_EXPENSE',1,'WECHAT','测试餐厅'," +
                    "1000,1000,NULL,'','ACTIVE',1000,1000)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE_2_3,
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        ).use { database ->
            database.query("SELECT amountCents FROM ledger_records WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2_350L, cursor.getLong(0))
            }
            database.query(
                "SELECT name FROM sqlite_master " +
                    "WHERE type = 'table' AND name = 'monthly_analysis_reports'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("monthly_analysis_reports", cursor.getString(0))
            }
        }
    }

    companion object {
        private const val TEST_DATABASE = "migration-1-2-test"
        private const val TEST_DATABASE_2_3 = "migration-2-3-test"
    }
}
