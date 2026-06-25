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

    /** Sign-out wipe — see ScanSessionDao.deleteAll() for contract. */
    @Query("DELETE FROM sync_events")
    suspend fun deleteAll()

    // Secondary ORDER BY id DESC stabilizes ordering for events with
    // identical occurredAt timestamps. Without it, a bulk insert that
    // lands 50 events in the same millisecond (CSV upload, multi-row
    // merge) has undefined display order — the operator sees
    // "Account Z added" before "Account A added" inconsistently across
    // recompositions. Room's autoGenerate id is monotonic per insert so
    // it's a stable proxy for insertion order within the same ms.

    @Query("SELECT * FROM sync_events WHERE occurredAt > :since ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    suspend fun getEventsSince(since: Long, limit: Int = 20): List<SyncEvent>

    @Query("SELECT * FROM sync_events WHERE occurredAt > :since ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeEventsSince(since: Long, limit: Int = 20): Flow<List<SyncEvent>>

    @Query("SELECT COUNT(*) FROM sync_events WHERE occurredAt > :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT * FROM sync_events ORDER BY occurredAt DESC, id DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int = 100): Flow<List<SyncEvent>>

    /**
     * Bounded retention. Called by a periodic worker; keeps at most
     * [keepCount] most-recent rows AND drops anything older than
     * [olderThan] millis. Both conditions must apply for deletion —
     * the AND (not OR) preserves recent rows even when the keepCount
     * window has shifted past them within the age window, and also
     * preserves age-young rows that fell outside the keepCount window
     * during a burst. Either interpretation alone would over-delete.
     */
    @Query(
        """
        DELETE FROM sync_events
        WHERE id NOT IN (
            SELECT id FROM sync_events ORDER BY occurredAt DESC LIMIT :keepCount
        )
        AND occurredAt < :olderThan
        """
    )
    suspend fun pruneOldEvents(keepCount: Int, olderThan: Long)
}
