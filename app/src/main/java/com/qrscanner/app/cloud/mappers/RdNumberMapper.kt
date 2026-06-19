package com.qrscanner.app.cloud.mappers

import com.qrscanner.app.cloud.dto.RdNumberDto
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.data.SyncStatus

/**
 * Local [RdNumber] ↔ wire [RdNumberDto] conversion.
 *
 * Outbound: caller passes the parent LOT's `cloudId` as [lotCloudId].
 * Mapper is stateless.
 *
 * Inbound: `lotId` arrives as a cloud UUID; the repository resolves it
 * to a local Long FK before insert. The mapper preserves the
 * monthsPaid/monthsList invariant — `MonthYear.resolveOrAuto` handles
 * the render-side fallback for malformed inputs, so the mapper does no
 * extra validation here.
 */
internal object RdNumberMapper {

    fun toDto(rdNumber: RdNumber, lotCloudId: String): RdNumberDto {
        val cloudId = requireNotNull(rdNumber.cloudId) {
            "RdNumber.cloudId must be set before pushing (id=${rdNumber.id})"
        }
        return RdNumberDto(
            id = cloudId,
            ownerId = "", // filled in by SyncRepository
            lotId = lotCloudId,
            number = rdNumber.number,
            position = rdNumber.position,
            scannedAt = IsoTime.fromEpochMillis(rdNumber.scannedAt),
            monthsPaid = rdNumber.monthsPaid,
            monthsList = rdNumber.monthsList,
            createdAt = IsoTime.fromEpochMillis(rdNumber.scannedAt),
            updatedAt = IsoTime.fromEpochMillis(rdNumber.updatedAt),
            deletedAt = IsoTime.fromEpochMillisOrNull(rdNumber.deletedAt)
        )
    }

    fun toEntity(dto: RdNumberDto): RdNumber =
        RdNumber(
            id = 0,
            lotId = 0,
            number = dto.number,
            position = dto.position,
            scannedAt = IsoTime.toEpochMillis(dto.scannedAt),
            monthsPaid = dto.monthsPaid,
            monthsList = dto.monthsList,
            cloudId = dto.id,
            syncStatus = SyncStatus.SYNCED,
            updatedAt = IsoTime.toEpochMillis(dto.updatedAt),
            syncedAt = IsoTime.toEpochMillis(dto.updatedAt),
            lastSyncError = null,
            deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
        )
}
