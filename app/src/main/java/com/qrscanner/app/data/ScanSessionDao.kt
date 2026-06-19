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
    
    @Query("SELECT * FROM scan_lots WHERE id = :id")
    suspend fun getLotById(id: Long): ScanLot?
    
    @Query("SELECT MAX(lotNumber) FROM scan_lots WHERE sessionId = :sessionId")
    suspend fun getMaxLotNumber(sessionId: Long): Int?
    
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

}

