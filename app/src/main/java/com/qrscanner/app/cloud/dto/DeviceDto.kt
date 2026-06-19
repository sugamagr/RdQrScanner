package com.qrscanner.app.cloud.dto

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
 */
@Serializable
data class DeviceDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_seen_at") val lastSeenAt: String,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)
