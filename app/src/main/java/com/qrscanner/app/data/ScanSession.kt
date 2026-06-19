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
    val activeLotId: Long? = null
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
    val timestamp: Long = System.currentTimeMillis()
)

// Validation helper
fun isValidRdNumber(number: String): Boolean {
    val cleanNumber = number.trim()
    return cleanNumber.matches(Regex("^\\d{9,15}$"))
}
