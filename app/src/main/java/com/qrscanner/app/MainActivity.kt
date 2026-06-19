package com.qrscanner.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qrscanner.app.cloud.CloudSessionStatus
import com.qrscanner.app.ui.auth.AuthAwareRoot
import com.qrscanner.app.ui.theme.QRScannerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as QRScannerApp

        // Phase 3 T3.4: realtime subscription + lifecycle-scoped 5-min poll.
        // repeatOnLifecycle(STARTED) means both children cancel when the
        // activity goes to STOPPED and restart when STARTED again — no
        // background battery drain, no leaked WebSocket on rotation.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!app.isCloudConfigured) return@repeatOnLifecycle

                // Realtime gated on auth. collectLatest cancels the prior
                // subscription if sessionStatus flips (e.g. signOut →
                // signIn with a different owner), so we never leak a
                // stale-owner channel.
                launch {
                    app.cloudClient.sessionStatus.collectLatest { status ->
                        if (status is CloudSessionStatus.Authenticated) {
                            runCatching { app.syncScheduler.enqueuePull() }
                            try {
                                app.cloudClient.observeRealtimeChanges(status.session.ownerId)
                                    .collect { payload ->
                                        runCatching { app.syncRepository.handleRealtimeChange(payload) }
                                    }
                            } catch (e: Exception) {
                                Log.w(TAG, "realtime subscription failed", e)
                            }
                        }
                    }
                }

                // Backstop poll: 5-min cadence catches anything realtime
                // missed (channel drop, transient WS reconnect). Spec
                // §15.5 amendment — the poll is lifecycle-scoped because
                // WorkManager's minimum periodic interval is 15 min,
                // which doesn't satisfy the spec's 5-min target.
                launch {
                    while (true) {
                        delay(POLL_INTERVAL)
                        runCatching { app.syncScheduler.enqueuePull() }
                    }
                }
            }
        }

        setContent {
            QRScannerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AuthAwareRoot()
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private val POLL_INTERVAL = 5.minutes
    }
}
