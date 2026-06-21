package com.qrscanner.app.cloud.mappers

import com.qrscanner.app.cloud.dto.RdAccountDto
import com.qrscanner.app.data.AccountSource
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.data.SyncStatus

/**
 * Local [RdAccount] ↔ wire [RdAccountDto] conversion. Mapper is
 * stateless. The caller (SyncRepository) owns ownerId injection on
 * outbound — toDto leaves `ownerId = ""` and the repository fills it
 * from the current session, matching the pattern used by every other
 * mapper in this package.
 */
internal object RdAccountMapper {

    fun toDto(account: RdAccount, editorDeviceCloudId: String?): RdAccountDto {
        return RdAccountDto(
            id = account.rdNumber,
            ownerId = "", // filled in by SyncRepository
            rdNumber = account.rdNumber,
            name = account.name,
            monthlyAmount = account.monthlyAmount,
            lastPaidThrough = account.lastPaidThrough,
            source = account.source.name,
            isActive = account.isActive,
            accountOpenedDate = account.accountOpenedDate,
            accountClosingDate = account.accountClosingDate,
            lastEditorDeviceId = editorDeviceCloudId,
            createdAt = IsoTime.fromEpochMillis(account.updatedAt),
            updatedAt = IsoTime.fromEpochMillis(account.updatedAt),
            deletedAt = IsoTime.fromEpochMillisOrNull(account.deletedAt)
        )
    }

    fun toEntity(dto: RdAccountDto): RdAccount =
        RdAccount(
            rdNumber = dto.rdNumber,
            name = dto.name,
            monthlyAmount = dto.monthlyAmount,
            lastPaidThrough = dto.lastPaidThrough,
            source = parseSource(dto.source),
            isActive = dto.isActive,
            accountOpenedDate = dto.accountOpenedDate,
            accountClosingDate = dto.accountClosingDate,
            ownerId = dto.ownerId,
            cloudId = dto.rdNumber,
            syncStatus = SyncStatus.SYNCED,
            updatedAt = IsoTime.toEpochMillis(dto.updatedAt),
            syncedAt = IsoTime.toEpochMillis(dto.updatedAt),
            lastSyncError = null,
            deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt),
            retryCount = 0,
            lastEditorDeviceId = dto.lastEditorDeviceId
        )

    /**
     * Defensive parse: a malformed source string from cloud (would
     * indicate a schema CHECK constraint failure or manual DB edit)
     * falls back to MANUAL rather than crashing the pull. Logged in
     * SyncRepository upstream.
     */
    private fun parseSource(raw: String): AccountSource = when (raw.uppercase()) {
        "CSV" -> AccountSource.CSV
        else -> AccountSource.MANUAL
    }
}
