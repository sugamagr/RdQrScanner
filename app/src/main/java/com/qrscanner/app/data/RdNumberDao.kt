package com.qrscanner.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RdNumberDao {

    @Insert
    suspend fun insert(rdNumber: RdNumber): Long

    @Query("SELECT * FROM rd_numbers WHERE lotId = :lotId AND deletedAt IS NULL ORDER BY position ASC")
    fun getNumbersForLot(lotId: Long): Flow<List<RdNumber>>

    @Query("SELECT * FROM rd_numbers WHERE lotId = :lotId AND deletedAt IS NULL ORDER BY position ASC")
    suspend fun getNumbersForLotSync(lotId: Long): List<RdNumber>

    @Query("""
        SELECT rn.number FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE sl.sessionId = :sessionId AND rn.deletedAt IS NULL
    """)
    suspend fun getAllNumbersInSession(sessionId: Long): List<String>

    /**
     * Full rd_number rows for a session, used by
     * [com.qrscanner.app.data.sync.SyncRepository.markSessionForSync]
     * to compute per-account `lastPaidThrough` updates and by
     * [com.qrscanner.app.data.sync.SyncRepository.pushSession] to
     * compute `defaultCount` from monthsPaid > 1 rows. Tombstones
     * are excluded server-side so neither computation inflates from
     * soft-deleted defaulters.
     */
    @Query("""
        SELECT rn.* FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE sl.sessionId = :sessionId AND rn.deletedAt IS NULL
    """)
    suspend fun getAllRowsInSession(sessionId: Long): List<RdNumber>

    /** Sign-out wipe — see [ScanSessionDao.deleteAll] for contract. */
    @Query("DELETE FROM rd_numbers")
    suspend fun deleteAll()

    @Query("DELETE FROM rd_numbers WHERE lotId = :lotId")
    suspend fun deleteForLot(lotId: Long)

    @Query("DELETE FROM rd_numbers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM rd_numbers WHERE lotId = :lotId AND deletedAt IS NULL ORDER BY position DESC LIMIT 1")
    suspend fun getMostRecentForLot(lotId: Long): RdNumber?

    /**
     * AccountHistoryScreen data source. Returns one row per scan of the
     * given rd_number across the whole session ledger, filtering out
     * tombstoned rd_numbers, tombstoned LOTs, and tombstoned sessions
     * per R3 discussion (History hides deleted sessions to stay
     * consistent with SessionHistoryScreen). Ordered newest-session
     * first so the operator's most recent activity is at the top of
     * the list. Column aliases MUST stay in lockstep with
     * [AccountHistoryRow] field names — Room binds by name.
     */
    @Query("""
        SELECT rn.id AS rdNumberId,
               rn.monthsPaid AS monthsPaid,
               rn.monthsList AS monthsList,
               rn.scannedAt AS scannedAt,
               sl.id AS lotId,
               sl.lotNumber AS lotNumber,
               sl.timestamp AS lotTimestamp,
               ss.id AS sessionId,
               ss.displayNumber AS sessionNumber,
               ss.startTime AS sessionStart,
               ss.endTime AS sessionEnd,
               ss.operatorName AS operatorName
        FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        INNER JOIN scan_sessions ss ON sl.sessionId = ss.id
        WHERE rn.number = :rdNumber
          AND rn.deletedAt IS NULL
          AND sl.deletedAt IS NULL
          AND ss.deletedAt IS NULL
        ORDER BY ss.endTime DESC, ss.startTime DESC, sl.lotNumber ASC
    """)
    fun observeHistoryForRdNumber(rdNumber: String): Flow<List<AccountHistoryRow>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM rd_numbers WHERE lotId = :lotId AND deletedAt IS NULL")
    suspend fun getNextPosition(lotId: Long): Int

    @Query("""
        DELETE FROM rd_numbers WHERE lotId IN
        (SELECT id FROM scan_lots WHERE sessionId = :sessionId)
    """)
    suspend fun deleteForSession(sessionId: Long)

    @Query("UPDATE rd_numbers SET monthsPaid = :months, monthsList = :monthsList WHERE id = :id")
    suspend fun updateMonths(id: Long, months: Int, monthsList: String?)

    @Query("""
        SELECT COUNT(*) FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE sl.sessionId = :sessionId AND rn.monthsPaid > 1 AND rn.deletedAt IS NULL
    """)
    fun observeDefaultCountForSession(sessionId: Long): Flow<Int>

    @Query("""
        SELECT COALESCE(SUM(rn.monthsPaid), 0) FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        WHERE sl.sessionId = :sessionId AND rn.monthsPaid > 1 AND rn.deletedAt IS NULL
    """)
    fun observeTotalDefaulterMonthsForSession(sessionId: Long): Flow<Int>

    // ── Sync push helpers (Phase 2 T2.1a) ────────────────────────────────

    @Query("SELECT * FROM rd_numbers WHERE syncStatus IN ('DIRTY','SYNC_ERROR') AND lotId = :lotId ORDER BY position ASC")
    suspend fun getDirtyForLot(lotId: Long): List<RdNumber>

    @Query("SELECT * FROM rd_numbers WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): RdNumber?

    @Query("UPDATE rd_numbers SET syncStatus = 'SYNCING' WHERE id = :id AND syncStatus IN ('DIRTY','SYNC_ERROR')")
    suspend fun markSyncing(id: Long)

    /** See [ScanSessionDao.stampCloudId]. */
    @Query("UPDATE rd_numbers SET cloudId = COALESCE(cloudId, :cloudId) WHERE id = :id")
    suspend fun stampCloudId(id: Long, cloudId: String)

    @Query("UPDATE rd_numbers SET syncStatus = 'SYNCED', syncedAt = :syncedAt, updatedAt = :cloudUpdatedAt, cloudId = COALESCE(cloudId, :cloudId), lastSyncError = NULL, retryCount = 0 WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAt: Long, cloudUpdatedAt: Long, cloudId: String)

    @Query("UPDATE rd_numbers SET syncStatus = 'SYNC_ERROR', lastSyncError = :error, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String)

    /** See [ScanSessionDao.markSyncAbandoned]. */
    @Query("UPDATE rd_numbers SET syncStatus = 'SYNC_ABANDONED' WHERE id = :id")
    suspend fun markSyncAbandoned(id: Long)

    @Query("UPDATE rd_numbers SET syncStatus = 'DIRTY' WHERE syncStatus = 'SYNCING'")
    suspend fun recoverStuckSyncing()

    @Query(
        """
        UPDATE rd_numbers
        SET deletedAt = :now, updatedAt = :now, syncStatus = 'DIRTY'
        WHERE lotId IN (SELECT id FROM scan_lots WHERE sessionId = :sessionId)
        """
    )
    suspend fun softDeleteForSession(sessionId: Long, now: Long)

    // ── Pull merge helpers (Phase 3 T3.1) ────────────────────────────────

    @Query("SELECT * FROM rd_numbers WHERE cloudId = :cloudId LIMIT 1")
    suspend fun findByCloudId(cloudId: String): RdNumber?

    @Query(
        """
        UPDATE rd_numbers SET
            cloudId = :cloudId,
            lotId = :lotId,
            number = :number,
            position = :position,
            scannedAt = :scannedAt,
            monthsPaid = :monthsPaid,
            monthsList = :monthsList,
            lastEditorDeviceId = :lastEditorDeviceId,
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
        lotId: Long,
        number: String,
        position: Int,
        scannedAt: Long,
        monthsPaid: Int,
        monthsList: String?,
        lastEditorDeviceId: String?,
        updatedAt: Long,
        deletedAt: Long?
    ): Int

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

    /**
     * R3 revert-cascade read path. Returns every rd_number row for
     * [rdNumber] that would still be visible if [excludeSessionId]
     * were tombstoned: filters out (a) rows whose lot lives in the
     * excluded session, (b) row-level tombstones, (c) lot-level
     * tombstones, (d) session-level tombstones from OTHER already-
     * deleted sessions.
     *
     * Used by [com.qrscanner.app.data.sync.SyncRepository.softDeleteSession]
     * to recompute lastPaidThrough post-delete: the max month resolved
     * across these survivors becomes the new value (NULL if empty).
     * MUST be called inside the same transaction that tombstones the
     * session, otherwise a concurrent scan could land between the read
     * and the write and be silently overwritten.
     */
    @Query("""
        SELECT rn.* FROM rd_numbers rn
        INNER JOIN scan_lots sl ON rn.lotId = sl.id
        INNER JOIN scan_sessions ss ON sl.sessionId = ss.id
        WHERE rn.number = :rdNumber
          AND ss.id != :excludeSessionId
          AND rn.deletedAt IS NULL
          AND sl.deletedAt IS NULL
          AND ss.deletedAt IS NULL
    """)
    suspend fun getSurvivingScansForRdNumberExcludingSession(
        rdNumber: String,
        excludeSessionId: Long
    ): List<RdNumber>
}
