package com.qrscanner.app.data

import androidx.room.Entity
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
    val displayNumber: Int = 0
)

@Entity(tableName = "scan_lots")
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
