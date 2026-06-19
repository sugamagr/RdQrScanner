package com.qrscanner.app.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.cloud.CloudException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Background driver for [com.qrscanner.app.data.sync.SyncRepository.runPush]
 * per spec §8 push phase.
 *
 * Enqueued by [SyncWorkScheduler.enqueuePush] whenever the scanner
 * finalizes a session, a defaulter edit lands, or a session is deleted
 * from history. WorkManager guarantees exactly-once execution per
 * unique-work-name with [BackoffPolicy.EXPONENTIAL] retries on
 * Result.retry().
 *
 * Result semantics:
 * - [Result.success] — runPush returned Result.success. Pending count
 *   is zero (or low enough we don't care this run).
 * - [Result.retry] — runPush returned a transient failure (network,
 *   5xx, partial success with remaining DIRTY rows). WorkManager retries
 *   with exponential backoff starting at 30s, capped server-side at 5h.
 * - [Result.failure] — runPush returned an AuthExpired failure or any
 *   unrecognized exception. The output Data carries an error code so
 *   the UI can route to re-sign-in. WorkManager will NOT retry; the
 *   next push attempt must come from a new enqueue (e.g. after the
 *   user signs back in).
 */
class SyncPushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val app: QRScannerApp
        get() = applicationContext as QRScannerApp

    override suspend fun doWork(): Result {
        if (!app.isCloudConfigured) {
            Log.w(TAG, "SyncPushWorker skipped: cloud not configured")
            return Result.failure(workDataOf(KEY_ERROR to ERROR_NOT_CONFIGURED))
        }

        return try {
            val pushResult = app.syncRepository.runPush()
            when {
                pushResult.isSuccess -> Result.success()
                else -> {
                    val cause = pushResult.exceptionOrNull()
                    classify(cause)
                }
            }
        } catch (e: CancellationException) {
            // Coroutine cancelled — let WorkManager treat this as a retry
            // candidate; the next run will recover any rows left in SYNCING.
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "SyncPushWorker unexpected failure", e)
            classify(e)
        }
    }

    private fun classify(cause: Throwable?): Result {
        return when (cause) {
            is CloudException.AuthExpired ->
                Result.failure(workDataOf(KEY_ERROR to ERROR_AUTH_EXPIRED))
            is CloudException.NotConfigured ->
                Result.failure(workDataOf(KEY_ERROR to ERROR_NOT_CONFIGURED))
            // Network, server, conflict, unknown: transient. Retry with
            // exponential backoff; the next run will sweep stuck-SYNCING
            // rows and try again.
            is CloudException.Network,
            is CloudException.Server,
            is CloudException.Conflict,
            is CloudException.Unknown,
            null -> {
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    Result.failure(workDataOf(KEY_ERROR to (cause?.message ?: ERROR_UNKNOWN)))
                } else {
                    Result.retry()
                }
            }
            else -> {
                // NotImplementedError or other unrecognized — treat as
                // permanent so we don't busy-loop the worker queue.
                Result.failure(workDataOf(KEY_ERROR to (cause.message ?: ERROR_UNKNOWN)))
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sync_push"
        const val KEY_ERROR = "error"
        const val ERROR_AUTH_EXPIRED = "auth_expired"
        const val ERROR_NOT_CONFIGURED = "not_configured"
        const val ERROR_UNKNOWN = "unknown"

        private const val TAG = "SyncPushWorker"
        private const val MAX_ATTEMPTS = 12
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncPushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INITIAL_BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .build()
    }
}
