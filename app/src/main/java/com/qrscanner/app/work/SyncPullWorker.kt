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
 * Background driver for [com.qrscanner.app.data.sync.SyncRepository.runPull]
 * per spec §8 pull phase + §11 merge.
 *
 * Enqueued by [SyncWorkScheduler.enqueuePull] on three triggers:
 *  1. App launch (always) — catch up with anything finalized while
 *     the phone was backgrounded.
 *  2. Opening SessionHistory — fresh data behind the list the user
 *     is about to look at.
 *  3. The lifecycle-scoped 5-min foreground poll (T3.4) — backstop
 *     for missed realtime payloads.
 *
 * Plus the realtime channel handler (T3.4) calls a targeted variant
 * directly via the repository instead of going through WorkManager,
 * because realtime is already on the main process lifecycle.
 *
 * Result semantics mirror [SyncPushWorker]: success/retry/failure for
 * transient vs auth vs permanent errors. The classify() helper is
 * intentionally identical so a future refactor could merge the two
 * workers into a single PullPushWorker.
 */
class SyncPullWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val app: QRScannerApp
        get() = applicationContext as QRScannerApp

    override suspend fun doWork(): Result {
        if (!app.isCloudConfigured) {
            Log.w(TAG, "SyncPullWorker skipped: cloud not configured")
            return Result.failure(workDataOf(KEY_ERROR to ERROR_NOT_CONFIGURED))
        }

        return try {
            val pullResult = app.syncRepository.runPull()
            when {
                pullResult.isSuccess -> Result.success()
                else -> classify(pullResult.exceptionOrNull())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "SyncPullWorker unexpected failure", e)
            classify(e)
        }
    }

    private fun classify(cause: Throwable?): Result {
        return when (cause) {
            is CloudException.AuthExpired ->
                Result.failure(workDataOf(KEY_ERROR to ERROR_AUTH_EXPIRED))
            is CloudException.NotConfigured ->
                Result.failure(workDataOf(KEY_ERROR to ERROR_NOT_CONFIGURED))
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
                Result.failure(workDataOf(KEY_ERROR to (cause.message ?: ERROR_UNKNOWN)))
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sync_pull"
        const val KEY_ERROR = "error"
        const val ERROR_AUTH_EXPIRED = "auth_expired"
        const val ERROR_NOT_CONFIGURED = "not_configured"
        const val ERROR_UNKNOWN = "unknown"

        private const val TAG = "SyncPullWorker"
        private const val MAX_ATTEMPTS = 12
        private const val INITIAL_BACKOFF_SECONDS = 30L

        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SyncPullWorker>()
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
