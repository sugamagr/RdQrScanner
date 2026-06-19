package com.qrscanner.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding per-phone settings: owner account binding,
 * device identity in the cloud, current operator name, and pull-cursor
 * bookkeeping for delta sync.
 *
 * The `id` column is constrained to 1 via the table-level CHECK in
 * [com.qrscanner.app.data.AppDatabase.MIGRATION_5_6], so this entity is
 * effectively a key/value blob — there is always exactly zero or one
 * row. Convention: every read goes through [DeviceSettingsDao.get] which
 * returns null when the row hasn't been inserted yet (fresh install,
 * never signed in).
 *
 * Spec reference: §6 device_settings, §9 auth & identity.
 */
@Entity(tableName = "device_settings")
data class DeviceSettings(
    @PrimaryKey
    val id: Int = SINGLETON_ID,

    /**
     * The UUID this phone uses as its identity in the cloud `devices`
     * table. Generated on first sign-in via `UUID.randomUUID()` and never
     * changes for the lifetime of the install. Null until first sign-in
     * completes.
     */
    val deviceCloudId: String? = null,

    /**
     * User-chosen friendly name for this phone, e.g. "Counter Phone".
     * Surfaced on the portal so the owner can identify which phone
     * scanned each session. Captured in [FirstRunSetupScreen]; editable
     * later from Settings.
     */
    val deviceName: String? = null,

    /**
     * Current operator working this phone, e.g. "Ravi". Captured at
     * first run and editable via the "Switch operator" action. Stamped
     * onto every finalized session at finalize time.
     */
    val operatorName: String? = null,

    /**
     * Supabase auth.users.id of the signed-in owner account. Null while
     * the SignInScreen is the root composable; non-null after a
     * successful sign-in. Cleared on sign-out (the local data cache is
     * preserved so a re-sign-in is instant).
     */
    val ownerId: String? = null,

    /**
     * High-water mark for the pull cursor — the most-recent
     * `updated_at` value (in epoch millis) the pull worker has
     * acknowledged from the cloud. The next pull queries for rows with
     * `updated_at > lastPulledAt OR deleted_at > lastPulledAt`. Zero on
     * fresh install means "pull everything."
     */
    val lastPulledAt: Long = 0L,

    /** Epoch millis of the last failed pull, null if the most recent attempt succeeded. */
    val lastPullErrorAt: Long? = null,

    /** Short, user-presentable failure message for the last pull error. */
    val lastPullError: String? = null,

    /**
     * Epoch millis the in-app "recent changes" banner was last
     * dismissed by the user. The banner composer reads
     * [com.qrscanner.app.data.SyncEvent] rows since this watermark to
     * decide whether to render.
     */
    val lastBannerSeenAt: Long = 0L
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
