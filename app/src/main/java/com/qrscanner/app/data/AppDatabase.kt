package com.qrscanner.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScanSession::class,
        ScanLot::class,
        RdNumber::class,
        DeviceSettings::class,
        SyncEvent::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scanLotDao(): ScanLotDao
    abstract fun rdNumberDao(): RdNumberDao
    abstract fun deviceSettingsDao(): DeviceSettingsDao
    abstract fun syncEventDao(): SyncEventDao

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

        /**
         * v5 → v6: Cloud sync schema bump.
         *
         * Adds the per-row sync metadata block (cloudId, syncStatus, updatedAt,
         * syncedAt, lastSyncError, deletedAt) to scan_sessions, scan_lots, and
         * rd_numbers. ScanSession also gains deviceCloudId + operatorName.
         *
         * Two new tables for the sync engine itself:
         *   - device_settings: single-row key/value with CHECK(id = 1).
         *     NB: the CHECK constraint exists on upgraded DBs but NOT on
         *     fresh installs (Room's entity-generated CREATE TABLE doesn't
         *     emit CHECK). The DAO surface enforces id = 1 in every query,
         *     so the divergence is invisible at runtime.
         *   - sync_events: bounded log feeding the in-app banner (§15.5.5).
         *
         * Backfill rules (see spec §17):
         *   - existing finalized rows (isActive = 0) flip to syncStatus = DIRTY
         *     so they push on first sign-in.
         *   - existing active rows stay LOCAL_ONLY (they're device-private
         *     until the user finalizes them).
         *   - updatedAt seeded from the row's natural timestamp (startTime /
         *     timestamp / scannedAt) so conflict resolution has a defensible
         *     starting value. COALESCE falls back to current epoch ms in the
         *     impossible-but-defensive case where the natural timestamp is
         *     NULL.
         *   - device_settings seeded with id = 1, all nullable columns null.
         *
         * Idempotency: ADD COLUMN itself is NOT idempotent (replaying throws
         * "duplicate column"), but Room never replays a completed migration
         * because the schema_version pragma bumps atomically at the end of
         * the transaction. So in practice this migration runs at most once
         * per device per upgrade.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // scan_sessions: 8 new columns (6 sync + 2 session-specific).
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `deviceCloudId` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `operatorName` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `cloudId` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `syncedAt` INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `deletedAt` INTEGER DEFAULT NULL")
                database.execSQL("""
                    UPDATE `scan_sessions`
                    SET `syncStatus` = 'DIRTY',
                        `updatedAt` = COALESCE(`endTime`, `startTime`, strftime('%s','now') * 1000)
                    WHERE `isActive` = 0
                """)
                database.execSQL("""
                    UPDATE `scan_sessions`
                    SET `updatedAt` = `startTime`
                    WHERE `isActive` = 1
                """)

                // scan_lots: 6 new columns.
                database.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `cloudId` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                database.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `syncedAt` INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `deletedAt` INTEGER DEFAULT NULL")
                database.execSQL("""
                    UPDATE `scan_lots`
                    SET `syncStatus` = 'DIRTY',
                        `updatedAt` = COALESCE(`timestamp`, strftime('%s','now') * 1000)
                    WHERE `sessionId` IN (SELECT `id` FROM `scan_sessions` WHERE `isActive` = 0)
                """)
                database.execSQL("""
                    UPDATE `scan_lots`
                    SET `updatedAt` = COALESCE(`timestamp`, strftime('%s','now') * 1000)
                    WHERE `sessionId` IN (SELECT `id` FROM `scan_sessions` WHERE `isActive` = 1)
                """)

                // rd_numbers: 6 new columns.
                database.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `cloudId` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                database.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `syncedAt` INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `deletedAt` INTEGER DEFAULT NULL")
                database.execSQL("""
                    UPDATE `rd_numbers`
                    SET `syncStatus` = 'DIRTY',
                        `updatedAt` = COALESCE(`scannedAt`, strftime('%s','now') * 1000)
                    WHERE `lotId` IN (
                        SELECT `id` FROM `scan_lots` WHERE `sessionId` IN (
                            SELECT `id` FROM `scan_sessions` WHERE `isActive` = 0
                        )
                    )
                """)
                database.execSQL("""
                    UPDATE `rd_numbers`
                    SET `updatedAt` = COALESCE(`scannedAt`, strftime('%s','now') * 1000)
                    WHERE `lotId` IN (
                        SELECT `id` FROM `scan_lots` WHERE `sessionId` IN (
                            SELECT `id` FROM `scan_sessions` WHERE `isActive` = 1
                        )
                    )
                """)

                // device_settings: single-row table seeded with id = 1.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `device_settings` (
                        `id` INTEGER PRIMARY KEY NOT NULL CHECK(`id` = 1),
                        `deviceCloudId` TEXT DEFAULT NULL,
                        `deviceName` TEXT DEFAULT NULL,
                        `operatorName` TEXT DEFAULT NULL,
                        `ownerId` TEXT DEFAULT NULL,
                        `lastPulledAt` INTEGER NOT NULL DEFAULT 0,
                        `lastPullErrorAt` INTEGER DEFAULT NULL,
                        `lastPullError` TEXT DEFAULT NULL,
                        `lastBannerSeenAt` INTEGER NOT NULL DEFAULT 0
                    )
                """)
                database.execSQL("INSERT OR IGNORE INTO `device_settings` (`id`) VALUES (1)")

                // sync_events: bounded log of remote changes feeding the banner.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurredAt` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `sessionCloudId` TEXT DEFAULT NULL,
                        `rdNumberCloudId` TEXT DEFAULT NULL,
                        `originDeviceCloudId` TEXT DEFAULT NULL,
                        `originDeviceName` TEXT DEFAULT NULL,
                        `originOperatorName` TEXT DEFAULT NULL,
                        `payloadSummary` TEXT NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_events_occurredAt` ON `sync_events` (`occurredAt`)")
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

        /**
         * v6 → v7: adds `retryCount INTEGER NOT NULL DEFAULT 0` to
         * scan_sessions, scan_lots, and rd_numbers. Powers the
         * [SyncStatus.SYNC_ABANDONED] circuit-breaker (oracle R3 / I6).
         * Existing rows backfill to 0 via the column default; the next
         * push cycle treats them as fresh.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `scan_sessions` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `scan_lots` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE `rd_numbers` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rd_scanner_database"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                )
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
