package com.qrscanner.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.qrscanner.app.cloud.CloudClient
import com.qrscanner.app.cloud.SupabaseCloudClient
import com.qrscanner.app.data.AppDatabase
import com.qrscanner.app.data.sync.SyncRepository
import com.qrscanner.app.notifications.SyncNotifier
import com.qrscanner.app.work.SyncWorkScheduler

/**
 * Application root. Owns the singletons every other layer reaches for
 * via `LocalContext.current.applicationContext as QRScannerApp`.
 *
 * Wave 3 (Phase 1) adds three new responsibilities:
 *
 * 1. [cloudClient] — production Supabase client. Initialized lazily so a
 *    fresh install that hasn't been signed in yet doesn't pay the SDK
 *    bootstrap cost until the first auth attempt.
 *
 * 2. [syncRepository] — coordinator between [database] and [cloudClient].
 *    Lazy for the same reason; consuming code never touches this before
 *    sign-in.
 *
 * 3. Notification channels (spec §15.5.6) — created eagerly in
 *    [onCreate] so any builder firing later in the app's lifetime can
 *    safely reference the channel ids. The OS will register the channel
 *    after the first notification, but creating early lets the user see
 *    the channels under system Settings → Notifications even before
 *    anything has been posted.
 *
 * 4. [Configuration.Provider] for WorkManager. The AndroidX Startup
 *    auto-init is disabled in the manifest (per the librarian guide and
 *    spec §15) so we control the exact moment WorkManager spins up.
 */
class QRScannerApp : Application(), Configuration.Provider {

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    /**
     * True when SUPABASE_URL + SUPABASE_ANON_KEY are present in BuildConfig.
     * AuthAwareRoot reads this to render a 'Cloud sync not configured' screen
     * instead of accessing [cloudClient] and crashing on the SDK's bare
     * malformed-URL error path (oracle round 6 BLOCKER #3).
     */
    val isCloudConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val cloudClient: CloudClient by lazy {
        SupabaseCloudClient(this)
    }

    val syncNotifier: SyncNotifier by lazy {
        SyncNotifier(this)
    }

    val syncRepository: SyncRepository by lazy {
        SyncRepository(
            database = database,
            cloudClient = cloudClient,
            notifier = syncNotifier,
            syncPrefs = getSharedPreferences(SyncRepository.PREFS_FILE, MODE_PRIVATE)
        )
    }

    val syncScheduler: SyncWorkScheduler by lazy {
        SyncWorkScheduler(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_SYNC_SUCCESS,
                    getString(R.string.channel_sync_success),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.channel_sync_success_desc)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_SYNC_ERROR,
                    getString(R.string.channel_sync_error),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = getString(R.string.channel_sync_error_desc)
                    setShowBadge(true)
                },
                NotificationChannel(
                    CHANNEL_REMOTE_EDIT,
                    getString(R.string.channel_remote_edit),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.channel_remote_edit_desc)
                    setShowBadge(true)
                }
            )
        )
    }

    companion object {
        const val CHANNEL_SYNC_SUCCESS = "sync_success"
        const val CHANNEL_SYNC_ERROR = "sync_error"
        const val CHANNEL_REMOTE_EDIT = "remote_edit"
    }
}
