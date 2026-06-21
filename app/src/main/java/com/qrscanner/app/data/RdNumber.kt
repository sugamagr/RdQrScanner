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
    val monthsList: String? = null,

    // ── Cloud sync metadata (v6) ─────────────────────────────────────────

    /**
     * UUID assigned at row creation, used as the cloud-side primary key.
     *
     * Generated locally via `UUID.randomUUID().toString()` so the row knows
     * its cloud identity before it ever talks to the network. This makes
     * pushes idempotent — replaying the same UPSERT with the same id is a
     * no-op on the server. Null only on rows created before v6 that have
     * never been pushed; the push worker assigns it lazily on first push.
     *
     * Spec §5, §6, §7.
     */
    val cloudId: String? = null,

    /**
     * Per-row sync lifecycle state. See [SyncStatus] for transitions.
     * Default is [SyncStatus.LOCAL_ONLY] because scans land in an active
     * session and stay local until the user taps End Session. The
     * MIGRATION_5_6 backfill flips existing finalized rows to
     * [SyncStatus.DIRTY] so they push on first sign-in.
     */
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,

    /**
     * Epoch millis of the most recent local mutation. Used as the
     * tiebreaker in last-writer-wins conflict resolution (§11). Set by
     * every code path that mutates the row; the cloud trigger on the
     * mirrored Postgres row also stamps `updated_at`, and the merge
     * compares the two.
     */
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * Epoch millis of the last successful push to cloud. Null until the
     * row has ever been pushed.
     */
    val syncedAt: Long? = null,

    /**
     * Short, user-presentable error string from the last failed push.
     * Null when the row is in any state other than [SyncStatus.SYNC_ERROR].
     * Surfaced verbatim in the Settings → Sync diagnostics screen so the
     * owner can copy/paste it into a bug report.
     */
    val lastSyncError: String? = null,

    /**
     * Tombstone marker. Null while the row is alive; epoch millis of the
     * delete request once the user has removed the row from history.
     * Sync pushes the tombstone to cloud (PATCH `deleted_at`), which
     * cascades to other devices via §11. We never hard-delete the local
     * row until after the tombstone is acknowledged by cloud, otherwise
     * we'd lose the cloudId and the deletion wouldn't propagate.
     */
    val deletedAt: Long? = null,

    /** See [ScanSession.retryCount]. */
    val retryCount: Int = 0,

    /**
     * Cloud devices.id of whoever last wrote this row. Phones stamp own
     * deviceCloudId on every push; the portal leaves this NULL so the
     * merge attribution code can render "edited by Portal" badges
     * (SyncRepository.mergeRdNumbers line ~1043-1047). Added in v9 to
     * close the wire-vs-local-storage symmetry gap: pre-v9, the field
     * existed in RdNumberDto + cloud schema but had no Room column, so
     * pulls couldn't persist the attribution and the badge stayed
     * blank after the next process restart. v9 MIGRATION_8_9 adds the
     * column with default null so existing rows keep deserialising.
     */
    val lastEditorDeviceId: String? = null
) {
    companion object {
        const val MONTHS_MIN = 1
        const val MONTHS_MAX = 36
        const val MONTHS_DEFAULT = 1
    }
}
