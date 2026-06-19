package com.qrscanner.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RdNumberDao {

    @Insert
    suspend fun insert(rdNumber: RdNumber): Long

    @Insert
    suspend fun insertAll(rdNumbers: List<RdNumber>)

    @Query("SELECT * FROM rd_numbers WHERE lotId = :lotId ORDER BY position ASC")
    fun getNumbersForLot(lotId: Long): Flow<List<RdNumber>>

    @Query("SELECT * FROM rd_numbers WHERE lotId = :lotId ORDER BY position ASC")
    suspend fun getNumbersForLotSync(lotId: Long): List<RdNumber>

    @Query("""
        SELECT rn.number FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE sl.sessionId = :sessionId
    """)
    suspend fun getAllNumbersInSession(sessionId: Long): List<String>

    @Query("SELECT COUNT(*) FROM rd_numbers WHERE lotId = :lotId")
    suspend fun getCountForLot(lotId: Long): Int

    @Query("DELETE FROM rd_numbers WHERE lotId = :lotId")
    suspend fun deleteForLot(lotId: Long)

    @Query("DELETE FROM rd_numbers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM rd_numbers WHERE lotId = :lotId ORDER BY position DESC LIMIT 1")
    suspend fun getMostRecentForLot(lotId: Long): RdNumber?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM rd_numbers WHERE lotId = :lotId")
    suspend fun getNextPosition(lotId: Long): Int

    @Query("""
        DELETE FROM rd_numbers WHERE lotId IN
        (SELECT id FROM scan_lots WHERE sessionId = :sessionId)
    """)
    suspend fun deleteForSession(sessionId: Long)

    @Query("UPDATE rd_numbers SET monthsPaid = :months, monthsList = :monthsList WHERE id = :id")
    suspend fun updateMonths(id: Long, months: Int, monthsList: String?)

    @Query("SELECT COUNT(*) FROM rd_numbers WHERE lotId = :lotId AND monthsPaid > 1")
    fun observeDefaultCountForLot(lotId: Long): Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE sl.sessionId = :sessionId AND rn.monthsPaid > 1
    """)
    fun observeDefaultCountForSession(sessionId: Long): Flow<Int>

    @Query("""
        SELECT COALESCE(SUM(rn.monthsPaid), 0) FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE sl.sessionId = :sessionId AND rn.monthsPaid > 1
    """)
    fun observeTotalDefaulterMonthsForSession(sessionId: Long): Flow<Int>

    // ── Sync push helpers (Phase 2 T2.1a) ────────────────────────────────

    @Query("SELECT * FROM rd_numbers WHERE syncStatus IN ('DIRTY','SYNC_ERROR') AND lotId = :lotId ORDER BY position ASC")
    suspend fun getDirtyForLot(lotId: Long): List<RdNumber>

    @Query("UPDATE rd_numbers SET syncStatus = 'SYNCING' WHERE id = :id AND syncStatus IN ('DIRTY','SYNC_ERROR')")
    suspend fun markSyncing(id: Long)

    @Query("UPDATE rd_numbers SET syncStatus = 'SYNCED', syncedAt = :syncedAt, cloudId = COALESCE(cloudId, :cloudId), lastSyncError = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, cloudId: String)

    @Query("UPDATE rd_numbers SET syncStatus = 'SYNC_ERROR', lastSyncError = :error WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String)

    @Query(
        """
        UPDATE rd_numbers
        SET syncStatus = 'DIRTY', updatedAt = :updatedAt
        WHERE lotId IN (SELECT id FROM scan_lots WHERE sessionId = :sessionId) AND syncStatus = 'LOCAL_ONLY'
        """
    )
    suspend fun markRdNumbersDirtyForSession(sessionId: Long, updatedAt: Long)

    @Query("UPDATE rd_numbers SET syncStatus = 'DIRTY', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDirty(id: Long, updatedAt: Long)
}
