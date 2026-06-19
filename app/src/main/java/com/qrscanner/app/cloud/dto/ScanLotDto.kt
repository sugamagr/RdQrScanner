package com.qrscanner.app.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire representation of the `scan_lots` row. Mirrors spec §5.
 *
 * FK `session_id` carries the cloud UUID of the parent session — the
 * local Long FK is resolved to the parent's `cloudId` at push time.
 */
@Serializable
data class ScanLotDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("lot_number") val lotNumber: Int,
    val timestamp: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)
