package com.qrscanner.app

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qrscanner.app.cloud.CloudSessionStatus
import com.qrscanner.app.ui.auth.AuthAwareRoot
import com.qrscanner.app.ui.theme.QRScannerTheme
import com.qrscanner.app.ui.update.UpdateBanner
import com.qrscanner.app.ui.update.UpdateGateScreen
import com.qrscanner.app.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as QRScannerApp
        val updateGate = app.updateGateController

        // Schedule the daily sync_events pruning worker. Idempotent
        // (KEEP policy) so calling on every launch is safe. Without
        // this, the sync_events DAO's pruneOldEvents query is defined
        // but never executed and the table grows unbounded.
        app.syncScheduler.scheduleEventPruning()

        // Force-update gate. Runs OFF the main thread on lifecycleScope
        // and never blocks the launch path: if GitHub is unreachable
        // the checker times out at 8s and returns UpToDate, so the app
        // opens normally on a bad WiFi day. Fresh check on every
        // MainActivity onCreate — cheap because GitHub CDN caches
        // releases/latest, and we want operators forced to the newest
        // version the next time they cold-launch the app after we
        // publish a release.
        updateGate.launchCheck(applicationContext, lifecycleScope)

        // Reconnect catch-up: fires enqueuePull() the instant the OS
        // hands us a usable default network again. Without this the
        // only catch-up paths after airplane-mode toggle are the 5-min
        // backstop poll above OR the realtime channel's own
        // reconnect, both of which can lag the actual network event
        // by minutes. NOT lifecycle-scoped because we want the
        // wake-up even when the activity is briefly backgrounded
        // during the toggle; the OS unregisters automatically in
        // onDestroy via our cleanup hook below. R6 oracle
        // bg_cadc45d5 F2.
        if (app.isCloudConfigured) {
            val cm = getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        runCatching { app.syncScheduler.enqueuePull() }
                    }
                }
                networkCallback = callback
                runCatching { cm.registerDefaultNetworkCallback(callback) }
            }
        }

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
                    val available =
                        updateGate.checkedResult as? UpdateChecker.UpdateResult.Available
                    val bannerDismissed = updateGate.bannerDismissed
                    val onPrimary = {
                        updateGate.onPrimaryClicked(
                            context = this@MainActivity,
                            scope = lifecycleScope,
                            openPermissionSettings = {
                                startActivity(
                                    UpdateChecker.installUnknownAppsSettingsIntent(this@MainActivity)
                                )
                            }
                        )
                    }
                    when {
                        available != null && available.isForce -> {
                            UpdateGateScreen(
                                newVersionName = available.versionName,
                                newVersionCode = available.versionCode,
                                apkSizeBytes = available.apkSizeBytes,
                                changelog = available.changelog,
                                state = updateGate.gateState,
                                onPrimaryAction = onPrimary
                            )
                        }
                        available != null && !bannerDismissed -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AuthAwareRoot()
                                UpdateBanner(
                                    newVersionName = available.versionName,
                                    onUpdate = onPrimary,
                                    onDismiss = { updateGate.dismissBannerForSession() },
                                    modifier = Modifier.align(Alignment.TopCenter)
                                )
                            }
                        }
                        else -> {
                            AuthAwareRoot()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
        private val POLL_INTERVAL = 5.minutes
    }
}
