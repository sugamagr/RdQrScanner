package com.qrscanner.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    
    // Only get completed sessions with actual data, ordered by displayNumber.
    // deletedAt IS NULL excludes soft-deleted sessions so they disappear from
    // history the instant the user taps delete (Phase 2 T2.6).
    @Query("SELECT * FROM scan_sessions WHERE isActive = 0 AND totalLots > 0 AND deletedAt IS NULL ORDER BY displayNumber DESC")
    fun getCompletedSessions(): Flow<List<ScanSession>>

    @Query("SELECT * FROM scan_sessions WHERE isActive = 1 AND deletedAt IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): ScanSession?

    @Query("SELECT id FROM scan_sessions WHERE isActive = 1 AND deletedAt IS NULL ORDER BY startTime DESC")
    suspend fun getAllActiveSessionIds(): List<Long>

    @Query("SELECT * FROM scan_sessions WHERE id = :id AND deletedAt IS NULL")
    suspend fun getSessionById(id: Long): ScanSession?

    /**
     * Observe a single session by local id. Emits null after a soft-
     * delete from another device arrives via pull (deletedAt set) — the
     * detail screen subscribes to detect mid-edit tombstones and bounce
     * the user back to History. Phase 5 T5.5 (F7 finding).
     */
    @Query("SELECT * FROM scan_sessions WHERE id = :id AND deletedAt IS NULL")
    fun observeSessionById(id: Long): Flow<ScanSession?>

    
    // Get the next sequential display number
    @Query("SELECT COALESCE(MAX(displayNumber), 0) + 1 FROM scan_sessions WHERE isActive = 0 AND totalLots > 0")
    suspend fun getNextDisplayNumber(): Int
    
    @Insert
    suspend fun insert(session: ScanSession): Long

    @Update
    suspend fun update(session: ScanSession)

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    // End session with display number
    @Query("UPDATE scan_sessions SET isActive = 0, endTime = :endTime, totalLots = :totalLots, totalRdNumbers = :totalRdNumbers, displayNumber = :displayNumber, activeLotId = NULL WHERE id = :id")
    suspend fun endSession(id: Long, endTime: Long, totalLots: Int, totalRdNumbers: Int, displayNumber: Int)

    @Query("UPDATE scan_sessions SET activeLotId = :lotId WHERE id = :sessionId")
    suspend fun setActiveLotId(sessionId: Long, lotId: Long?)

    @Query("""
        SELECT sl.sessionId AS sessionId, COUNT(rn.id) AS count
        FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE rn.monthsPaid > 1 AND rn.deletedAt IS NULL
        GROUP BY sl.sessionId
    """)
    fun getDefaultCountsBySession(): Flow<List<SessionDefaultCount>>

    // ── Sync push helpers (Phase 2 T2.1a) ────────────────────────────────

    @Query("SELECT * FROM scan_sessions WHERE syncStatus IN ('DIRTY','SYNC_ERROR') AND isActive = 0 ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getDirtyForPush(limit: Int = 500): List<ScanSession>

    @Query("UPDATE scan_sessions SET syncStatus = 'SYNCING' WHERE id = :id AND syncStatus IN ('DIRTY','SYNC_ERROR')")
    suspend fun markSyncing(id: Long)

    /**
     * Persists the client-generated cloudId BEFORE the cloud upsert
     * (oracle bg_0ea195ce R2 / I4). Previously cloudId was only
     * persisted in [markSynced] after the network call returned, so a
     * mid-call network failure lost the id; next push regenerated a
     * fresh UUID, and if the original upsert had actually succeeded
     * server-side the next push created a DUPLICATE cloud row.
     *
     * Idempotent COALESCE prevents stomping an already-persisted id.
     */
    @Query("UPDATE scan_sessions SET cloudId = COALESCE(cloudId, :cloudId) WHERE id = :id")
    suspend fun stampCloudId(id: Long, cloudId: String)

    @Query("UPDATE scan_sessions SET syncStatus = 'SYNCED', syncedAt = :syncedAt, updatedAt = :cloudUpdatedAt, cloudId = COALESCE(cloudId, :cloudId), lastSyncError = NULL, retryCount = 0 WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, cloudUpdatedAt: Long, cloudId: String)

    @Query("UPDATE scan_sessions SET syncStatus = 'SYNC_ERROR', lastSyncError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String)

    /**
     * Circuit breaker (oracle R3): a row whose push has failed
     * [SyncRepository.PUSH_ABANDON_THRESHOLD] times is structurally
     * unpushable (cloud schema drift, FK constraint we can't satisfy,
     * etc.). Flip it to [SyncStatus.SYNC_ABANDONED] so it stops being
     * counted as pending, stops being re-promoted, and stops being
     * retried until a user clears it manually from the diagnostics screen.
     */
    @Query("UPDATE scan_sessions SET syncStatus = 'SYNC_ABANDONED' WHERE id = :id")
    suspend fun markSyncAbandoned(id: Long)

    /**
     * Reverts any session left in SYNCING after a worker was killed
     * mid-push back to DIRTY so the next push run picks it up. Without
     * this sweep the row is invisible to [getDirtyForPush] and frozen
     * forever. Called as the first DAO interaction of [SyncRepository.runPush].
     */
    @Query("UPDATE scan_sessions SET syncStatus = 'DIRTY' WHERE syncStatus = 'SYNCING'")
    suspend fun recoverStuckSyncing()

    /**
     * Promotes any SYNCED session that has DIRTY/SYNC_ERROR children
     * (lots OR rd_numbers) back to DIRTY so the next push iteration
     * picks it up.
     *
     * Covers two orphan classes:
     * 1. Defaulter edit on an already-synced session: T2.5 flips the
     *    rd_number to DIRTY but leaves the parent SYNCED. getDirtyForPush
     *    only iterates DIRTY/SYNC_ERROR sessions, so the edit would
     *    never reach cloud.
     * 2. Worker killed mid-push: a lot or rd_number flipped to SYNCING,
     *    recoverStuckSyncing flips it back to DIRTY, but the parent
     *    session was already marked SYNCED earlier in the same push.
     *    Same orphan pattern.
     *
     * Called by [SyncRepository.runPush] in the startup sweep alongside
     * recoverStuckSyncing.
     */
    @Query(
        """
        UPDATE scan_sessions
        SET syncStatus = 'DIRTY', updatedAt = :now
        WHERE syncStatus = 'SYNCED'
          AND deletedAt IS NULL
          AND id IN (
              SELECT sessionId FROM scan_lots
              WHERE syncStatus IN ('DIRTY','SYNC_ERROR')
              UNION
              SELECT sl.sessionId FROM scan_lots sl
              INNER JOIN rd_numbers rn ON rn.lotId = sl.id
              WHERE rn.syncStatus IN ('DIRTY','SYNC_ERROR')
          )
        """
    )
    suspend fun promoteSessionsWithDirtyChildren(now: Long)

    @Query("UPDATE scan_sessions SET displayNumber = :displayNumber WHERE id = :id")
    suspend fun updateDisplayNumber(id: Long, displayNumber: Int)

    // ── Pull merge helpers (Phase 3 T3.1) ────────────────────────────────

    @Query("SELECT * FROM scan_sessions WHERE cloudId = :cloudId LIMIT 1")
    suspend fun findByCloudId(cloudId: String): ScanSession?

    @Query("SELECT cloudId FROM scan_sessions WHERE id = :localId LIMIT 1")
    suspend fun findCloudIdByLocalId(localId: Long): String?

    /**
     * Last-writer-wins merge for an inbound pulled row per spec §11.
     * Overwrites the row IF the incoming `updatedAt` is strictly newer
     * than the local copy. Idempotent and safe to call concurrently;
     * the WHERE filter prevents an older payload from clobbering a
     * locally-edited row that hasn't been pushed yet.
     */
    @Query(
        """
        UPDATE scan_sessions SET
            cloudId = :cloudId,
            deviceCloudId = :deviceCloudId,
            operatorName = :operatorName,
            displayNumber = :displayNumber,
            startTime = :startTime,
            endTime = :endTime,
            isActive = 0,
            activeLotId = NULL,
            totalLots = :totalLots,
            totalRdNumbers = :totalRdNumbers,
            syncStatus = 'SYNCED',
            updatedAt = :updatedAt,
            syncedAt = :updatedAt,
            lastSyncError = NULL,
            retryCount = 0,
            deletedAt = :deletedAt
        WHERE id = :id AND updatedAt < :updatedAt
        """
    )
    suspend fun mergeFromCloud(
        id: Long,
        cloudId: String,
        deviceCloudId: String,
        operatorName: String?,
        displayNumber: Int,
        startTime: Long,
        endTime: Long,
        totalLots: Int,
        totalRdNumbers: Int,
        updatedAt: Long,
        deletedAt: Long?
    ): Int

    /**
     * Finalized sessions still stuck in LOCAL_ONLY. Targets two recovery cases:
     * (a) rotation-during-finalize orphans where scope cancelled between
     * endSession and markSessionForSync, (b) v5→v6 historical sessions
     * that the MIGRATION_5_6 backfill flipped to DIRTY but were then
     * de-promoted somehow (defensive). Called by [SyncRepository.runPush]
     * as part of its startup sweep.
     */
    @Query("SELECT * FROM scan_sessions WHERE isActive = 0 AND syncStatus = 'LOCAL_ONLY' AND deletedAt IS NULL")
    suspend fun getOrphanFinalizedSessions(): List<ScanSession>

    /**
     * Soft-deletes a session by stamping deletedAt + bumping updatedAt +
     * flipping syncStatus to DIRTY so the push worker propagates the
     * tombstone to cloud. Called by [SyncRepository.softDeleteSession] only
     * for already-synced sessions (cloudId != null); never-pushed sessions
     * are hard-deleted by the caller because there's nothing in cloud to
     * tombstone.
     */
    @Query(
        """
        UPDATE scan_sessions
        SET deletedAt = :now, updatedAt = :now, syncStatus = 'DIRTY'
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: Long, now: Long)

    /**
     * Live count of finalized sessions still owed a push. Excludes
     * SYNC_ABANDONED per oracle bg_0ea195ce R3/I6 (a row that's failed
     * PUSH_ABANDON_THRESHOLD times is structurally unpushable and
     * shouldn't keep inflating the pill's "pending" count forever).
     * Active sessions are also excluded because they're device-private
     * until the operator taps End Session.
     */
    @Query("SELECT COUNT(*) FROM scan_sessions WHERE syncStatus IN ('DIRTY','SYNC_ERROR') AND isActive = 0")
    fun observePendingCount(): Flow<Int>

    /**
     * Flips the entire session subtree (session + its lots + its rd_numbers)
     * from LOCAL_ONLY to DIRTY in one atomic UPDATE pass. Called at finalize
     * time once the cloud identity (cloudId, deviceCloudId, operatorName) is
     * stamped on the session row by [stampFinalizeMetadata]. Wrapped in
     * @Transaction at the SyncRepository level so a process kill between
     * the three writes can't half-promote a session.
     */
    @Query(
        """
        UPDATE scan_sessions
        SET syncStatus = 'DIRTY', updatedAt = :updatedAt
        WHERE id = :sessionId AND syncStatus = 'LOCAL_ONLY'
        """
    )
    suspend fun markSessionDirty(sessionId: Long, updatedAt: Long)

    @Query(
        """
        UPDATE scan_sessions
        SET cloudId = COALESCE(cloudId, :cloudId),
            deviceCloudId = :deviceCloudId,
            operatorName = :operatorName,
            updatedAt = :updatedAt
        WHERE id = :sessionId
        """
    )
    suspend fun stampFinalizeMetadata(
        sessionId: Long,
        cloudId: String,
        deviceCloudId: String,
        operatorName: String?,
        updatedAt: Long
    )
}

data class SessionDefaultCount(
    val sessionId: Long,
    val count: Int
)

@Dao
interface ScanLotDao {

    @Query("SELECT * FROM scan_lots WHERE sessionId = :sessionId AND deletedAt IS NULL ORDER BY lotNumber ASC")
    fun getLotsForSession(sessionId: Long): Flow<List<ScanLot>>

    @Query("SELECT * FROM scan_lots WHERE sessionId = :sessionId AND deletedAt IS NULL ORDER BY lotNumber ASC")
    suspend fun getLotsForSessionSync(sessionId: Long): List<ScanLot>

    @Insert
    suspend fun insert(lot: ScanLot): Long

    @Query("DELETE FROM scan_lots WHERE sessionId = :sessionId")
    suspend fun deleteLotsForSession(sessionId: Long)

    @Query("""
        DELETE FROM scan_lots
        WHERE id = :lotId
          AND NOT EXISTS (SELECT 1 FROM rd_numbers WHERE lotId = :lotId)
    """)
    suspend fun deleteIfEmpty(lotId: Long)

    // ── Sync push helpers (Phase 2 T2.1a) ────────────────────────────────

    @Query("SELECT * FROM scan_lots WHERE syncStatus IN ('DIRTY','SYNC_ERROR') AND sessionId = :sessionId ORDER BY lotNumber ASC")
    suspend fun getDirtyForSession(sessionId: Long): List<ScanLot>

    @Query("SELECT * FROM scan_lots WHERE id = :id AND deletedAt IS NULL LIMIT 1")
    suspend fun findById(id: Long): ScanLot?

    @Query("UPDATE scan_lots SET syncStatus = 'SYNCING' WHERE id = :id AND syncStatus IN ('DIRTY','SYNC_ERROR')")
    suspend fun markSyncing(id: Long)

    /** See [ScanSessionDao.stampCloudId]. */
    @Query("UPDATE scan_lots SET cloudId = COALESCE(cloudId, :cloudId) WHERE id = :id")
    suspend fun stampCloudId(id: Long, cloudId: String)

    @Query("UPDATE scan_lots SET syncStatus = 'SYNCED', syncedAt = :syncedAt, updatedAt = :cloudUpdatedAt, cloudId = COALESCE(cloudId, :cloudId), lastSyncError = NULL, retryCount = 0 WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, cloudUpdatedAt: Long, cloudId: String)

    @Query("UPDATE scan_lots SET syncStatus = 'SYNC_ERROR', lastSyncError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String)

    /** See [ScanSessionDao.markSyncAbandoned]. */
    @Query("UPDATE scan_lots SET syncStatus = 'SYNC_ABANDONED' WHERE id = :id")
    suspend fun markSyncAbandoned(id: Long)

    @Query("UPDATE scan_lots SET syncStatus = 'DIRTY' WHERE syncStatus = 'SYNCING'")
    suspend fun recoverStuckSyncing()

    @Query(
        """
        UPDATE scan_lots
        SET deletedAt = :now, updatedAt = :now, syncStatus = 'DIRTY'
        WHERE sessionId = :sessionId
        """
    )
    suspend fun softDeleteForSession(sessionId: Long, now: Long)

    @Query("SELECT * FROM scan_lots WHERE cloudId = :cloudId LIMIT 1")
    suspend fun findByCloudId(cloudId: String): ScanLot?

    @Query(
        """
        UPDATE scan_lots SET
            cloudId = :cloudId,
            sessionId = :sessionId,
            lotNumber = :lotNumber,
            timestamp = :timestamp,
            syncStatus = 'SYNCED',
            updatedAt = :updatedAt,
            syncedAt = :updatedAt,
            lastSyncError = NULL,
            retryCount = 0,
            deletedAt = :deletedAt
        WHERE id = :id AND updatedAt < :updatedAt
        """
    )
    suspend fun mergeFromCloud(
        id: Long,
        cloudId: String,
        sessionId: Long,
        lotNumber: Int,
        timestamp: Long,
        updatedAt: Long,
        deletedAt: Long?
    ): Int

    @Query(
        """
        UPDATE scan_lots
        SET syncStatus = 'DIRTY', updatedAt = :updatedAt
        WHERE sessionId = :sessionId AND syncStatus = 'LOCAL_ONLY'
        """
    )
    suspend fun markLotsDirtyForSession(sessionId: Long, updatedAt: Long)

}

