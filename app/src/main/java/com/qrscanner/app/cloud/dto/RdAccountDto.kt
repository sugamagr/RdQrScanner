package com.qrscanner.app.cloud.dto

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
 * Source enum is sent as plain text ("MANUAL" | "CSV") with a
 * CHECK constraint server-side. Adding a value requires a coordinated
 * Room migration + cloud schema patch.
 */
@Serializable
data class RdAccountDto(
    @Transient val id: String = "",
    @SerialName("owner_id") val ownerId: String,
    @SerialName("rd_number") val rdNumber: String,
    val name: String,
    @SerialName("monthly_amount") val monthlyAmount: Int,
    @SerialName("last_paid_through") val lastPaidThrough: String? = null,
    val source: String,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("account_opened_date") val accountOpenedDate: String? = null,
    @SerialName("account_closing_date") val accountClosingDate: String? = null,
    @SerialName("last_editor_device_id") val lastEditorDeviceId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)
