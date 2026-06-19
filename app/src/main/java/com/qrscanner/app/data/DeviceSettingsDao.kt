package com.qrscanner.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to the single-row [DeviceSettings] table.
 *
 * Every method targets the row at [DeviceSettings.SINGLETON_ID] (= 1).
 * The migration in v5→v6 seeds an empty row, so [get] and [observe]
 * always have something to return after first run. Mutations use
 * targeted UPDATE so we never accidentally insert a duplicate at a
 * different id.
 *
 * Spec reference: §6 device_settings, §9 auth flow, §15.5 banner.
 */
@Dao
interface DeviceSettingsDao {

    /** One-shot read. Null only on a fresh DB before MIGRATION_5_6's seed runs. */
    @Query("SELECT * FROM device_settings WHERE id = 1")
    suspend fun get(): DeviceSettings?

    /** Reactive read used by Compose. Emits null until the seed row exists. */
    @Query("SELECT * FROM device_settings WHERE id = 1")
    fun observe(): Flow<DeviceSettings?>

    /**
     * Upsert the singleton row. Used by [SyncRepository] during first-run
     * setup and on operator switches. Always writes id = 1.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: DeviceSettings)

    /**
     * Updates only the identity columns used by the auth flow. Leaves
     * pull-cursor and banner watermarks untouched so concurrent syncs
     * don't clobber them.
     */
    @Query(
        """
        UPDATE device_settings
        SET deviceCloudId = :deviceCloudId,
            deviceName = :deviceName,
            operatorName = :operatorName,
            ownerId = :ownerId
        WHERE id = 1
        """
    )
    suspend fun updateIdentity(
        deviceCloudId: String?,
        deviceName: String?,
        operatorName: String?,
        ownerId: String?
    )

    @Query("UPDATE device_settings SET operatorName = :operatorName WHERE id = 1")
    suspend fun updateOperatorName(operatorName: String)

    /**
     * Clears the entire device identity block (owner + cloudId + name +
     * operator). Called by SettingsScreen sign-out. Critical that this
     * clears all four fields, not just ownerId — otherwise the next
     * sign-in on a DIFFERENT owner account would inherit the previous
     * owner's deviceCloudId and skip first-run setup, breaking RLS
     * assumptions and producing cross-account device row pollution
     * (oracle round 6 BLOCKER #10).
     */
    @Query(
        """
        UPDATE device_settings
        SET ownerId = NULL,
            deviceCloudId = NULL,
            deviceName = NULL,
            operatorName = NULL
        WHERE id = 1
        """
    )
    suspend fun clearOwner()

    @Query(
        """
        UPDATE device_settings
        SET lastPulledAt = :timestamp,
            lastPullErrorAt = NULL,
            lastPullError = NULL
        WHERE id = 1
        """
    )
    suspend fun updateLastPulledAt(timestamp: Long)

    @Query(
        """
        UPDATE device_settings
        SET lastPullErrorAt = :timestamp, lastPullError = :error
        WHERE id = 1
        """
    )
    suspend fun recordPullError(timestamp: Long, error: String?)

    @Query("UPDATE device_settings SET lastBannerSeenAt = :timestamp WHERE id = 1")
    suspend fun updateLastBannerSeenAt(timestamp: Long)
}
