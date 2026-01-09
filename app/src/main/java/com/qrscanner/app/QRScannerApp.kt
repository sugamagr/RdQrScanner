package com.qrscanner.app

import android.app.Application
import com.qrscanner.app.data.AppDatabase

class QRScannerApp : Application() {
    
    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }
}
