package com.qrscanner.app.data.sync

import androidx.room.withTransaction
import com.qrscanner.app.cloud.CloudClient
import com.qrscanner.app.cloud.CloudException
import com.qrscanner.app.cloud.dto.DeviceDto
import com.qrscanner.app.cloud.mappers.IsoTime
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
    private val cloudClient: CloudClient,
    private val notifier: com.qrscanner.app.notifications.SyncNotifier
) {

    private val sessionDao = database.scanSessionDao()
    private val lotDao = database.scanLotDao()
    private val rdNumberDao = database.rdNumberDao()
    private val deviceSettingsDao = database.deviceSettingsDao()

    // Track consecutive runPush failure cycles. Spec §15.5.2 fires the
    // sync_error notification on the 3rd consecutive failure and re-fires
    // every additional 6 failures so the notification stays sticky without
    // spamming on every flaky-network retry tick.
    private var consecutiveFailures: Int = 0

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

        // Two-pass recovery before each push:
        //   1. SYNCING → DIRTY for rows left by a worker killed mid-push.
        //   2. Finalized (isActive=0) sessions stuck at LOCAL_ONLY get
        //      promoted to DIRTY. This covers (a) rotation-mid-finalize
        //      orphans (scope cancels between endSession and
        //      markSessionForSync), and (b) historical sessions from a
        //      v5→v6 migration where the user finalized them before
        //      completing first-run setup — they'd be unreachable from
        //      getDirtyForPush otherwise (oracle warnings #5, #7).
        database.withTransaction {
            sessionDao.recoverStuckSyncing()
            lotDao.recoverStuckSyncing()
            rdNumberDao.recoverStuckSyncing()
            promoteOrphanFinalizedSessions()
            sessionDao.promoteSessionsWithDirtyChildren()
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

            val rdAllOk = try {
                pushRdNumbersForLots(lotIdMap, ownerId)
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                false
            }

            // Fire per-session success notification (spec §15.5.2 Channel A,
            // T2.11) BEFORE the orphan-children check below — we want to
            // confirm to the user that the session reached cloud even if a
            // late rd_number fix-up is still pending.
            if (rdAllOk) {
                runCatching {
                    val settings = deviceSettingsDao.get()
                    val deviceName = settings?.deviceName ?: ""
                    notifier.notifySessionSynced(
                        displayNumber = sess.displayNumber,
                        totalLots = sess.totalLots,
                        totalRdNumbers = sess.totalRdNumbers,
                        deviceName = deviceName
                    )
                }
            }

            // BLOCKER fix (oracle adversarial #7): if any child rd_number
            // failed, propagate that to the session so it stays in the
            // DIRTY/SYNC_ERROR set and is revisited on the next push run.
            // Without this the rd_numbers are orphaned: getDirtyForPush
            // only returns DIRTY/SYNC_ERROR sessions, and once the parent
            // session is SYNCED its DIRTY/SYNC_ERROR children are
            // unreachable from the per-session loop.
            if (!rdAllOk) {
                sessionDao.markSyncError(sess.id, "one or more rd_numbers failed; will retry")
                if (firstError == null) firstError = IllegalStateException("rd_number child failure")
            } else {
                pushedSessionCount++
            }
        }

        // BLOCKER fix (oracle correctness #1): bump the device's last_seen_at
        // on every successful push cycle. The portal's Devices page reads
        // this; without the update the owner can't tell if a phone is
        // actively syncing or hasn't been used in weeks. Wrapped in
        // runCatching so a transient failure here doesn't mask a successful
        // data push.
        runCatching { bumpDeviceLastSeen(ownerId) }
            .onFailure { android.util.Log.w("SyncRepository", "device last_seen_at bump failed", it) }

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
            consecutiveFailures += 1
            // Spec §15.5.2: surface the error notification on the 3rd
            // consecutive failure and re-fire every additional 6 so the
            // user isn't spammed on every retry tick but the badge stays
            // sticky.
            val shouldNotify = consecutiveFailures == ERROR_NOTIFY_THRESHOLD ||
                (consecutiveFailures > ERROR_NOTIFY_THRESHOLD &&
                    (consecutiveFailures - ERROR_NOTIFY_THRESHOLD) % ERROR_NOTIFY_REFIRE_EVERY == 0)
            if (shouldNotify) {
                runCatching { notifier.notifySyncError(remaining) }
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
            consecutiveFailures = 0
            runCatching { notifier.clearSyncError() }
            Result.success(Unit)
        }
    }

    /**
     * Stamps cloud identity + flips to DIRTY any finalized sessions
     * that are still LOCAL_ONLY. Used by [runPush]'s startup sweep to
     * recover orphans from rotation-during-finalize and v5→v6
     * historical sessions that were never pushed.
     */
    private suspend fun promoteOrphanFinalizedSessions() {
        val settings = deviceSettingsDao.get() ?: return
        val deviceCloudId = settings.deviceCloudId ?: return
        val operatorName = settings.operatorName
        val orphans = sessionDao.getOrphanFinalizedSessions()
        val now = System.currentTimeMillis()
        for (orphan in orphans) {
            val cloudId = orphan.cloudId ?: UUID.randomUUID().toString()
            sessionDao.stampFinalizeMetadata(
                sessionId = orphan.id,
                cloudId = cloudId,
                deviceCloudId = deviceCloudId,
                operatorName = operatorName,
                updatedAt = now
            )
            sessionDao.markSessionDirty(orphan.id, now)
            lotDao.markLotsDirtyForSession(orphan.id, now)
            rdNumberDao.markRdNumbersDirtyForSession(orphan.id, now)
        }
    }

    private suspend fun bumpDeviceLastSeen(ownerId: String) {
        val settings = deviceSettingsDao.get() ?: return
        val cloudId = settings.deviceCloudId ?: return
        val deviceName = settings.deviceName ?: return
        val nowIso = IsoTime.fromEpochMillis(System.currentTimeMillis())
        cloudClient.upsertDevice(
            DeviceDto(
                id = cloudId,
                ownerId = ownerId,
                deviceName = deviceName,
                deviceModel = android.os.Build.MODEL,
                firstSeenAt = nowIso,
                lastSeenAt = nowIso,
                appVersion = null,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        )
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

    /** Returns true iff every rd_number across every lot pushed successfully. */
    private suspend fun pushRdNumbersForLots(
        lotIdMap: Map<Long, String>,
        ownerId: String
    ): Boolean {
        var allOk = true
        for ((lotLocalId, lotCloudId) in lotIdMap) {
            val rdNumbers = rdNumberDao.getDirtyForLot(lotLocalId)
            for (rd in rdNumbers) {
                if (!pushRdNumber(rd, lotCloudId, ownerId)) {
                    allOk = false
                }
            }
        }
        return allOk
    }

    /** Returns true on success, false on non-auth failure (parent re-marks SYNC_ERROR). AuthExpired re-throws. */
    private suspend fun pushRdNumber(rd: RdNumber, lotCloudId: String, ownerId: String): Boolean {
        val cloudId = rd.cloudId ?: UUID.randomUUID().toString()
        rdNumberDao.markSyncing(rd.id)
        return try {
            val dto = RdNumberMapper.toDto(rd.copy(cloudId = cloudId), lotCloudId).copy(ownerId = ownerId)
            cloudClient.upsertRdNumber(dto)
            rdNumberDao.markSynced(rd.id, System.currentTimeMillis(), cloudId)
            true
        } catch (e: CloudException.AuthExpired) {
            rdNumberDao.markSyncError(rd.id, "auth expired")
            throw e
        } catch (e: Throwable) {
            rdNumberDao.markSyncError(rd.id, e.message ?: e.toString())
            false
        }
    }

    /**
     * Pull phase per spec §8 + §11. Queries cloud for rows with
     * updated_at > device_settings.lastPulledAt, merges into local Room
     * with last-writer-wins by updatedAt, advances the cursor.
     *
     * Per-row outcomes:
     *  - **Local missing**: insert via the mapper. Stamps syncStatus=SYNCED.
     *  - **Local present + remote newer**: mergeFromCloud UPDATE filtered
     *    by `WHERE id = :id AND updatedAt <= :updatedAt`. Idempotent.
     *  - **Local present + local newer**: merge UPDATE is a no-op (the
     *    WHERE filter excludes it). The local change pushes on the
     *    next runPush cycle. Spec §11's silent-loser pattern.
     *  - **Orphan child** (parent cloudId not yet local): skip + log.
     *    The cloud's FK CASCADE makes orphans impossible in normal
     *    operation; if one shows up it's a cloud-side integrity issue.
     *
     * One runPull = one delta page. Pagination is via the cursor across
     * cycles — pull more by calling runPull again (e.g. realtime trigger
     * in T3.4, or the lifecycle-scoped 5-min poll).
     */
    suspend fun runPull(): Result<Unit> {
        val cloudSession = cloudClient.currentSession()
            ?: return Result.failure(CloudException.AuthExpired())
        val ownerId = cloudSession.ownerId
        val settings = deviceSettingsDao.get()
            ?: return Result.failure(IllegalStateException("device_settings missing; first-run setup incomplete"))
        val since = settings.lastPulledAt

        val delta = try {
            cloudClient.pullChangesSince(ownerId, since)
        } catch (e: CloudException.AuthExpired) {
            updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
            return Result.failure(e)
        } catch (e: Throwable) {
            deviceSettingsDao.recordPullError(
                timestamp = System.currentTimeMillis(),
                error = e.message ?: e.toString()
            )
            updateSummary { it.copy(lastErrorMessage = e.message ?: e.toString()) }
            return Result.failure(e)
        }

        database.withTransaction {
            mergeSessions(delta.sessions)
            mergeLots(delta.lots)
            mergeRdNumbers(delta.rdNumbers)
            if (delta.highWaterMark > since) {
                deviceSettingsDao.updateLastPulledAt(delta.highWaterMark)
            }
        }

        val now = System.currentTimeMillis()
        updateSummary { it.copy(lastSuccessfulPullAt = now, lastErrorMessage = null) }
        return Result.success(Unit)
    }

    private suspend fun mergeSessions(dtos: List<com.qrscanner.app.cloud.dto.ScanSessionDto>) {
        for (dto in dtos) {
            val existing = sessionDao.findByCloudId(dto.id)
            val updatedAt = IsoTime.toEpochMillis(dto.updatedAt)
            val deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
            if (existing == null) {
                sessionDao.insert(SessionMapper.toEntity(dto))
            } else {
                sessionDao.mergeFromCloud(
                    id = existing.id,
                    cloudId = dto.id,
                    deviceCloudId = dto.deviceId,
                    operatorName = dto.operatorName,
                    displayNumber = dto.displayNumber,
                    startTime = IsoTime.toEpochMillis(dto.startTime),
                    endTime = IsoTime.toEpochMillis(dto.endTime),
                    totalLots = dto.totalLots,
                    totalRdNumbers = dto.totalRdNumbers,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt
                )
            }
        }
    }

    private suspend fun mergeLots(dtos: List<com.qrscanner.app.cloud.dto.ScanLotDto>) {
        for (dto in dtos) {
            val parent = sessionDao.findByCloudId(dto.sessionId)
            if (parent == null) {
                android.util.Log.w("SyncRepository", "pull: skipping lot ${dto.id} — parent session ${dto.sessionId} not local")
                continue
            }
            val existing = lotDao.findByCloudId(dto.id)
            val updatedAt = IsoTime.toEpochMillis(dto.updatedAt)
            val deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
            if (existing == null) {
                lotDao.insert(LotMapper.toEntity(dto).copy(sessionId = parent.id))
            } else {
                lotDao.mergeFromCloud(
                    id = existing.id,
                    cloudId = dto.id,
                    sessionId = parent.id,
                    lotNumber = dto.lotNumber,
                    timestamp = IsoTime.toEpochMillis(dto.timestamp),
                    updatedAt = updatedAt,
                    deletedAt = deletedAt
                )
            }
        }
    }

    private suspend fun mergeRdNumbers(dtos: List<com.qrscanner.app.cloud.dto.RdNumberDto>) {
        for (dto in dtos) {
            val parent = lotDao.findByCloudId(dto.lotId)
            if (parent == null) {
                android.util.Log.w("SyncRepository", "pull: skipping rd_number ${dto.id} — parent lot ${dto.lotId} not local")
                continue
            }
            val existing = rdNumberDao.findByCloudId(dto.id)
            val updatedAt = IsoTime.toEpochMillis(dto.updatedAt)
            val deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
            if (existing == null) {
                rdNumberDao.insert(RdNumberMapper.toEntity(dto).copy(lotId = parent.id))
            } else {
                rdNumberDao.mergeFromCloud(
                    id = existing.id,
                    cloudId = dto.id,
                    lotId = parent.id,
                    number = dto.number,
                    position = dto.position,
                    scannedAt = IsoTime.toEpochMillis(dto.scannedAt),
                    monthsPaid = dto.monthsPaid,
                    monthsList = dto.monthsList,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt
                )
            }
        }
    }

    /**
     * Called by the realtime channel handler on every postgresChange
     * payload. The payload identifies which row changed; we treat it as
     * a 'go look' trigger and run [runPull] which fetches the delta
     * since `lastPulledAt`. Targeted single-row fetch would be more
     * bandwidth-efficient but reusing the delta path keeps the cursor
     * state machine consistent and avoids a second cloud round-trip.
     *
     * Phase 3 T3.4. Spec §14.
     */
    suspend fun handleRealtimeChange(payload: com.qrscanner.app.cloud.CloudRealtimePayload) {
        android.util.Log.d(
            "SyncRepository",
            "realtime ${payload.event} on ${payload.table} cloudId=${payload.cloudId}"
        )
        runPull()
    }

    private fun updateSummary(transform: (SyncSummary) -> SyncSummary) {
        mutableSummary.value = transform(mutableSummary.value)
    }

    companion object {
        private const val ERROR_NOTIFY_THRESHOLD = 3
        private const val ERROR_NOTIFY_REFIRE_EVERY = 6
    }
}
