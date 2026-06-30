package com.qrscanner.app.notifications

import android.Manifest
import android.annotation.SuppressLint
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
import com.qrscanner.app.data.SyncEventType

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
    @SuppressLint("MissingPermission") // canPostNotifications() gate above each notify()
    fun notifySessionSynced(
        displayNumber: Int,
        totalLots: Int,
        totalRdNumbers: Int,
        actorLabel: String
    ) {
        if (!canPostNotifications()) return

        val title = context.getString(R.string.notif_sync_success_title, displayNumber)
        val body = context.getString(
            R.string.notif_sync_success_body,
            totalLots,
            totalRdNumbers,
            actorLabel
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
     * Batched 'N sessions synced' summary used when a single push cycle
     * pushes more than [BULK_SUMMARY_THRESHOLD] sessions (v5→v6 first
     * push, backlog catchup after offline period, etc.). One notification
     * instead of N spam notifications.
     *
     * Fixed id so a subsequent batch replaces rather than stacks. Body
     * shows the latest 3 display numbers as a 'and 47 more' tail so the
     * user sees recent activity without scrolling 50 notifications.
     */
    @SuppressLint("MissingPermission") // canPostNotifications() gate inside
    fun notifyBulkSessionsSynced(syncedDisplayNumbers: List<Int>) {
        if (!canPostNotifications()) return
        if (syncedDisplayNumbers.isEmpty()) return

        val count = syncedDisplayNumbers.size
        val title = context.resources.getQuantityString(
            R.plurals.notif_sync_bulk_title,
            count,
            count
        )
        val sortedDesc = syncedDisplayNumbers.sortedDescending()
        val recentLabel = sortedDesc.take(3).joinToString(", ") { "#$it" }
        val body = if (count > 3) {
            context.getString(R.string.notif_sync_bulk_body_extra, recentLabel, count - 3)
        } else {
            context.getString(R.string.notif_sync_bulk_body_short, recentLabel)
        }

        val notification = NotificationCompat.Builder(context, QRScannerApp.CHANNEL_SYNC_SUCCESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(requestCode = NOTIF_ID_BULK_SUCCESS))
            .build()

        runCatching { manager.notify(NOTIF_ID_BULK_SUCCESS, notification) }
    }

    /**
     * Spec §15.5.2 Channel C — fires when a pull merges a change that
     * originated elsewhere (another phone or the portal). The id is
     * deterministic from (type, displayNumber) so successive edits to
     * the same session collapse into one tray slot.
     */
    @SuppressLint("MissingPermission") // canPostNotifications() gate inside
    fun notifyRemoteEdit(type: SyncEventType, displayNumber: Int, originLabel: String) {
        if (!canPostNotifications()) return
        val (title, body) = when (type) {
            SyncEventType.REMOTE_SESSION_FINALIZED -> Pair(
                context.getString(R.string.notif_remote_session_finalized_title, displayNumber),
                context.getString(R.string.notif_remote_session_finalized_body, originLabel)
            )
            SyncEventType.REMOTE_DEFAULTER_EDIT,
            SyncEventType.PORTAL_DEFAULTER_EDIT -> Pair(
                context.getString(R.string.notif_remote_defaulter_edit_title, displayNumber),
                context.getString(R.string.notif_remote_defaulter_edit_body, originLabel)
            )
            SyncEventType.REMOTE_SESSION_DELETED -> Pair(
                context.getString(R.string.notif_remote_session_deleted_title, displayNumber),
                context.getString(R.string.notif_remote_session_deleted_body, originLabel)
            )
            // Tray notifications fire only for cross-device changes; the
            // operator's own LOCAL_* actions already produced in-app
            // feedback (Toast / sheet) and don't need a second nudge in
            // the system tray. notifyRemoteEdit() is currently only
            // called from SyncRepository's pull-merge path so these
            // branches are defensive — they keep this `when` exhaustive
            // if the call surface ever widens.
            SyncEventType.LOCAL_SESSION_FINALIZED,
            SyncEventType.LOCAL_ACCOUNTS_ADDED,
            SyncEventType.LOCAL_DEFAULTER_EDIT -> return
        }
        val notificationId = NOTIF_ID_REMOTE_EDIT_BASE +
            (type.ordinal * 100_000) + (displayNumber % 100_000)
        val notification = NotificationCompat.Builder(context, QRScannerApp.CHANNEL_REMOTE_EDIT)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(requestCode = notificationId))
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    /**
     * Fires (or refreshes) the sync-error notification. Called by
     * SyncRepository when the failure streak reaches the configured
     * threshold. Re-firing with the same id [NOTIF_ID_ERROR] replaces
     * the prior notification rather than stacking, so the user always
     * sees the current pending count.
     */
    @SuppressLint("MissingPermission") // canPostNotifications() gate inside
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

    /**
     * Fires when a row crosses the SYNC_ABANDONED threshold (default 8
     * consecutive push failures). Distinct from the transient SYNC_ERROR
     * notification because abandoned rows are EXCLUDED from
     * [SyncRepository.getDirtyForPush] forever after — the operator
     * must take manual action (re-finalize the session, contact support)
     * or the row sits as silent local-only data. Without this tray nudge
     * the operator never learns a row was permanently dropped from the
     * cloud cycle; the pill goes back to OK because no DIRTY rows remain.
     *
     * Channel = sync_error (operator-attention class). Distinct
     * notification id so it doesn't collapse with the transient-error
     * spinner.
     */
    @SuppressLint("MissingPermission") // canPostNotifications() gate inside
    fun notifySyncAbandoned(rowKind: String, contextLabel: String) {
        if (!canPostNotifications()) return
        val title = context.getString(R.string.notif_sync_abandoned_title)
        val body = context.getString(R.string.notif_sync_abandoned_body, rowKind, contextLabel)
        val notification = NotificationCompat.Builder(context, QRScannerApp.CHANNEL_SYNC_ERROR)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppIntent(requestCode = NOTIF_ID_ABANDONED))
            .build()
        runCatching { manager.notify(NOTIF_ID_ABANDONED, notification) }
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
        const val BULK_SUMMARY_THRESHOLD = 5
        private const val NOTIF_ID_ERROR = 9001
        private const val NOTIF_ID_BULK_SUCCESS = 9002
        private const val NOTIF_ID_ABANDONED = 9003
        private const val NOTIF_ID_SUCCESS_BASE = 10_000
        private const val NOTIF_ID_REMOTE_EDIT_BASE = 20_000
    }
}
