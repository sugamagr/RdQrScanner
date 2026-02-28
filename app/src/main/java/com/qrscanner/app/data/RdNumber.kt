package com.qrscanner.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rd_numbers",
    foreignKeys = [
        ForeignKey(
            entity = ScanLot::class,
            parentColumns = ["id"],
            childColumns = ["lotId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["lotId"]),
        Index(value = ["lotId", "number"]),
        Index(value = ["number"])
    ]
)
data class RdNumber(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val lotId: Long,
    val number: String,
    val position: Int,
    val scannedAt: Long = System.currentTimeMillis()
)
