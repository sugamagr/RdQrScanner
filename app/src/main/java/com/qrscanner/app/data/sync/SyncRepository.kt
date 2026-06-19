package com.qrscanner.app.data.sync

import androidx.room.withTransaction
import com.qrscanner.app.cloud.CloudClient
import com.qrscanner.app.cloud.CloudException
import com.qrscanner.app.cloud.mappers.LotMapper
import com.qrscanner.app.cloud.mappers.RdNumberMapper
import com.qrscanner.app.cloud.mappers.SessionMapper
import com.qrscanner.app.data.AppDatabase
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.data.ScanLot
import com.qrscanner.app.data.ScanSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Coordinates Room ↔ CloudClient. UI observes [summaryFlow] for the
 * status pill; WorkManager workers call [runPush] / [runPull].
 *
 * Spec reference: §3, §8, §11, §15.5.
 */
class SyncRepository(
    private val database: AppDatabase,
    private val cloudClient: CloudClient
) {

    private val sessionDao = database.scanSessionDao()
    private val lotDao = database.scanLotDao()
    private val rdNumberDao = database.rdNumberDao()
    private val deviceSettingsDao = database.deviceSettingsDao()

    private val mutableSummary = MutableStateFlow(
        SyncSummary(
            state = SyncPillState.INITIALIZING,
            pendingCount = 0,
            lastSuccessfulPushAt = null,
            lastSuccessfulPullAt = null,
            lastErrorMessage = null
        )
    )

    val summaryFlow: Flow<SyncSummary> = mutableSummary.asStateFlow()

    /**
     * Promotes a just-finalized session subtree from LOCAL_ONLY to DIRTY,
     * stamping cloud identity on the parent. Called by the scanner's
     * `finalizeSession` immediately before enqueuing the sync worker
     * (Phase 2 T2.4). All five writes happen inside a single Room
     * transaction so a process kill between any pair can never leave a
     * session half-promoted (e.g. cloudId set but children still
     * LOCAL_ONLY would be unsyncable forever).
     *
     * The cloud's [CloudClient.nextDisplayNumber] RPC is invoked BEFORE the
     * transaction (network IO never runs inside Room transactions). The
     * RPC acquires a Postgres advisory lock per spec §5 so two phones
     * finalizing simultaneously cannot collide on the same display number
     * (Phase 2 T2.7). On failure (offline, paused project, auth expired,
     * etc.) we fall back to the local tentative number — the push
     * pipeline's pushSession reconciles by overwriting local
     * displayNumber if the server returns a different value.
     */
    suspend fun markSessionForSync(sessionId: Long) {
        val settings = deviceSettingsDao.get()
        val deviceCloudId = settings?.deviceCloudId
            ?: throw IllegalStateException("markSessionForSync called before first-run setup")
        val ownerId = settings.ownerId
            ?: throw IllegalStateException("markSessionForSync called before first-run setup")
        val operatorName = settings.operatorName
        val cloudId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val claimedDisplayNumber: Int? = runCatching { cloudClient.nextDisplayNumber(ownerId) }
            .onFailure { android.util.Log.w("SyncRepository", "nextDisplayNumber RPC failed; using local tentative number", it) }
            .getOrNull()

        database.withTransaction {
            sessionDao.stampFinalizeMetadata(
                sessionId = sessionId,
                cloudId = cloudId,
                deviceCloudId = deviceCloudId,
                operatorName = operatorName,
                updatedAt = now
            )
            if (claimedDisplayNumber != null) {
                sessionDao.updateDisplayNumber(sessionId, claimedDisplayNumber)
            }
            sessionDao.markSessionDirty(sessionId, now)
            lotDao.markLotsDirtyForSession(sessionId, now)
            rdNumberDao.markRdNumbersDirtyForSession(sessionId, now)
        }
    }

    /**
     * Deletes a session from history. Picks hard-delete vs soft-delete
     * based on whether the session was ever pushed to cloud:
     *
     * - **Never-pushed** (cloudId == null): hard-delete the session +
     *   its lots + rd_numbers. Nothing in cloud to tombstone.
     *
     * - **Already pushed** (cloudId != null): cascade soft-delete the
     *   session subtree by stamping deletedAt + bumping updatedAt +
     *   flipping syncStatus to DIRTY. The push worker picks up the
     *   tombstones and propagates them to cloud, which broadcasts
     *   them via realtime to other devices per spec §11.
     *
     * Wrapped in withTransaction so a process kill mid-delete can't
     * leave parent soft-deleted while children are still alive.
     */
    suspend fun softDeleteSession(sessionId: Long) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        val now = System.currentTimeMillis()
        if (session.cloudId == null) {
            database.withTransaction {
                rdNumberDao.deleteForSession(sessionId)
                lotDao.deleteLotsForSession(sessionId)
                sessionDao.deleteById(sessionId)
            }
        } else {
            database.withTransaction {
                rdNumberDao.softDeleteForSession(sessionId, now)
                lotDao.softDeleteForSession(sessionId, now)
                sessionDao.softDelete(sessionId, now)
            }
        }
    }

    /**
     * Push phase per spec §8. Walks DIRTY/SYNC_ERROR sessions in
     * updated_at ASC order. For each: pushes the session first, then
     * its DIRTY/SYNC_ERROR LOTs, then each LOT's DIRTY/SYNC_ERROR RD
     * numbers. Children inherit the cloudId/owner of their parent at
     * push time, so the cloud foreign keys always resolve.
     *
     * Returns Result.failure with [CloudException.AuthExpired] if auth
     * is missing — the worker translates this to Result.failure() with
     * no retry, surfaces re-sign-in UI. Network / 5xx errors flag rows
     * as SYNC_ERROR and return Result.failure(...) so WorkManager
     * retries with exponential backoff.
     */
    suspend fun runPush(): Result<Unit> {
        val session = cloudClient.currentSession()
            ?: return Result.failure(CloudException.AuthExpired())
        // We snapshot via currentSession() and accept the small race
        // window where signOut happens between here and the first cloud
        // call — runCloud's 401 mapping converts that to AuthExpired.
        val ownerId = session.ownerId

        // Recover from a worker killed mid-push: flip any SYNCING rows
        // back to DIRTY so they're visible to getDirtyForPush. Without
        // this they're frozen forever.
        database.withTransaction {
            sessionDao.recoverStuckSyncing()
            lotDao.recoverStuckSyncing()
            rdNumberDao.recoverStuckSyncing()
        }

        val dirtySessions = sessionDao.getDirtyForPush()
        if (dirtySessions.isEmpty()) {
            updateSummary { it.copy(state = SyncPillState.SYNCED, pendingCount = 0) }
            return Result.success(Unit)
        }

        updateSummary { it.copy(state = SyncPillState.SYNCING, pendingCount = dirtySessions.size) }

        var firstError: Throwable? = null
        var pushedSessionCount = 0

        for (sess in dirtySessions) {
            val sessionCloudId = try {
                pushSession(sess, ownerId)
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                sessionDao.markSyncError(sess.id, e.message ?: e.toString())
                continue
            }

            val lotIdMap = try {
                pushLotsForSession(sess.id, sessionCloudId, ownerId)
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                continue
            }

            try {
                pushRdNumbersForLots(lotIdMap, ownerId)
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                continue
            }

            pushedSessionCount++
        }

        val now = System.currentTimeMillis()
        val remaining = sessionDao.getDirtyForPush(limit = 1).size
        return if (firstError != null) {
            updateSummary {
                it.copy(
                    state = SyncPillState.ERROR,
                    pendingCount = remaining,
                    lastErrorMessage = firstError.message ?: firstError.toString()
                )
            }
            Result.failure(firstError)
        } else {
            updateSummary {
                it.copy(
                    state = if (remaining == 0) SyncPillState.SYNCED else SyncPillState.PENDING,
                    pendingCount = remaining,
                    lastSuccessfulPushAt = now,
                    lastErrorMessage = null
                )
            }
            Result.success(Unit)
        }
    }

    private suspend fun pushSession(sess: ScanSession, ownerId: String): String {
        val cloudId = sess.cloudId ?: throw IllegalStateException(
            "session ${sess.id} reached push without cloudId — finalize path didn't stamp it"
        )
        sessionDao.markSyncing(sess.id)
        val dto = SessionMapper.toDto(sess).copy(ownerId = ownerId)
        val result = cloudClient.upsertSession(dto)
        val displayNumber = result.displayNumber
        if (displayNumber != sess.displayNumber) {
            sessionDao.update(sess.copy(displayNumber = displayNumber, cloudId = cloudId))
        }
        sessionDao.markSynced(sess.id, System.currentTimeMillis(), cloudId)
        return cloudId
    }

    private suspend fun pushLotsForSession(
        sessionLocalId: Long,
        sessionCloudId: String,
        ownerId: String
    ): Map<Long, String> {
        val lots = lotDao.getDirtyForSession(sessionLocalId)
        val resolved = mutableMapOf<Long, String>()
        for (lot in lots) {
            try {
                resolved[lot.id] = pushLot(lot, sessionCloudId, ownerId)
            } catch (e: CloudException.AuthExpired) {
                throw e
            } catch (e: Throwable) {
                lotDao.markSyncError(lot.id, e.message ?: e.toString())
                throw e
            }
        }
        return resolved
    }

    private suspend fun pushLot(lot: ScanLot, sessionCloudId: String, ownerId: String): String {
        val cloudId = lot.cloudId ?: UUID.randomUUID().toString()
        lotDao.markSyncing(lot.id)
        val dto = LotMapper.toDto(lot.copy(cloudId = cloudId), sessionCloudId).copy(ownerId = ownerId)
        cloudClient.upsertLot(dto)
        lotDao.markSynced(lot.id, System.currentTimeMillis(), cloudId)
        return cloudId
    }

    private suspend fun pushRdNumbersForLots(
        lotIdMap: Map<Long, String>,
        ownerId: String
    ) {
        for ((lotLocalId, lotCloudId) in lotIdMap) {
            val rdNumbers = rdNumberDao.getDirtyForLot(lotLocalId)
            for (rd in rdNumbers) {
                pushRdNumber(rd, lotCloudId, ownerId)
            }
        }
    }

    private suspend fun pushRdNumber(rd: RdNumber, lotCloudId: String, ownerId: String) {
        val cloudId = rd.cloudId ?: UUID.randomUUID().toString()
        rdNumberDao.markSyncing(rd.id)
        try {
            val dto = RdNumberMapper.toDto(rd.copy(cloudId = cloudId), lotCloudId).copy(ownerId = ownerId)
            cloudClient.upsertRdNumber(dto)
            rdNumberDao.markSynced(rd.id, System.currentTimeMillis(), cloudId)
        } catch (e: CloudException.AuthExpired) {
            rdNumberDao.markSyncError(rd.id, "auth expired")
            throw e
        } catch (e: Throwable) {
            rdNumberDao.markSyncError(rd.id, e.message ?: e.toString())
        }
    }

    /** Pull phase per spec §8 + §11. Filled in by Phase 3 T3.1. */
    suspend fun runPull(): Result<Unit> {
        return Result.failure(NotImplementedError("runPull() is Phase 3 T3.1"))
    }

    /** Realtime targeted pull. Filled in by Phase 3 T3.4. */
    suspend fun handleRealtimeChange(payload: com.qrscanner.app.cloud.CloudRealtimePayload) {
        throw NotImplementedError("handleRealtimeChange() is Phase 3 T3.4")
    }

    private fun updateSummary(transform: (SyncSummary) -> SyncSummary) {
        mutableSummary.value = transform(mutableSummary.value)
    }
}
