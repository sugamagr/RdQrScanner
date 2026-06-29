package com.qrscanner.app.cloud.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire representation of the `devices` row in Supabase. Mirrors the
 * cloud schema at spec §5; column names use `snake_case` via [SerialName].
 *
 * Timestamps cross the wire as ISO-8601 strings (Postgres `timestamptz`
 * default rendering). The mapper layer
 * [com.qrscanner.app.cloud.mappers] converts to/from epoch millis used
 * by Room.
 *
 * Convention for nullables: cloud schema NOT NULL columns have non-null
 * Kotlin types; nullable columns have Kotlin `?`. The mapper enforces
 * the entity invariants.
 *
 * EncodeDefault on the two nullable defaulted fields (deviceModel,
 * appVersion) — see RdNumberDto KDoc for the bug class #3 rationale.
 * Lower impact than other DTOs because device model + version are set
 * once at registration and never edited; still annotated for the same
 * defense in case a future code path ever clears one.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class DeviceDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_name") val deviceName: String,
    @EncodeDefault @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @EncodeDefault @SerialName("app_version") val appVersion: String? = null,
    // Diagnostics columns (cloud schema v11) — populated from runPush()
    // exit so the portal reflects the same truth the in-app sync pill
    // shows. EncodeDefault forces all three onto every wire payload so
    // a clean cycle clears a stale error / pending count instead of
    // letting Supabase ignore the omitted nullable field.
    @EncodeDefault @SerialName("last_sync_error") val lastSyncError: String? = null,
    @EncodeDefault @SerialName("pending_count") val pendingCount: Int = 0,
    @EncodeDefault @SerialName("last_push_at") val lastPushAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)
