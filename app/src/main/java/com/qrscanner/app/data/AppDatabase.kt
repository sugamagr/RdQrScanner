package com.qrscanner.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScanSession::class, ScanLot::class, RdNumber::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scanLotDao(): ScanLotDao
    abstract fun rdNumberDao(): RdNumberDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 → v3: Adds [RdNumber.monthsPaid] to track defaulter accounts that
         * pay for multiple months at once. Existing rows backfill to 1 via the
         * column default.
         */
        /**
         * v4 → v5: Adds [RdNumber.monthsList] (nullable TEXT) so defaulter
         * rows can persist which specific YYYY-MM months their payment
         * covered. Existing defaulter rows leave it NULL; the UI derives an
         * auto-window from the session date until the user edits them.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rd_numbers` ADD COLUMN `monthsList` TEXT DEFAULT NULL"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `rd_numbers` ADD COLUMN `monthsPaid` INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * v3 → v4: Two related changes that close two long-standing risks:
         *
         * 1. Adds [ScanSession.activeLotId] (nullable) — persisted source of
         *    truth for "which LOT is in progress", so resume after process
         *    death never misidentifies a just-finished LOT as in-progress.
         *
         * 2. Recreates scan_lots with a FK on sessionId → scan_sessions.id
         *    (ON DELETE CASCADE). Pre-v4 the column had no referential
         *    integrity; deleting a session could orphan its LOTs and rows.
         *    Uses the standard SQLite rename-create-copy-drop pattern.
         *    rd_numbers is unaffected — its FK on lotId already cascades.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `scan_sessions` ADD COLUMN `activeLotId` INTEGER DEFAULT NULL"
                )

                // Best-effort backfill: any session that was mid-scan at upgrade
                // time gets its highest-numbered LOT pinned as activeLotId, so
                // resume continues into the same LOT instead of starting a new
                // one. Without this, the v3 in-progress LOT becomes a phantom
                // 'completed' LOT in the user's count.
                database.execSQL("""
                    UPDATE `scan_sessions`
                    SET `activeLotId` = (
                        SELECT `id` FROM `scan_lots`
                        WHERE `scan_lots`.`sessionId` = `scan_sessions`.`id`
                        ORDER BY `lotNumber` DESC LIMIT 1
                    )
                    WHERE `isActive` = 1
                """)

                // SQLite 3.26+ auto-updates child FK references during RENAME
                // (the rd_numbers.lotId FK would silently re-target scan_lots_old
                // and become dangling after DROP). legacy_alter_table=ON opts
                // out of that behaviour for the duration of the rebuild.
                database.execSQL("PRAGMA legacy_alter_table = ON")
                database.execSQL("ALTER TABLE `scan_lots` RENAME TO `scan_lots_old`")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scan_lots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `lotNumber` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `scan_sessions`(`id`) ON DELETE CASCADE
                    )
                """)
                database.execSQL("""
                    INSERT INTO `scan_lots` (`id`, `sessionId`, `lotNumber`, `timestamp`)
                    SELECT `id`, `sessionId`, `lotNumber`, `timestamp` FROM `scan_lots_old`
                """)
                database.execSQL("DROP TABLE `scan_lots_old`")
                database.execSQL("PRAGMA legacy_alter_table = OFF")

                database.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_lots_sessionId` ON `scan_lots` (`sessionId`)")
            }
        }

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

                // Recreate scan_lots without the rdNumbers column (SQLite rename-create-copy-drop).
                // legacy_alter_table=ON keeps the freshly-created rd_numbers.lotId FK pointed at
                // 'scan_lots' instead of being auto-rewritten to 'scan_lots_old' by SQLite 3.26+.
                database.execSQL("PRAGMA legacy_alter_table = ON")
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
                database.execSQL("PRAGMA legacy_alter_table = OFF")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rd_scanner_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
