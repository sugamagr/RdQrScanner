package com.qrscanner.app.data.sync

import com.qrscanner.app.cloud.CloudClient
import com.qrscanner.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single point of coordination between local Room state, the
 * [CloudClient], and the UI's sync status surfaces.
 *
 * Wave 1 (Phase 1) lands this as a skeleton: the public API is fixed
 * and consumable by the auth screens, but `runPush` and `runPull`
 * throw [NotImplementedError] until Phase 2 (T2.1) and Phase 3 (T3.1)
 * fill them in.
 *
 * Lifetime: singleton, held by [com.qrscanner.app.QRScannerApp]. The
 * UI observes [summaryFlow] for the status pill; the WorkManager
 * workers call [runPush] / [runPull] from their `doWork()` bodies.
 *
 * Spec reference: §3, §8, §11, §15.5.
 */
class SyncRepository(
    private val database: AppDatabase,
    private val cloudClient: CloudClient
) {

    private val mutableSummary = MutableStateFlow(
        SyncSummary(
            state = SyncPillState.INITIALIZING,
            pendingCount = 0,
            lastSuccessfulPushAt = null,
            lastSuccessfulPullAt = null,
            lastErrorMessage = null
        )
    )

    /** Hot flow of the aggregated sync state. Observed by the status pill. */
    val summaryFlow: Flow<SyncSummary> = mutableSummary.asStateFlow()

    /**
     * Per spec §8 push phase: select all DIRTY/SYNC_ERROR rows ordered
     * parent-first (sessions → lots → rd_numbers), push each via the
     * cloud client, flip status on response.
     *
     * Filled in by Phase 2 T2.1.
     */
    suspend fun runPush(): Result<Unit> {
        return Result.failure(NotImplementedError("runPush() is Phase 2 T2.1"))
    }

    /**
     * Per spec §8 pull phase + §11 merge: query cloud for rows newer
     * than `device_settings.lastPulledAt`, run last-writer-wins merge
     * against local, advance the cursor.
     *
     * Filled in by Phase 3 T3.1.
     */
    suspend fun runPull(): Result<Unit> {
        return Result.failure(NotImplementedError("runPull() is Phase 3 T3.1"))
    }

    /**
     * Called by the realtime channel handler. Triggers a targeted pull
     * for the affected row id so we don't re-fetch the entire delta.
     *
     * Filled in by Phase 3 T3.4.
     */
    suspend fun handleRealtimeChange(payload: com.qrscanner.app.cloud.CloudRealtimePayload) {
        throw NotImplementedError("handleRealtimeChange() is Phase 3 T3.4")
    }
}
