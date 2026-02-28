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
    
    @Query("SELECT * FROM scan_sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): ScanSession?
    
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
    @Query("UPDATE scan_sessions SET isActive = 0, endTime = :endTime, totalLots = :totalLots, totalRdNumbers = :totalRdNumbers, displayNumber = :displayNumber WHERE id = :id")
    suspend fun endSession(id: Long, endTime: Long, totalLots: Int, totalRdNumbers: Int, displayNumber: Int)
}

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
    
}

