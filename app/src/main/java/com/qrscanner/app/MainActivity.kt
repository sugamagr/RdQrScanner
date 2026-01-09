package com.qrscanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.qrscanner.app.ui.theme.QRScannerTheme
import com.qrscanner.app.navigation.QRScannerNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QRScannerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QRScannerNavigation()
                }
            }
        }
    }
}




