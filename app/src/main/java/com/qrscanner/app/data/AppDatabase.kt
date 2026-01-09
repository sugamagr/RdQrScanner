package com.qrscanner.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ScanSession::class, ScanLot::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(RdNumberListConverter::class)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun scanSessionDao(): ScanSessionDao
    abstract fun scanLotDao(): ScanLotDao
    
    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rd_scanner_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
