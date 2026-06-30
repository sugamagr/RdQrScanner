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

    fun toDto(rdNumber: RdNumber, lotCloudId: String, editorDeviceCloudId: String?): RdNumberDto {
        val cloudId = requireNotNull(rdNumber.cloudId) {
            "RdNumber.cloudId must be set before pushing (id=${rdNumber.id})"
        }
        // Defense-in-depth: cloud schema enforces CHECK (months_paid BETWEEN 1 AND 36).
        // If a buggy local migration or in-memory mutation produces 0 or 37, the push
        // would silently fail with PostgrestException 23514 and the row would
        // be retried until SYNC_ABANDONED (8 retries × WorkManager backoff = ~hours
        // of wasted bandwidth + an opaque sync-error pill for the operator).
        // Fail fast here with a precise message so the bug is caught at the seam.
        require(rdNumber.monthsPaid in 1..36) {
            "RdNumber.monthsPaid must be in 1..36, got ${rdNumber.monthsPaid} for cloudId=$cloudId"
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
            lastEditorDeviceId = editorDeviceCloudId,
            createdAt = IsoTime.fromEpochMillis(rdNumber.scannedAt),
            updatedAt = IsoTime.fromEpochMillis(rdNumber.updatedAt),
            deletedAt = IsoTime.fromEpochMillisOrNull(rdNumber.deletedAt)
        )
    }

    fun toEntity(dto: RdNumberDto): RdNumber {
        // Inbound symmetry with toDto(): catch corrupt cloud rows (or future
        // schema drift) at the seam instead of silently violating the local
        // Room invariant — RdNumber.monthsPaid is consumed by MonthBar UI and
        // by next_display_number style aggregations that assume 1..36 bounds.
        require(dto.monthsPaid in 1..36) {
            "RdNumberDto.monthsPaid must be in 1..36, got ${dto.monthsPaid} for cloudId=${dto.id}"
        }
        return RdNumber(
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
            deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt),
            lastEditorDeviceId = dto.lastEditorDeviceId
        )
    }
}
