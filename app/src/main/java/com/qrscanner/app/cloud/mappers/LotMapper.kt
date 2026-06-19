package com.qrscanner.app.cloud.mappers

import com.qrscanner.app.cloud.dto.ScanLotDto
import com.qrscanner.app.data.ScanLot
import com.qrscanner.app.data.SyncStatus

/**
 * Local [ScanLot] ↔ wire [ScanLotDto] conversion.
 *
 * Outbound: the caller (SyncRepository) must already have resolved the
 * parent session's `cloudId` and passes it in as [sessionCloudId] —
 * this mapper is intentionally stateless and never queries Room.
 *
 * Inbound: cloudId of the parent session arrives as `dto.sessionId`;
 * the repository resolves it to a local Long FK by looking up the
 * matching `scan_sessions.cloudId = dto.sessionId` row before insert.
 * The mapper leaves `sessionId = 0` for the caller to fill in.
 */
internal object LotMapper {

    fun toDto(lot: ScanLot, sessionCloudId: String): ScanLotDto {
        val cloudId = requireNotNull(lot.cloudId) {
            "ScanLot.cloudId must be set before pushing (id=${lot.id})"
        }
        return ScanLotDto(
            id = cloudId,
            ownerId = "", // filled in by SyncRepository
            sessionId = sessionCloudId,
            lotNumber = lot.lotNumber,
            timestamp = IsoTime.fromEpochMillis(lot.timestamp),
            createdAt = IsoTime.fromEpochMillis(lot.timestamp),
            updatedAt = IsoTime.fromEpochMillis(lot.updatedAt),
            deletedAt = IsoTime.fromEpochMillisOrNull(lot.deletedAt)
        )
    }

    /**
     * Builds a local entity from an inbound DTO. The `sessionId` FK is
     * left at 0 — the repository resolves the parent's local Long PK
     * before insert.
     */
    fun toEntity(dto: ScanLotDto): ScanLot =
        ScanLot(
            id = 0,
            sessionId = 0,
            lotNumber = dto.lotNumber,
            timestamp = IsoTime.toEpochMillis(dto.timestamp),
            cloudId = dto.id,
            syncStatus = SyncStatus.SYNCED,
            updatedAt = IsoTime.toEpochMillis(dto.updatedAt),
            syncedAt = IsoTime.toEpochMillis(dto.updatedAt),
            lastSyncError = null,
            deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
        )
}
