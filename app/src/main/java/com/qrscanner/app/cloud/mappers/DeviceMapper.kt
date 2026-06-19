package com.qrscanner.app.cloud.mappers

import com.qrscanner.app.cloud.dto.DeviceDto
import com.qrscanner.app.data.DeviceSettings

/**
 * Local [DeviceSettings] ↔ wire [DeviceDto] conversion.
 *
 * DeviceSettings is the single-row local table representing 'this phone';
 * DeviceDto is the row representing 'this phone' on the server. The
 * mapper handles two awkward facts:
 *
 * 1. DeviceSettings has many fields the cloud doesn't care about
 *    (pull cursor, banner watermark) and vice versa (created_at,
 *    first_seen_at). Each direction only carries the fields the other
 *    side actually uses.
 *
 * 2. The cloud-side `id` corresponds to local `deviceCloudId`, not local
 *    `id` (which is constrained to 1). The mapper translates explicitly
 *    so the call sites can't get the two confused.
 */
internal object DeviceMapper {

    fun toDto(
        settings: DeviceSettings,
        ownerId: String,
        deviceModel: String?,
        appVersion: String?,
        firstSeenAt: Long,
        lastSeenAt: Long,
        createdAt: Long,
        updatedAt: Long
    ): DeviceDto {
        val cloudId = requireNotNull(settings.deviceCloudId) {
            "DeviceSettings.deviceCloudId must be set before pushing to cloud"
        }
        val deviceName = requireNotNull(settings.deviceName) {
            "DeviceSettings.deviceName must be set before pushing to cloud"
        }
        return DeviceDto(
            id = cloudId,
            ownerId = ownerId,
            deviceName = deviceName,
            deviceModel = deviceModel,
            firstSeenAt = IsoTime.fromEpochMillis(firstSeenAt),
            lastSeenAt = IsoTime.fromEpochMillis(lastSeenAt),
            appVersion = appVersion,
            createdAt = IsoTime.fromEpochMillis(createdAt),
            updatedAt = IsoTime.fromEpochMillis(updatedAt)
        )
    }
}
