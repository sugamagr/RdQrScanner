package com.qrscanner.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScanSession::class, ScanLot::class, RdNumber::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scanLotDao(): ScanLotDao
    abstract fun rdNumberDao(): RdNumberDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create rd_numbers table with FK → scan_lots.id CASCADE DELETE
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `rd_numbers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `lotId` INTEGER NOT NULL,
                        `number` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `scannedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`lotId`) REFERENCES `scan_lots`(`id`) ON DELETE CASCADE
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_rd_numbers_lotId` ON `rd_numbers` (`lotId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_rd_numbers_lotId_number` ON `rd_numbers` (`lotId`, `number`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_rd_numbers_number` ON `rd_numbers` (`number`)")

                // Recreate scan_lots without the rdNumbers column (SQLite rename-create-copy-drop)
                database.execSQL("ALTER TABLE `scan_lots` RENAME TO `scan_lots_old`")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scan_lots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `lotNumber` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                """)
                database.execSQL("""
                    INSERT INTO `scan_lots` (`id`, `sessionId`, `lotNumber`, `timestamp`)
                    SELECT `id`, `sessionId`, `lotNumber`, `timestamp` FROM `scan_lots_old`
                """)
                database.execSQL("DROP TABLE `scan_lots_old`")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rd_scanner_database"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
