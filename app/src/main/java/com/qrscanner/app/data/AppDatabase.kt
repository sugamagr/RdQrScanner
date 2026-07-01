package com.qrscanner.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScanSession::class,
        ScanLot::class,
        RdNumber::class,
        DeviceSettings::class,
        SyncEvent::class,
        RdAccount::class
    ],
    version = 12,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scanLotDao(): ScanLotDao
    abstract fun rdNumberDao(): RdNumberDao
    abstract fun deviceSettingsDao(): DeviceSettingsDao
    abstract fun syncEventDao(): SyncEventDao
    abstract fun rdAccountDao(): RdAccountDao

    /**
     * Sign-out wipe — atomically drops every row of user-owned content
     * (sessions, lots, rd_numbers, rd_accounts, sync_events). The
     * device_settings singleton is NOT cleared here — callers invoke
     * [DeviceSettingsDao.clearOwner] separately so identity-vs-data
     * stay independently testable. Delete order respects FK direction
     * (children first) so the wipe never fails an FK check mid-
     * transaction even when destructive-migration paths skip cascade.
     *
     * Threat model: without this wipe, signing out and signing back
     * in as a different owner would surface the prior owner's
     * locally-cached sessions and accounts — a cross-owner data leak
     * on shared devices.
     */
    suspend fun wipeAllUserData() {
        withTransaction {
            rdNumberDao().deleteAll()
            scanLotDao().deleteAllLots()
            scanSessionDao().deleteAll()
            rdAccountDao().deleteAll()
            syncEventDao().deleteAll()
        }
    }

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
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
            override fun migrate(db: SupportSQLiteDatabase) {
                // scan_sessions: 8 new columns (6 sync + 2 session-specific).
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `deviceCloudId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `operatorName` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `cloudId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `syncedAt` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_sessions` ADD COLUMN `deletedAt` INTEGER DEFAULT NULL")
                db.execSQL("""
                    UPDATE `scan_sessions`
                    SET `syncStatus` = 'DIRTY',
                        `updatedAt` = COALESCE(`endTime`, `startTime`, strftime('%s','now') * 1000)
                    WHERE `isActive` = 0
                """)
                db.execSQL("""
                    UPDATE `scan_sessions`
                    SET `updatedAt` = `startTime`
                    WHERE `isActive` = 1
                """)

                // scan_lots: 6 new columns.
                db.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `cloudId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `syncedAt` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `scan_lots` ADD COLUMN `deletedAt` INTEGER DEFAULT NULL")
                db.execSQL("""
                    UPDATE `scan_lots`
                    SET `syncStatus` = 'DIRTY',
                        `updatedAt` = COALESCE(`timestamp`, strftime('%s','now') * 1000)
                    WHERE `sessionId` IN (SELECT `id` FROM `scan_sessions` WHERE `isActive` = 0)
                """)
                db.execSQL("""
                    UPDATE `scan_lots`
                    SET `updatedAt` = COALESCE(`timestamp`, strftime('%s','now') * 1000)
                    WHERE `sessionId` IN (SELECT `id` FROM `scan_sessions` WHERE `isActive` = 1)
                """)

                // rd_numbers: 6 new columns.
                db.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `cloudId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
                db.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `syncedAt` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `lastSyncError` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `rd_numbers` ADD COLUMN `deletedAt` INTEGER DEFAULT NULL")
                db.execSQL("""
                    UPDATE `rd_numbers`
                    SET `syncStatus` = 'DIRTY',
                        `updatedAt` = COALESCE(`scannedAt`, strftime('%s','now') * 1000)
                    WHERE `lotId` IN (
                        SELECT `id` FROM `scan_lots` WHERE `sessionId` IN (
                            SELECT `id` FROM `scan_sessions` WHERE `isActive` = 0
                        )
                    )
                """)
                db.execSQL("""
                    UPDATE `rd_numbers`
                    SET `updatedAt` = COALESCE(`scannedAt`, strftime('%s','now') * 1000)
                    WHERE `lotId` IN (
                        SELECT `id` FROM `scan_lots` WHERE `sessionId` IN (
                            SELECT `id` FROM `scan_sessions` WHERE `isActive` = 1
                        )
                    )
                """)

                // device_settings: single-row table seeded with id = 1.
                db.execSQL("""
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
                db.execSQL("INSERT OR IGNORE INTO `device_settings` (`id`) VALUES (1)")

                // sync_events: bounded log of remote changes feeding the banner.
                db.execSQL("""
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_events_occurredAt` ON `sync_events` (`occurredAt`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `scan_sessions` ADD COLUMN `activeLotId` INTEGER DEFAULT NULL"
                )

                // Best-effort backfill: any session that was mid-scan at upgrade
                // time gets its highest-numbered LOT pinned as activeLotId, so
                // resume continues into the same LOT instead of starting a new
                // one. Without this, the v3 in-progress LOT becomes a phantom
                // 'completed' LOT in the user's count.
                db.execSQL("""
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
                db.execSQL("PRAGMA legacy_alter_table = ON")
                db.execSQL("ALTER TABLE `scan_lots` RENAME TO `scan_lots_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scan_lots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `lotNumber` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        FOREIGN KEY(`sessionId`) REFERENCES `scan_sessions`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("""
                    INSERT INTO `scan_lots` (`id`, `sessionId`, `lotNumber`, `timestamp`)
                    SELECT `id`, `sessionId`, `lotNumber`, `timestamp` FROM `scan_lots_old`
                """)
                db.execSQL("DROP TABLE `scan_lots_old`")
                db.execSQL("PRAGMA legacy_alter_table = OFF")

                db.execSQL("CREATE INDEX IF NOT EXISTS `index_scan_lots_sessionId` ON `scan_lots` (`sessionId`)")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create rd_numbers table with FK → scan_lots.id CASCADE DELETE
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `rd_numbers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `lotId` INTEGER NOT NULL,
                        `number` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `scannedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`lotId`) REFERENCES `scan_lots`(`id`) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rd_numbers_lotId` ON `rd_numbers` (`lotId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rd_numbers_lotId_number` ON `rd_numbers` (`lotId`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rd_numbers_number` ON `rd_numbers` (`number`)")

                // Recreate scan_lots without the rdNumbers column (SQLite rename-create-copy-drop).
                // legacy_alter_table=ON keeps the freshly-created rd_numbers.lotId FK pointed at
                // 'scan_lots' instead of being auto-rewritten to 'scan_lots_old' by SQLite 3.26+.
                db.execSQL("PRAGMA legacy_alter_table = ON")
                db.execSQL("ALTER TABLE `scan_lots` RENAME TO `scan_lots_old`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scan_lots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `lotNumber` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO `scan_lots` (`id`, `sessionId`, `lotNumber`, `timestamp`)
                    SELECT `id`, `sessionId`, `lotNumber`, `timestamp` FROM `scan_lots_old`
                """)
                db.execSQL("DROP TABLE `scan_lots_old`")
                db.execSQL("PRAGMA legacy_alter_table = OFF")
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
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `scan_sessions` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `scan_lots` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `rd_numbers` ADD COLUMN `retryCount` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v7 → v8: creates the `rd_accounts` table for customer-account
         * profile metadata (name + monthly amount + last-paid-through +
         * source + lifecycle dates). Phase RD-Accounts.
         *
         * Schema mirrors the [RdAccount] entity. New install — no
         * backfill needed; the table starts empty and gets populated
         * via the AddAccountsScreen spreadsheet or the portal CSV bulk
         * upload + cloud pull. accountOpenedDate / accountClosingDate
         * columns added schema-only this round (no UI yet).
         */
        /**
         * v9 → v10: Adds covering indexes on the push-loop hot path
         * for every syncable entity (scan_sessions, scan_lots,
         * rd_numbers, rd_accounts):
         *  - (syncStatus, updatedAt) — covers getDirtyForPush's
         *    `WHERE syncStatus IN ('DIRTY','SYNC_ERROR') ORDER BY
         *    updatedAt ASC` with a single index seek.
         *  - (deletedAt) — covers the `WHERE deletedAt IS NULL`
         *    filter present in 10+ history/list queries.
         *  - rd_accounts also gains a cloudId index for symmetry
         *    with the other entities.
         *
         * Pure index-add migration: no schema/data changes, no
         * backfill needed. Safe to re-run (CREATE INDEX IF NOT
         * EXISTS) so a partial-failure replay doesn't break.
         */
        // v10 -> v11: repair rd_accounts.isActive DEFAULT drift. Devices
        // upgraded through an early Phase-RD-Accounts build have a
        // rd_accounts table whose isActive column was created WITHOUT
        // the SQL-level DEFAULT 1 clause (the entity carried only a
        // Kotlin default, no @ColumnInfo(defaultValue = "1")). Room
        // reads the live SQLite table info to build TableInfo.Found;
        // the entity annotation drives TableInfo.Expected. They now
        // disagree on exactly one cell: isActive defaultValue.
        //
        // Fix: rebuild rd_accounts with the correct DEFAULT. Standard
        // rename-create-copy-drop pattern (matches MIGRATION_1_2 and
        // MIGRATION_3_4 for consistency). All rows preserved via SELECT
        // *; PK on rdNumber preserved; indexes recreated identically.
        // legacy_alter_table=ON keeps this defensive even though no
        // child table FK-references rd_accounts today (no auto-retarget
        // hazard, but the pragma costs nothing and matches the codebase
        // convention).
        //
        // Fresh installs are unaffected: MIGRATION_7_8 already emits
        // DEFAULT 1 on the CREATE TABLE, so their Found already matches
        // Expected.
        // v11 -> v12: adds `csvImportedAt INTEGER DEFAULT NULL` to
        // rd_accounts for the R3 session-delete cascade CSV-authority
        // guard. Read contract: session.endTime > account.csvImportedAt
        // (or csvImportedAt IS NULL) means the scan post-dates the last
        // CSV import and its contribution to lastPaidThrough should be
        // reverted on session delete. Otherwise the CSV is authoritative
        // (came from the live DOP portal) and the session is dropped
        // silently. Writer is portal bulkUpsertAccounts only; scan-flow
        // never touches this column. Deliberately distinct from
        // updatedAt because updatedAt reflects the last write of ANY
        // kind (rename, tombstone, cloud pull) whereas csvImportedAt
        // must specifically track "when was authoritative CSV data last
        // stamped over this row". Existing rows backfill to NULL which
        // reads as "no CSV ever imported" — the safe default that
        // reverts every session on delete.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `rd_accounts` ADD COLUMN `csvImportedAt` INTEGER DEFAULT NULL"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA legacy_alter_table = ON")
                db.execSQL("ALTER TABLE `rd_accounts` RENAME TO `rd_accounts_old`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rd_accounts` (
                        `rdNumber` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `monthlyAmount` INTEGER NOT NULL,
                        `lastPaidThrough` TEXT,
                        `source` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `accountOpenedDate` TEXT,
                        `accountClosingDate` TEXT,
                        `ownerId` TEXT,
                        `cloudId` TEXT,
                        `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `syncedAt` INTEGER,
                        `lastSyncError` TEXT,
                        `deletedAt` INTEGER,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `lastEditorDeviceId` TEXT,
                        PRIMARY KEY(`rdNumber`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `rd_accounts` (
                        `rdNumber`, `name`, `monthlyAmount`, `lastPaidThrough`,
                        `source`, `isActive`, `accountOpenedDate`, `accountClosingDate`,
                        `ownerId`, `cloudId`, `syncStatus`, `updatedAt`, `syncedAt`,
                        `lastSyncError`, `deletedAt`, `retryCount`, `lastEditorDeviceId`
                    )
                    SELECT
                        `rdNumber`, `name`, `monthlyAmount`, `lastPaidThrough`,
                        `source`, `isActive`, `accountOpenedDate`, `accountClosingDate`,
                        `ownerId`, `cloudId`, `syncStatus`, `updatedAt`, `syncedAt`,
                        `lastSyncError`, `deletedAt`, `retryCount`, `lastEditorDeviceId`
                    FROM `rd_accounts_old`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `rd_accounts_old`")
                db.execSQL("PRAGMA legacy_alter_table = OFF")

                // Recreate every index exactly as declared on the entity
                // (matches the set Room reports in TableInfo.Expected.indices
                // from the crash dump). Order alphabetical for reviewability.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_cloudId` ON `rd_accounts` (`cloudId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_deletedAt` ON `rd_accounts` (`deletedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_isActive` ON `rd_accounts` (`isActive`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_name` ON `rd_accounts` (`name`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_source` ON `rd_accounts` (`source`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_syncStatus_updatedAt` ON `rd_accounts` (`syncStatus`, `updatedAt`)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_sessions_syncStatus_updatedAt` ON `scan_sessions` (`syncStatus`, `updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_sessions_deletedAt` ON `scan_sessions` (`deletedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_lots_syncStatus_updatedAt` ON `scan_lots` (`syncStatus`, `updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_lots_deletedAt` ON `scan_lots` (`deletedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_numbers_syncStatus_updatedAt` ON `rd_numbers` (`syncStatus`, `updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_numbers_deletedAt` ON `rd_numbers` (`deletedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_syncStatus_updatedAt` ON `rd_accounts` (`syncStatus`, `updatedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_deletedAt` ON `rd_accounts` (`deletedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_cloudId` ON `rd_accounts` (`cloudId`)"
                )
            }
        }

        /**
         * v8 → v9: Adds [RdNumber.lastEditorDeviceId] so pulls can
         * persist the per-row "edited by Portal / edited by another
         * phone" attribution that previously existed only on the
         * wire (RdNumberDto) and the cloud schema. Pre-v9 pulls
         * accepted the field over the network but had nowhere to
         * store it, so the attribution badge stayed blank after the
         * next process restart.
         *
         * Defensive registration (Q3=B was destructive while pre-
         * release, but the explicit migration becomes data-preserving
         * the moment Q3=B ends and the destructive fallback is
         * removed). Column is nullable so existing rows backfill to
         * NULL — which is exactly the "unknown editor" attribution
         * we want for historical rows that never carried the field.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `rd_numbers` ADD COLUMN `lastEditorDeviceId` TEXT DEFAULT NULL"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `rd_accounts` (
                        `rdNumber` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `monthlyAmount` INTEGER NOT NULL,
                        `lastPaidThrough` TEXT,
                        `source` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL DEFAULT 1,
                        `accountOpenedDate` TEXT,
                        `accountClosingDate` TEXT,
                        `ownerId` TEXT,
                        `cloudId` TEXT,
                        `syncStatus` TEXT NOT NULL DEFAULT 'LOCAL_ONLY',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `syncedAt` INTEGER,
                        `lastSyncError` TEXT,
                        `deletedAt` INTEGER,
                        `retryCount` INTEGER NOT NULL DEFAULT 0,
                        `lastEditorDeviceId` TEXT,
                        PRIMARY KEY(`rdNumber`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_name` ON `rd_accounts` (`name`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_source` ON `rd_accounts` (`source`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_rd_accounts_isActive` ON `rd_accounts` (`isActive`)"
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
                    // All migrations MUST be registered. Without this
                    // call, fallbackToDestructiveMigration fires on
                    // every version bump and silently drops every
                    // table — a data-loss bomb the moment Q3=B
                    // pre-release ends. The destructive fallback
                    // stays as last resort for corrupt or unknown
                    // version paths but should never run in a
                    // healthy upgrade.
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
