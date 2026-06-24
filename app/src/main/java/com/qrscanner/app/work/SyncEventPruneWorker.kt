package com.qrscanner.app.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.qrscanner.app.QRScannerApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background driver that enforces the [com.qrscanner.app.data.SyncEvent]
 * table's bounded-retention contract: at most 100 most-recent rows AND
 * nothing older than 7 days, whichever is smaller (see
 * [com.qrscanner.app.data.SyncEvent] KDoc + spec §15.5.5).
 *
 * The cap exists so an offline phone catching up after a long absence
 * doesn't materialize thousands of irrelevant banner entries, and so
 * the table doesn't grow unboundedly on long-lived installs.
 *
 * Scheduled as a daily periodic worker by [SyncWorkScheduler.scheduleEventPruning]
 * with no network constraint — pruning is pure local DB maintenance, no
 * sync involved. KEEP semantics on the unique work name so a fresh
 * install doesn't drop a still-pending first prune.
 */
class SyncEventPruneWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val app: QRScannerApp
        get() = applicationContext as QRScannerApp

    override suspend fun doWork(): Result {
        return try {
            val sevenDaysAgo = System.currentTimeMillis() - SEVEN_DAYS_MS
            app.database.syncEventDao().pruneOldEvents(
                keepCount = KEEP_COUNT,
                olderThan = sevenDaysAgo
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Pruning failures are observability concerns — the table
            // is bounded by the data growth rate, not by this worker's
            // success. A failed run just means the table is slightly
            // larger; the next scheduled run will catch up. Don't
            // retry aggressively because retrying a DB prune is the
            // same operation that just failed; let the next periodic
            // tick handle it.
            Log.w(TAG, "SyncEventPruneWorker failed", e)
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sync_event_prune"
        const val KEEP_COUNT = 100
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val TAG = "SyncEventPruneWorker"

        fun buildRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncEventPruneWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        // No network requirement — local DB maintenance.
                        // Battery-not-low so we don't drain on low batt.
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
    }
}
