package com.qrscanner.app.cloud.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire representation of the `rd_numbers` row. Mirrors spec §5.
 *
 * FK `lot_id` carries the cloud UUID of the parent LOT. The `months_paid`
 * / `months_list` columns enforce the same invariant as local: when
 * `months_paid == 1` the list is null; when > 1 the list's token count
 * equals `months_paid`. The mapper validates on inbound and trusts on
 * outbound (the UI's [com.qrscanner.app.ui.components.DefaulterRowDraft]
 * is the gate that prevents bad writes).
 */
@Serializable
data class RdNumberDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("lot_id") val lotId: String,
    val number: String,
    val position: Int,
    @SerialName("scanned_at") val scannedAt: String,
    @SerialName("months_paid") val monthsPaid: Int,
    @SerialName("months_list") val monthsList: String? = null,
    /**
     * Phase 5 T5.6 (F9). Cloud devices.id of whoever last wrote this row.
     * Phones stamp own deviceId on every push; the portal leaves this NULL.
     * The merge attribution check at SyncRepository compares this value
     * against own deviceCloudId to distinguish own / other-phone / portal
     * edits. Nullable + default null so cloud rows from pre-T5.6 phones
     * keep deserializing.
     */
    @SerialName("last_editor_device_id") val lastEditorDeviceId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)
