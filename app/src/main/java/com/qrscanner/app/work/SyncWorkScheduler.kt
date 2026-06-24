package com.qrscanner.app.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin facade over [WorkManager] for the cloud-sync workers. Callers
 * (scanner finalize, defaulter edit, history delete, settings 'force
 * push') invoke [enqueuePush] / [enqueuePull] and forget — they never
 * touch [WorkManager] directly.
 */
class SyncWorkScheduler(context: Context) {

    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext)

    /**
     * Enqueue a push with REPLACE semantics: cancel any pending push and
     * start fresh. The user just did something they expect to sync (end
     * session, defaulter save, etc.); making them wait for an earlier
     * queued push to finish would feel unresponsive. REPLACE also
     * ensures we never run two push workers concurrently.
     */
    fun enqueuePush() {
        workManager.enqueueUniqueWork(
            SyncPushWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SyncPushWorker.buildRequest()
        )
    }

    /**
     * Enqueue a pull with KEEP semantics: if a pull is already in flight,
     * don't displace it. Pulls are idempotent — replaying the same delta
     * is a no-op via mergeFromCloud's updated_at filter — so we prefer
     * letting the running one finish over churning the queue.
     *
     * Called from MainActivity on app launch / SessionHistory open / the
     * lifecycle-scoped 5-min foreground poll (Phase 3 T3.4).
     */
    fun enqueuePull() {
        workManager.enqueueUniqueWork(
            SyncPullWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            SyncPullWorker.buildRequest()
        )
    }

    /** Cancel both pending workers. Called on sign-out by [SettingsScreen]. */
    fun cancelAll() {
        workManager.cancelUniqueWork(SyncPushWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork(SyncPullWorker.UNIQUE_WORK_NAME)
    }

    /**
     * Schedule the daily [SyncEventPruneWorker] to enforce the sync_events
     * table's 100-row / 7-day retention cap. Idempotent: KEEP policy
     * means subsequent calls are no-ops, so it's safe to invoke on every
     * app launch from MainActivity. Without this scheduling call the
     * table grows unbounded — the DAO's pruneOldEvents query is defined
     * but never executed.
     */
    fun scheduleEventPruning() {
        workManager.enqueueUniquePeriodicWork(
            SyncEventPruneWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            SyncEventPruneWorker.buildRequest()
        )
    }

    /**
     * Reactive view of the push worker state for the sync diagnostics
     * screen. Emits the most-recent terminal state (SUCCEEDED / FAILED /
     * CANCELLED) plus any in-flight RUNNING.
     */
    fun observePushState(): Flow<WorkInfo.State?> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncPushWorker.UNIQUE_WORK_NAME)
            .map { it.firstOrNull()?.state }
}
