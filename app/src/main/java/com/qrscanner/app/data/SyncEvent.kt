package com.qrscanner.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bounded log of remote-origin sync events that feed the in-app
 * "recent changes since last open" banner on Home (spec §15.5.5).
 *
 * Rows are appended by [com.qrscanner.app.data.sync.SyncRepository]
 * after a pull / realtime payload is merged. The banner composer reads
 * events newer than [DeviceSettings.lastBannerSeenAt], aggregates them
 * into 1–3 lines per the 60-second rule in §15.5.1, and renders.
 *
 * Bounded retention: a periodic worker prunes rows older than 7 days or
 * beyond the 100 most-recent, whichever is smaller. The cap exists so
 * an offline phone catching up after a long absence doesn't materialize
 * thousands of irrelevant banner entries.
 */
@Entity(
    tableName = "sync_events",
    indices = [Index(value = ["occurredAt"])]
)
data class SyncEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Epoch millis when the event happened. Prefer the cloud row's
     * `updated_at` over the local merge time so cross-device ordering
     * is stable (clock skew aside).
     */
    val occurredAt: Long,

    @ColumnInfo(name = "type")
    val type: SyncEventType,

    /** Cloud UUID of the affected session, when applicable. */
    val sessionCloudId: String? = null,

    /** Cloud UUID of the affected rd_number, when applicable. */
    val rdNumberCloudId: String? = null,

    /**
     * Cloud UUID of the device that originated the change. Null when
     * the source is the portal (which has no device row) — the banner
     * uses null-device → "Owner edited …" copy.
     */
    val originDeviceCloudId: String? = null,

    /**
     * Friendly name of the origin device, denormalized at record-time
     * so the banner doesn't have to join through `devices` on render.
     */
    val originDeviceName: String? = null,

    /**
     * Operator name carried on the originating session. Denormalized for
     * the same reason as [originDeviceName].
     */
    val originOperatorName: String? = null,

    /**
     * Pre-rendered short summary used as the banner body fragment, e.g.
     * "marked 2 defaulters" or "finalized Session #47 (12 LOTs)". The
     * banner composer concatenates several of these with the §15.5.1
     * aggregation rule.
     */
    val payloadSummary: String
)

/**
 * Type discriminator for [SyncEvent]. Drives both banner copy
 * selection and aggregation grouping (events of the same type from the
 * same origin within a 60-second window collapse into one line).
 *
 * Adding a new type is a non-breaking change because Room stores enum
 * values as TEXT; older builds reading an unknown enum value will
 * crash with `IllegalArgumentException`, so any new value must ship
 * after the consuming code knows how to render it.
 */
enum class SyncEventType {
    REMOTE_SESSION_FINALIZED,
    REMOTE_DEFAULTER_EDIT,
    PORTAL_DEFAULTER_EDIT,
    REMOTE_SESSION_DELETED
}
