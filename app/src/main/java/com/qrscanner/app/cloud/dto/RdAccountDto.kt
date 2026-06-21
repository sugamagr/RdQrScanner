package com.qrscanner.app.cloud.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Wire representation of the `rd_accounts` row. Mirrors spec §17
 * (Account profiles).
 *
 * Identity model differs from [RdNumberDto]: the cloud primary key is
 * the composite (owner_id, rd_number), not a generated UUID. The
 * cloud table has NO `id` column — sending one returns PGRST204 from
 * PostgREST. The [id] field below is therefore @Transient: it lives
 * only in-process so the pull-merge code can pass a client cloudId
 * marker through the mapper without a wire payload. On pull the DTO
 * is constructed with id = rdNumber (the natural key) inside
 * SyncRepository.mergeRdAccounts.
 *
 * EncodeDefault is REQUIRED on every defaulted field. supabase-kt's
 * default Json config drops fields whose value equals the default,
 * which on an upsert means PostgREST silently preserves the prior
 * cloud value for that column. Concrete failure mode: toggle Active
 * on for a previously-inactive row -> isActive=true matches default
 * -> field omitted from payload -> cloud is_active stays false ->
 * realtime echo -> local UI reverts. Same trap for last_paid_through,
 * deleted_at, etc when an edit clears them to null. Forcing ALWAYS
 * makes the upsert truly idempotent over the full row state.
 *
 * Source enum is sent as plain text ("MANUAL" | "CSV") with a
 * CHECK constraint server-side. Adding a value requires a coordinated
 * Room migration + cloud schema patch.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RdAccountDto(
    @Transient val id: String = "",
    @SerialName("owner_id") val ownerId: String,
    @SerialName("rd_number") val rdNumber: String,
    val name: String,
    @SerialName("monthly_amount") val monthlyAmount: Int,
    @EncodeDefault @SerialName("last_paid_through") val lastPaidThrough: String? = null,
    val source: String,
    @EncodeDefault @SerialName("is_active") val isActive: Boolean = true,
    @EncodeDefault @SerialName("account_opened_date") val accountOpenedDate: String? = null,
    @EncodeDefault @SerialName("account_closing_date") val accountClosingDate: String? = null,
    @EncodeDefault @SerialName("last_editor_device_id") val lastEditorDeviceId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @EncodeDefault @SerialName("deleted_at") val deletedAt: String? = null
)
