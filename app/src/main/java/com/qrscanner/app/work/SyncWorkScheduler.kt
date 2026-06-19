package com.qrscanner.app.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin facade over [WorkManager] for the cloud-sync workers. Callers
 * (scanner finalize, defaulter edit, history delete, settings 'force
 * push') invoke [enqueuePush] and forget — they never touch [WorkManager]
 * directly.
 *
 * Phase 2 ships only [enqueuePush]. [enqueuePull] lands in Phase 3 T3.3
 * and follows the same shape.
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

    /** Cancel any pending push. Called on sign-out by [SettingsScreen]. */
    fun cancelAll() {
        workManager.cancelUniqueWork(SyncPushWorker.UNIQUE_WORK_NAME)
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
