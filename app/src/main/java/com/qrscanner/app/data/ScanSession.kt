package com.qrscanner.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_sessions",
    indices = [
        Index(value = ["cloudId"]),
        // Push-loop hot path. getDirtyForPush queries:
        //   WHERE syncStatus IN ('DIRTY','SYNC_ERROR') AND isActive = 0
        //   ORDER BY updatedAt ASC
        // The composite (syncStatus, updatedAt) covers both the filter
        // and the sort with a single index seek instead of a full table
        // scan + filesort. At 1,200 sessions/year the index pays for
        // itself within the first push cycle.
        Index(value = ["syncStatus", "updatedAt"]),
        // History/list queries filter `deletedAt IS NULL` (10+ call
        // sites). Standalone index on deletedAt suffices because the
        // selectivity is high (most rows live, few tombstoned).
        Index(value = ["deletedAt"])
    ]
)
data class ScanSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val isActive: Boolean = true,
    val totalLots: Int = 0,
    val totalRdNumbers: Int = 0,
    val displayNumber: Int = 0,
    /**
     * The LOT currently being scanned into, or null if no LOT is in progress.
     *
     * Persisted source of truth for resume — survives process death where
     * Compose's rememberSaveable bundle does not. The scanner sets this on
     * first scan of a LOT and clears it on Finish-LOT / End-Session / discard.
     * On relaunch, [adoptSession] reads this instead of inferring the in-progress
     * LOT from row counts, so a just-finished LOT can never be misidentified.
     */
    val activeLotId: Long? = null,

    /**
     * Cloud UUID of the [DeviceSettings] row that originated this session.
     * Stamped at finalize time so the portal can answer 'which phone scanned
     * this?'. Remains null for sessions started before v6 until the user
     * signs in and the push worker backfills.
     */
    val deviceCloudId: String? = null,

    /**
     * Free-text operator name captured from [DeviceSettings.operatorName] at
     * finalize time, e.g. 'Ravi'. Persisted on the session so an operator
     * switch on the same phone doesn't retroactively rewrite older sessions.
     */
    val operatorName: String? = null,

    val cloudId: String? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val lastSyncError: String? = null,
    val deletedAt: Long? = null,

    /**
     * Consecutive push failure count. Incremented on each
     * [SyncStatus.SYNC_ERROR] transition, reset to 0 on
     * [SyncStatus.SYNCED]. When the value hits
     * [com.qrscanner.app.data.sync.SyncRepository.PUSH_ABANDON_THRESHOLD]
     * the row flips to [SyncStatus.SYNC_ABANDONED] and stops retrying
     * (oracle bg_0ea195ce R3 / I6).
     */
    val retryCount: Int = 0
)

@Entity(
    tableName = "scan_lots",
    foreignKeys = [
        ForeignKey(
            entity = ScanSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["cloudId"]),
        // See ScanSession indices KDoc — same push-loop rationale.
        Index(value = ["syncStatus", "updatedAt"]),
        Index(value = ["deletedAt"])
    ]
)
data class ScanLot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val lotNumber: Int,
    val timestamp: Long = System.currentTimeMillis(),

    val cloudId: String? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val lastSyncError: String? = null,
    val deletedAt: Long? = null,

    /** See [ScanSession.retryCount]. */
    val retryCount: Int = 0
)
