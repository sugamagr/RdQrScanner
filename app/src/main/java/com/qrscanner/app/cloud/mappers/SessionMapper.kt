package com.qrscanner.app.cloud.mappers

import com.qrscanner.app.cloud.dto.ScanSessionDto
import com.qrscanner.app.data.ScanSession
import com.qrscanner.app.data.SyncStatus

/**
 * Local [ScanSession] ↔ wire [ScanSessionDto] conversion.
 *
 * Outbound: a session is only ever pushed when it's finalized
 * (isActive=false and endTime!=null), and the entity invariants
 * guarantee deviceCloudId / operatorName / cloudId / displayNumber are
 * non-null at that point. The mapper enforces those preconditions with
 * requireNotNull so a malformed push attempt blows up locally instead
 * of producing a half-baked Postgres row.
 *
 * Inbound: cloudId becomes the local key for upsert, and the local Long
 * PK is left at 0 — Room will assign it on insert. Local
 * `deviceCloudId` carries the server `device_id`; existing rows match
 * by `cloudId` and the upsert merge keeps the local Long PK stable.
 */
internal object SessionMapper {

    fun toDto(session: ScanSession): ScanSessionDto {
        val cloudId = requireNotNull(session.cloudId) {
            "ScanSession.cloudId must be set before pushing (id=${session.id})"
        }
        val deviceCloudId = requireNotNull(session.deviceCloudId) {
            "ScanSession.deviceCloudId must be set before pushing (id=${session.id})"
        }
        val endTime = requireNotNull(session.endTime) {
            "ScanSession.endTime must be set before pushing — active sessions never sync (id=${session.id})"
        }
        return ScanSessionDto(
            id = cloudId,
            ownerId = "", // filled in by SyncRepository from the current auth session
            deviceId = deviceCloudId,
            operatorName = session.operatorName,
            displayNumber = session.displayNumber,
            startTime = IsoTime.fromEpochMillis(session.startTime),
            endTime = IsoTime.fromEpochMillis(endTime),
            totalLots = session.totalLots,
            totalRdNumbers = session.totalRdNumbers,
            defaultCount = 0, // computed by SyncRepository before push (joins LOTs/RD numbers)
            createdAt = IsoTime.fromEpochMillis(session.startTime),
            updatedAt = IsoTime.fromEpochMillis(session.updatedAt),
            deletedAt = IsoTime.fromEpochMillisOrNull(session.deletedAt)
        )
    }

    /**
     * Builds a local entity from an inbound DTO. The caller (the pull
     * merge in SyncRepository) decides whether this entity is a fresh
     * insert or merges into an existing row matched by cloudId.
     */
    fun toEntity(dto: ScanSessionDto): ScanSession =
        ScanSession(
            id = 0,
            startTime = IsoTime.toEpochMillis(dto.startTime),
            endTime = IsoTime.toEpochMillis(dto.endTime),
            isActive = false,
            totalLots = dto.totalLots,
            totalRdNumbers = dto.totalRdNumbers,
            displayNumber = dto.displayNumber,
            activeLotId = null,
            deviceCloudId = dto.deviceId,
            operatorName = dto.operatorName,
            cloudId = dto.id,
            syncStatus = SyncStatus.SYNCED,
            updatedAt = IsoTime.toEpochMillis(dto.updatedAt),
            syncedAt = IsoTime.toEpochMillis(dto.updatedAt),
            lastSyncError = null,
            deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
        )
}
