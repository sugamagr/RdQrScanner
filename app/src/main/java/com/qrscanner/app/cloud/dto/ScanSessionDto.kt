package com.qrscanner.app.cloud.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire representation of the `scan_sessions` row. Mirrors spec §5.
 *
 * Local-only columns (`isActive`, `activeLotId`) are absent because
 * active sessions never sync (spec §10). The denormalized aggregates
 * (`total_lots`, `total_rd_numbers`, `default_count`) are computed by
 * the phone at finalize time so the portal session list renders
 * without joining every detail row.
 *
 * EncodeDefault on every defaulted field — see RdNumberDto KDoc for the
 * full rationale (bug class #3). Concrete trap: a session with exactly
 * 0 defaulters has defaultCount=0 matching the default, the field is
 * omitted from the upsert, and the cloud preserves any prior non-zero
 * count from a partial earlier push.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScanSessionDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_id") val deviceId: String,
    @EncodeDefault @SerialName("operator_name") val operatorName: String? = null,
    @SerialName("display_number") val displayNumber: Int,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("total_lots") val totalLots: Int,
    @SerialName("total_rd_numbers") val totalRdNumbers: Int,
    @EncodeDefault @SerialName("default_count") val defaultCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @EncodeDefault @SerialName("deleted_at") val deletedAt: String? = null
)
