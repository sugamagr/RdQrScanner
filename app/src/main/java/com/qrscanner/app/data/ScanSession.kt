package com.qrscanner.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val isActive: Boolean = true,
    val totalLots: Int = 0,
    val totalRdNumbers: Int = 0,
    val displayNumber: Int = 0  // Sequential number assigned when session completes
)

@Entity(tableName = "scan_lots")
@TypeConverters(RdNumberListConverter::class)
data class ScanLot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val lotNumber: Int,
    val rdNumbers: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    val rdNumberCount: Int get() = rdNumbers.size
    
    fun getRdNumbersAsString(): String = rdNumbers.joinToString(", ")
}

class RdNumberListConverter {
    @TypeConverter
    fun fromString(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    
    @TypeConverter
    fun toString(list: List<String>?): String {
        return list?.joinToString(",") ?: ""
    }
}

// Validation helper
fun isValidRdNumber(number: String): Boolean {
    val cleanNumber = number.trim()
    return cleanNumber.matches(Regex("^\\d{9,15}$"))
}


