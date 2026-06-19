package com.qrscanner.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scan_sessions")
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
    val deletedAt: Long? = null
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
    indices = [Index(value = ["sessionId"])]
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
    val deletedAt: Long? = null
)

// Validation helper
fun isValidRdNumber(number: String): Boolean {
    val cleanNumber = number.trim()
    return cleanNumber.matches(Regex("^\\d{9,15}$"))
}
