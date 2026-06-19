package com.qrscanner.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    
    @Query("SELECT * FROM scan_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ScanSession>>
    
    // Only get completed sessions with actual data, ordered by displayNumber
    @Query("SELECT * FROM scan_sessions WHERE isActive = 0 AND totalLots > 0 ORDER BY displayNumber DESC")
    fun getCompletedSessions(): Flow<List<ScanSession>>
    
    @Query("SELECT * FROM scan_sessions WHERE isActive = 1 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): ScanSession?

    @Query("SELECT id FROM scan_sessions WHERE isActive = 1 ORDER BY startTime DESC")
    suspend fun getAllActiveSessionIds(): List<Long>
    
    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): ScanSession?
    
    // Get the next sequential display number
    @Query("SELECT COALESCE(MAX(displayNumber), 0) + 1 FROM scan_sessions WHERE isActive = 0 AND totalLots > 0")
    suspend fun getNextDisplayNumber(): Int
    
    @Insert
    suspend fun insert(session: ScanSession): Long
    
    @Update
    suspend fun update(session: ScanSession)
    
    @Delete
    suspend fun delete(session: ScanSession)
    
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
        WHERE rn.monthsPaid > 1
        GROUP BY sl.sessionId
    """)
    fun getDefaultCountsBySession(): Flow<List<SessionDefaultCount>>

    // ── Sync push helpers (Phase 2 T2.1a) ────────────────────────────────

    @Query("SELECT * FROM scan_sessions WHERE syncStatus IN ('DIRTY','SYNC_ERROR') AND isActive = 0 ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getDirtyForPush(limit: Int = 500): List<ScanSession>

    @Query("UPDATE scan_sessions SET syncStatus = 'SYNCING' WHERE id = :id AND syncStatus IN ('DIRTY','SYNC_ERROR')")
    suspend fun markSyncing(id: Long)

    @Query("UPDATE scan_sessions SET syncStatus = 'SYNCED', syncedAt = :syncedAt, cloudId = COALESCE(cloudId, :cloudId), lastSyncError = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, cloudId: String)

    @Query("UPDATE scan_sessions SET syncStatus = 'SYNC_ERROR', lastSyncError = :error WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String)

    /**
     * Reverts any session left in SYNCING after a worker was killed
     * mid-push back to DIRTY so the next push run picks it up. Without
     * this sweep the row is invisible to [getDirtyForPush] and frozen
     * forever. Called as the first DAO interaction of [SyncRepository.runPush].
     */
    @Query("UPDATE scan_sessions SET syncStatus = 'DIRTY' WHERE syncStatus = 'SYNCING'")
    suspend fun recoverStuckSyncing()

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
    
    @Query("SELECT * FROM scan_lots WHERE sessionId = :sessionId ORDER BY lotNumber ASC")
    fun getLotsForSession(sessionId: Long): Flow<List<ScanLot>>
    
    @Query("SELECT * FROM scan_lots WHERE sessionId = :sessionId ORDER BY lotNumber ASC")
    suspend fun getLotsForSessionSync(sessionId: Long): List<ScanLot>

    @Insert
    suspend fun insert(lot: ScanLot): Long
    
    @Update
    suspend fun update(lot: ScanLot)
    
    @Delete
    suspend fun delete(lot: ScanLot)
    
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

    @Query("UPDATE scan_lots SET syncStatus = 'SYNCING' WHERE id = :id AND syncStatus IN ('DIRTY','SYNC_ERROR')")
    suspend fun markSyncing(id: Long)

    @Query("UPDATE scan_lots SET syncStatus = 'SYNCED', syncedAt = :syncedAt, cloudId = COALESCE(cloudId, :cloudId), lastSyncError = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, cloudId: String)

    @Query("UPDATE scan_lots SET syncStatus = 'SYNC_ERROR', lastSyncError = :error WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String)

    @Query("UPDATE scan_lots SET syncStatus = 'DIRTY' WHERE syncStatus = 'SYNCING'")
    suspend fun recoverStuckSyncing()

    @Query(
        """
        UPDATE scan_lots
        SET syncStatus = 'DIRTY', updatedAt = :updatedAt
        WHERE sessionId = :sessionId AND syncStatus = 'LOCAL_ONLY'
        """
    )
    suspend fun markLotsDirtyForSession(sessionId: Long, updatedAt: Long)

}

