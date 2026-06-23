package com.qrscanner.app.cloud.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire representation of the `rd_numbers` row. Mirrors spec §5.
 *
 * FK `lot_id` carries the cloud UUID of the parent LOT. The `months_paid`
 * / `months_list` columns enforce the same invariant as local: when
 * `months_paid == 1` the list is null; when > 1 the list's token count
 * equals `months_paid`. The mapper validates on inbound and trusts on
 * outbound (the UI's [com.qrscanner.app.ui.screens.LotReviewRow] is the
 * gate that prevents bad writes).
 *
 * EncodeDefault on every defaulted field: supabase-kt's default Json
 * config drops fields whose value equals the default. On an upsert that
 * means PostgREST silently preserves the prior cloud value for that
 * column. Concrete failure mode: clear monthsList on a defaulter that
 * resets to "paid for current month only" -> monthsList=null matches
 * default -> field omitted -> cloud keeps stale list. Same trap for
 * lastEditorDeviceId and deletedAt (resurrect a tombstoned row from
 * phone? deletedAt=null gets dropped, row stays tombstoned everywhere
 * else). Bug class #3 from the rd_accounts audit applies to every DTO
 * with defaulted fields.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RdNumberDto(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("lot_id") val lotId: String,
    val number: String,
    val position: Int,
    @SerialName("scanned_at") val scannedAt: String,
    @SerialName("months_paid") val monthsPaid: Int,
    @EncodeDefault @SerialName("months_list") val monthsList: String? = null,
    @EncodeDefault @SerialName("last_editor_device_id") val lastEditorDeviceId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @EncodeDefault @SerialName("deleted_at") val deletedAt: String? = null
)
