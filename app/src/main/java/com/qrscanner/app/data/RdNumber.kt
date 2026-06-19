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
    val scannedAt: Long = System.currentTimeMillis(),
    /**
     * Number of months this account has paid for in the current cycle.
     *
     * Normal RD payments are monthly, so the default is 1. Values greater
     * than 1 indicate a defaulter who is paying multiple months at once
     * (e.g. catching up after missed payments). Bounded to [MONTHS_MIN]..[MONTHS_MAX].
     */
    val monthsPaid: Int = MONTHS_DEFAULT,
    /**
     * Comma-separated list of YYYY-MM tokens identifying which months the
     * payment covers, or null when monthsPaid == 1 (the only-current-month
     * case where the list is implicit).
     *
     * Invariant: when non-null, token count must equal [monthsPaid]. Enforced
     * at the dialog save boundary; the UI falls back to an auto-derived
     * window when the value is null or the invariant is violated, so a bad
     * DB write never breaks rendering.
     */
    val monthsList: String? = null
) {
    companion object {
        const val MONTHS_MIN = 1
        const val MONTHS_MAX = 36
        const val MONTHS_DEFAULT = 1
    }
}
