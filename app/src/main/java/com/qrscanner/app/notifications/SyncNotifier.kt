package com.qrscanner.app.notifications

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.qrscanner.app.MainActivity
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.R

/**
 * Owns the two sync system-tray notification channels for Phase 2 per
 * spec §15.5.2:
 *
 *  - **sync_success** (LOW): fires when this phone's own push succeeds
 *    for a finalized session. Silent, auto-cancel on tap.
 *  - **sync_error** (DEFAULT): fires after 3 consecutive failed retries
 *    for the same backlog. Sound, no vibration, auto-cancel on tap.
 *
 * Both notifications surface deep-link to the app via a tap intent
 * that opens [MainActivity]; the routing inside the app is owned by
 * `AuthAwareRoot` and `QRScannerNavigation`, so the notifier doesn't
 * need to know about destinations.
 *
 * POST_NOTIFICATIONS gating is enforced at every notify() call — on
 * API 33+ we silently no-op if the user denied the permission, so the
 * caller never has to remember to check.
 */
class SyncNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /**
     * Fires a "Session #N synced" notification. Called by SyncRepository
     * after a finalized session pushes successfully. The notification id
     * is deterministic from the session's displayNumber so multiple
     * successive syncs collapse into a single most-recent entry instead
     * of stacking.
     */
    fun notifySessionSynced(
        displayNumber: Int,
        totalLots: Int,
        totalRdNumbers: Int,
        deviceName: String
    ) {
        if (!canPostNotifications()) return

        val title = context.getString(R.string.notif_sync_success_title, displayNumber)
        val body = context.getString(
            R.string.notif_sync_success_body,
            totalLots,
            totalRdNumbers,
            deviceName
        )

        val notification = NotificationCompat.Builder(context, QRScannerApp.CHANNEL_SYNC_SUCCESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(requestCode = NOTIF_ID_SUCCESS_BASE + displayNumber))
            .build()

        runCatching {
            manager.notify(NOTIF_ID_SUCCESS_BASE + displayNumber, notification)
        }
    }

    /**
     * Fires (or refreshes) the sync-error notification. Called by
     * SyncRepository when the failure streak reaches the configured
     * threshold. Re-firing with the same id [NOTIF_ID_ERROR] replaces
     * the prior notification rather than stacking, so the user always
     * sees the current pending count.
     */
    fun notifySyncError(pendingCount: Int) {
        if (!canPostNotifications()) return
        if (pendingCount <= 0) {
            manager.cancel(NOTIF_ID_ERROR)
            return
        }

        val title = context.getString(R.string.notif_sync_error_title)
        val body = context.resources.getQuantityString(
            R.plurals.notif_sync_error_body,
            pendingCount,
            pendingCount
        )

        val notification = NotificationCompat.Builder(context, QRScannerApp.CHANNEL_SYNC_ERROR)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(requestCode = NOTIF_ID_ERROR))
            .build()

        runCatching { manager.notify(NOTIF_ID_ERROR, notification) }
    }

    /** Clears the persistent error notification once sync recovers. */
    fun clearSyncError() {
        runCatching { manager.cancel(NOTIF_ID_ERROR) }
    }

    private fun buildOpenAppIntent(requestCode: Int): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // FLAG_IMMUTABLE required on API 31+; we always include it for
        // forward compatibility and because we never need to mutate the
        // intent post-construction.
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, launch, pendingFlags)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val NOTIF_ID_ERROR = 9001
        private const val NOTIF_ID_SUCCESS_BASE = 10_000
    }
}
