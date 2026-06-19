package com.qrscanner.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Access to the [SyncEvent] log that feeds the in-app banner.
 *
 * Reads filter by a `since` cursor (typically
 * [DeviceSettings.lastBannerSeenAt]) and cap at 20 rows — the banner
 * renders at most 3 lines and the extra headroom lets the aggregator
 * coalesce same-origin events.
 *
 * Spec reference: §15.5.5.
 */
@Dao
interface SyncEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: SyncEvent): Long

    @Query("SELECT * FROM sync_events WHERE occurredAt > :since ORDER BY occurredAt DESC LIMIT :limit")
    suspend fun getEventsSince(since: Long, limit: Int = 20): List<SyncEvent>

    @Query("SELECT * FROM sync_events WHERE occurredAt > :since ORDER BY occurredAt DESC LIMIT :limit")
    fun observeEventsSince(since: Long, limit: Int = 20): Flow<List<SyncEvent>>

    @Query("SELECT COUNT(*) FROM sync_events WHERE occurredAt > :since")
    suspend fun countSince(since: Long): Int

    /**
     * Bounded retention. Called by a periodic worker; keeps at most
     * [keepCount] most-recent rows and drops anything older than
     * [olderThan] millis regardless of count.
     */
    @Query(
        """
        DELETE FROM sync_events
        WHERE id NOT IN (
            SELECT id FROM sync_events ORDER BY occurredAt DESC LIMIT :keepCount
        )
        OR occurredAt < :olderThan
        """
    )
    suspend fun pruneOldEvents(keepCount: Int, olderThan: Long)
}
