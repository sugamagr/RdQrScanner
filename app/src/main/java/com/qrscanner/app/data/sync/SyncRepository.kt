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
import com.qrscanner.app.data.SyncEvent
import com.qrscanner.app.data.SyncEventType
import com.qrscanner.app.data.SyncStatus
import com.qrscanner.app.notifications.SyncNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val notifier: SyncNotifier,
    private val syncPrefs: android.content.SharedPreferences? = null
) {

    private val sessionDao = database.scanSessionDao()
    private val lotDao = database.scanLotDao()
    private val rdNumberDao = database.rdNumberDao()
    private val deviceSettingsDao = database.deviceSettingsDao()
    private val syncEventDao = database.syncEventDao()
    private val rdAccountDao = database.rdAccountDao()

    // Track consecutive runPush failure cycles. Spec §15.5.2 fires the
    // sync_error notification on the 3rd consecutive failure and re-fires
    // every additional 6 failures so the notification stays sticky without
    // spamming on every flaky-network retry tick.
    //
    // Persisted via SharedPreferences (NOT Room) so app-restart doesn't
    // reset the counter and defeat the threshold — without persistence,
    // a force-kill between two failure cycles drops the counter to 0
    // and the user never sees the "Sync paused" notification even
    // though sync has been failing for hours. Prefs avoid a v9→v10
    // schema bump for a notifier-state counter (the value is purely
    // observability, never user data, so the schema is unaffected).
    // syncPrefs is nullable for the legacy constructor signature used
    // by older instantiation paths; when null we fall back to the
    // in-memory counter (no persistence, but the runtime behavior of
    // the rest of the sync flow is unchanged).
    private val consecutiveFailuresMutex = Mutex()

    private suspend fun readConsecutiveFailures(): Int = consecutiveFailuresMutex.withLock {
        syncPrefs?.getInt(KEY_CONSECUTIVE_FAILURES, 0) ?: inMemoryFailures
    }

    private suspend fun writeConsecutiveFailures(value: Int) = consecutiveFailuresMutex.withLock {
        val prefs = syncPrefs
        if (prefs != null) {
            prefs.edit().putInt(KEY_CONSECUTIVE_FAILURES, value).apply()
        } else {
            inMemoryFailures = value
        }
    }

    @Volatile
    private var inMemoryFailures: Int = 0

    /**
     * Serializes sign-out against in-flight runPush / runPull so the
     * three operations form a coherent total order. Without this, a
     * mid-push sign-out would call wipeAllUserData() while runPush is
     * still iterating dirty rows, producing crashes (deleted rows
     * being marked SYNCED) or owner-cross-contamination (rows pushed
     * to owner A after the user signed in as owner B).
     *
     * Uses the same syncMutex as runPush/runPull so push/pull/sign-out
     * are mutually exclusive at the repository level. The caller passes
     * the entire sign-out sequence (cancelAll + signOut + wipe + clearOwner)
     * inside the block so the lock spans the whole transition.
     */
    suspend fun <T> withSyncLock(block: suspend () -> T): T = syncMutex.withLock { block() }

    // Phase 5 T5.2 (F3 finding): serialize runPush + runPull so realtime
    // (which calls runPull directly from handleRealtimeChange) cannot
    // race with the 5-min foreground poll or WorkManager backstop. Without
    // this, two concurrent runPull invocations both write to
    // device_settings.lastPulledAt — if the slower finishes second with
    // an older highWaterMark the cursor regresses and rows are
    // re-processed. The mutex is non-fair (FIFO not guaranteed) but
    // each section is short (one delta page) so starvation isn't a risk.
    private val syncMutex = Mutex()

    private val mutableSummary = MutableStateFlow(
        SyncSummary(
            state = SyncPillState.INITIALIZING,
            pendingCount = 0,
            lastSuccessfulPushAt = null,
            lastSuccessfulPullAt = null,
            lastErrorMessage = null
        )
    )

    /**
     * State machine invariant (oracle bg_0ea195ce R1, R4, I9):
     *
     * The pill pendingCount + derived state are produced by combining:
     *  - mutableSummary: lifecycle-controlled fields (last*At, error msg,
     *    transient SYNCING/ERROR/SCHEMA_MISSING/INITIALIZING states),
     *  - sessionDao.observePendingCount(): the LIVE row count from Room
     *    of DIRTY/SYNC_ERROR/non-active sessions.
     *
     * Pill state derivation is centralized here so HomeScreen no longer
     * has to combine two independent data sources (which raced — the
     * old logic could show PENDING because the Room Flow emitted faster
     * than the post-push state reset, even though the DB had nothing
     * left to push).
     *
     * Priority order:
     *  1. NOT_SIGNED_IN / Initializing — driven externally by HomeScreen
     *     via CloudSessionStatus; we only emit it when set by callers
     *  2. SCHEMA_MISSING — blocking setup state, beats live count
     *  3. SYNCING — transient mid-cycle marker
     *  4. ERROR — full-fail; partial-success uses PENDING per d1d13fc
     *  5. liveCount > 0 -> PENDING
     *  6. else -> SYNCED
     */
    val summaryFlow: Flow<SyncSummary> =
        combine(mutableSummary, sessionDao.observePendingCount()) { summary, liveCount ->
            derivePillSummary(summary, liveCount)
        }.distinctUntilChanged()

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

        // Compute lastPaidThrough updates OUTSIDE the main transaction:
        // resolveOrAuto + parseList are CPU work, and the monotonic
        // upsert runs inside the same transaction below so the entire
        // finalize is still atomic.
        val rdRows = rdNumberDao.getAllRowsInSession(sessionId)
        val lots = lotDao.getLotsForSessionSync(sessionId).associateBy { it.id }
        // (rdNumber, max-month-token) pairs — one entry per distinct
        // rd_number scanned in this session, using the latest month
        // across all scans of that number (covers the case where the
        // same RD was scanned in multiple LOTs of the same session).
        val accountUpdates: Map<String, String> = rdRows
            .filter { it.deletedAt == null && it.monthsPaid >= 1 }
            .groupBy { it.number }
            .mapValues { (_, scans) ->
                scans.maxOf { scan ->
                    val anchor = lots[scan.lotId]
                        ?.let { com.qrscanner.app.util.MonthYear.fromEpochMillis(it.timestamp) }
                        ?: com.qrscanner.app.util.MonthYear.current()
                    val months = com.qrscanner.app.util.MonthYear
                        .resolveOrAuto(scan.monthsList, scan.monthsPaid, anchor)
                    // newest month per the contiguous-block invariant —
                    // index 0 since resolveOrAuto returns newest-first
                    months.first().toToken()
                }
            }

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

            // updateLastPaidThroughMonotonic is a no-op when the rd_number
            // has no matching rd_accounts row, and its WHERE clause enforces
            // the strictly-greater rule (Phase 5 T5.7 LWW tie-breaker).
            for ((rdNumber, newMonth) in accountUpdates) {
                rdAccountDao.updateLastPaidThroughMonotonic(rdNumber, newMonth, now)
            }
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
        val now = System.currentTimeMillis()
        // Read MUST happen inside the transaction. Pulling cloudId
        // outside opens a TOCTOU race: a concurrent pull worker or
        // realtime UPDATE can stamp cloudId on the local row between
        // the read and the branch decision. If the read sees the
        // stale cloudId==null and the branch hard-deletes, the cloud
        // copy survives untouched and the next pull resurrects the
        // row we just tried to delete. Re-reading inside the
        // transaction serialises against any concurrent writer per
        // Room's per-transaction lock semantics (verified against
        // SQLite WAL contract). bg_f0d47c71 R3 F1.
        database.withTransaction {
            val session = sessionDao.getSessionById(sessionId) ?: return@withTransaction
            applyR3RevertCascade(session, now)
            if (session.cloudId == null) {
                rdNumberDao.deleteForSession(sessionId)
                lotDao.deleteLotsForSession(sessionId)
                sessionDao.deleteById(sessionId)
            } else {
                rdNumberDao.softDeleteForSession(sessionId, now)
                lotDao.softDeleteForSession(sessionId, now)
                sessionDao.softDelete(sessionId, now)
            }
        }
    }

    /**
     * R3 revert-cascade: on session delete, roll back the session's
     * contribution to each affected [com.qrscanner.app.data.RdAccount.lastPaidThrough]
     * BUT ONLY when the session is newer than the last authoritative
     * CSV import for that account. If the CSV came AFTER the session
     * (session.endTime <= account.csvImportedAt), the CSV is
     * authoritative and the session is dropped silently — that scan
     * predated the current CSV truth and cannot regress it.
     *
     * Algorithm per user's rule:
     *   1. Group session's rd_numbers by rd_number.
     *   2. For each affected account:
     *      a. Look up account. Skip if missing (rd_number scanned but
     *         no account row — nothing to revert).
     *      b. Guard: if csvImportedAt != null && session.endTime <=
     *         csvImportedAt, skip. CSV wins.
     *      c. Recompute lastPaidThrough from ALL surviving scans
     *         (excluding this session, tombstones filtered). Max
     *         resolved month wins; if no survivors, NULL.
     *      d. Apply the recomputed value via setLastPaidThroughExplicit
     *         (non-null) or clearLastPaidThrough (null). Both mark
     *         DIRTY so the change propagates to cloud + other devices.
     *
     * MUST run inside the same transaction that tombstones the
     * session's rd_numbers. Recomputing after the tombstone (but before
     * the transaction commits) means the DAO read sees a consistent
     * "post-delete" view: the excluded-session-id filter is a defence-
     * in-depth guard even if the tombstones landed first.
     *
     * Sync propagation: the writes here mark rd_accounts DIRTY. The
     * next runPush cycle sends them to cloud; realtime broadcasts the
     * update to other devices. Cross-device consistency is thereby
     * eventual, not instant — matches every other Room->cloud path.
     */
    private suspend fun applyR3RevertCascade(session: ScanSession, now: Long) {
        val rdRows = rdNumberDao.getAllRowsInSession(session.id)
        if (rdRows.isEmpty()) return
        val affectedRdNumbers = rdRows.filter { it.deletedAt == null }
            .map { it.number }
            .distinct()
        if (affectedRdNumbers.isEmpty()) return
        val sessionAnchor = session.endTime ?: session.startTime
        for (rdNumber in affectedRdNumbers) {
            val account = rdAccountDao.findByRdNumberIncludingDeleted(rdNumber) ?: continue
            val csvStamp = account.csvImportedAt
            if (csvStamp != null && sessionAnchor <= csvStamp) continue
            val survivors = rdNumberDao.getSurvivingScansForRdNumberExcludingSession(
                rdNumber = rdNumber,
                excludeSessionId = session.id
            )
            val lotIdsInPlay = survivors.map { it.lotId }.distinct()
            val anchorsByLotId: Map<Long, com.qrscanner.app.util.MonthYear> = if (lotIdsInPlay.isEmpty()) {
                emptyMap()
            } else {
                lotIdsInPlay.mapNotNull { lotId ->
                    val lot = lotDao.findById(lotId) ?: return@mapNotNull null
                    lotId to com.qrscanner.app.util.MonthYear.fromEpochMillis(lot.timestamp)
                }.toMap()
            }
            val recomputedToken: String? = survivors
                .filter { it.monthsPaid >= 1 }
                .mapNotNull { scan ->
                    val anchor = anchorsByLotId[scan.lotId]
                        ?: com.qrscanner.app.util.MonthYear.current()
                    com.qrscanner.app.util.MonthYear
                        .resolveOrAuto(scan.monthsList, scan.monthsPaid, anchor)
                        .firstOrNull()
                        ?.toToken()
                }
                .maxOrNull()
            if (recomputedToken != null) {
                rdAccountDao.setLastPaidThroughExplicit(rdNumber, recomputedToken, now)
            } else {
                rdAccountDao.clearLastPaidThrough(rdNumber, now)
            }
        }
    }

    /**
     * Bulk variant of [softDeleteSession] for History's "delete selected"
     * and "clear all" paths. All N sessions are deleted inside a SINGLE
     * Room transaction so partial cancellation can't leave the UI with a
     * half-deleted subset (F2 from Phase 3 boundary review).
     *
     * Hard-delete (never-pushed) and soft-delete (already-pushed) rows are
     * batched in the same transaction — the per-session branch is decided
     * by inspecting cloudId inside the transaction.
     */
    suspend fun softDeleteSessions(sessionIds: Collection<Long>) {
        if (sessionIds.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            for (id in sessionIds) {
                val session = sessionDao.getSessionById(id) ?: continue
                applyR3RevertCascade(session, now)
                if (session.cloudId == null) {
                    rdNumberDao.deleteForSession(id)
                    lotDao.deleteLotsForSession(id)
                    sessionDao.deleteById(id)
                } else {
                    rdNumberDao.softDeleteForSession(id, now)
                    lotDao.softDeleteForSession(id, now)
                    sessionDao.softDelete(id, now)
                }
            }
        }
    }

    /**
     * Push phase per spec §8 + §17.
     *
     * Push order (master-data first, child rows last):
     *   1. **rd_accounts** — independent master data with no FK to other
     *      tables. Pushed first so a portal user opening the Accounts
     *      page during sync sees consistent state.
     *   2. **scan_sessions** — DIRTY/SYNC_ERROR rows in updated_at ASC.
     *   3. **scan_lots** per session — children of (2).
     *   4. **rd_numbers** per lot — leaves of the tree.
     *
     * Within (2)-(4), children inherit the cloudId/owner of their
     * parent at push time, so cloud foreign keys always resolve.
     *
     * Returns Result.failure with [CloudException.AuthExpired] if auth
     * is missing — the worker translates this to Result.failure() with
     * no retry, surfaces re-sign-in UI. Network / 5xx errors flag rows
     * as SYNC_ERROR and return Result.failure(...) so WorkManager
     * retries with exponential backoff.
     */
    suspend fun runPush(): Result<Unit> = syncMutex.withLock { runPushLocked() }

    private suspend fun runPushLocked(): Result<Unit> {
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
            rdAccountDao.recoverStuckSyncing()
            promoteOrphanFinalizedSessions()
            sessionDao.promoteSessionsWithDirtyChildren(System.currentTimeMillis())
        }

        val dirtySessions = sessionDao.getDirtyForPush()
        val dirtyAccounts = rdAccountDao.getDirtyForPush()
        // Release-build breadcrumb: a stuck-PENDING session is the
        // single hardest support case ("how many rows? which ones?")
        // and the answer used to live only in adb-attached logs. One
        // INFO line per push cycle costs us nothing and tells a
        // future support session exactly what was on the wire.
        android.util.Log.i(
            "SyncRepository",
            "push start: sessions=${dirtySessions.size} accounts=${dirtyAccounts.size}"
        )
        if (dirtySessions.isEmpty() && dirtyAccounts.isEmpty()) {
            updateSummary { it.copy(state = SyncPillState.SYNCED, pendingCount = 0) }
            return Result.success(Unit)
        }

        updateSummary {
            it.copy(
                state = SyncPillState.SYNCING,
                pendingCount = dirtySessions.size + dirtyAccounts.size
            )
        }

        var firstError: Throwable? = null
        var pushedSessionCount = 0
        var pushedAccountCount = 0

        // Push rd_accounts FIRST (master-data, independent of session
        // hierarchy). Future versions may add FK from rd_numbers to
        // rd_accounts; pushing accounts first means the FK target
        // exists by the time rd_numbers push starts.
        val ownDeviceCloudId = deviceSettingsDao.get()?.deviceCloudId
        for (acct in dirtyAccounts) {
            try {
                pushRdAccount(acct, ownerId, ownDeviceCloudId)
                pushedAccountCount++
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                rdAccountDao.markSyncError(acct.rdNumber, e.message ?: e.toString())
                val current = rdAccountDao.findByRdNumberIncludingDeleted(acct.rdNumber)
                if (current != null && current.retryCount >= PUSH_ABANDON_THRESHOLD) {
                    rdAccountDao.markSyncAbandoned(acct.rdNumber)
                    android.util.Log.w(
                        "SyncRepository",
                        "rd_account ${acct.rdNumber} abandoned after ${current.retryCount} push failures"
                    )
                    runCatching { notifier.notifySyncAbandoned("account", "RD ${acct.rdNumber}") }
                }
            }
        }
        // Buffer success notifications; small batches fire per-session
        // (responsive), batches > BULK_SUMMARY_THRESHOLD collapse into one
        // tray slot (avoids spam on v5→v6 first push / offline backlog).
        data class SyncedNotice(
            val displayNumber: Int,
            val totalLots: Int,
            val totalRdNumbers: Int
        )
        val pendingNotices = mutableListOf<SyncedNotice>()

        for (sess in dirtySessions) {
            val sessionCloudId = try {
                pushSession(sess, ownerId)
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                sessionDao.markSyncError(sess.id, e.message ?: e.toString())
                // R3 circuit breaker — see pushRdNumber catch.
                val currentSess = sessionDao.getSessionById(sess.id)
                if (currentSess != null && currentSess.retryCount >= PUSH_ABANDON_THRESHOLD) {
                    sessionDao.markSyncAbandoned(sess.id)
                    android.util.Log.w(
                        "SyncRepository",
                        "scan_session ${sess.id} abandoned after ${currentSess.retryCount} push failures"
                    )
                    runCatching { notifier.notifySyncAbandoned("session", "Session #${sess.displayNumber}") }
                }
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
                pushRdNumbersForLots(lotIdMap, ownerId, sess.deviceCloudId)
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: Throwable) {
                if (firstError == null) firstError = e
                false
            }

            // Buffer success per spec §15.5.2 Channel A (T2.11); we fire
            // the actual notifications after the loop so a large batch
            // collapses into one bulk summary instead of N tray slots.
            if (rdAllOk) {
                pendingNotices.add(
                    SyncedNotice(
                        displayNumber = sess.displayNumber,
                        totalLots = sess.totalLots,
                        totalRdNumbers = sess.totalRdNumbers
                    )
                )
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

        // Drain buffered success notices: per-session for small batches,
        // single bulk summary above the threshold (spec §15.5.2 minimal-noise).
        if (pendingNotices.isNotEmpty()) {
            runCatching {
                if (pendingNotices.size > SyncNotifier.BULK_SUMMARY_THRESHOLD) {
                    notifier.notifyBulkSessionsSynced(pendingNotices.map { it.displayNumber })
                } else {
                    val settings = deviceSettingsDao.get()
                    val actorLabel = settings?.operatorName?.takeIf { it.isNotBlank() }
                        ?: settings?.deviceName?.takeIf { it.isNotBlank() }
                        ?: ""
                    for (n in pendingNotices) {
                        notifier.notifySessionSynced(
                            displayNumber = n.displayNumber,
                            totalLots = n.totalLots,
                            totalRdNumbers = n.totalRdNumbers,
                            actorLabel = actorLabel
                        )
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        // Remaining = dirty sessions + dirty accounts (both kinds of work).
        // Limit-1 probes catch the common "still has something pending"
        // case without scanning the full table.
        val remaining = sessionDao.getDirtyForPush(limit = 1).size +
            rdAccountDao.getDirtyForPush(limit = 1).size
        // Either kind of progress qualifies as "partial success" for
        // the pill-doesn't-scream-red invariant from Phase 5 R5.
        val anyProgress = pushedSessionCount > 0 || pushedAccountCount > 0

        // Bump device heartbeat + diagnostics on every push exit (success
        // OR failure). Cloud schema v11 columns (last_sync_error,
        // pending_count, last_push_at) let the portal's Devices page
        // show truth: a phone with persistent push failures shows the
        // error message; a clean cycle clears it. Wrapped in runCatching
        // so a transient device-upsert failure here doesn't mask the
        // primary outcome of the push cycle below.
        runCatching {
            bumpDeviceLastSeen(
                ownerId = ownerId,
                pendingCount = remaining,
                lastError = firstError?.let { it.message ?: it.toString() },
                lastPushAtMillis = now
            )
        }.onFailure { android.util.Log.w("SyncRepository", "device diagnostics push failed", it) }

        return if (firstError != null) {
            // Phase 5 T5.1: SchemaMissing is the user's setup, not a flaky
            // network — route to SCHEMA_MISSING pill + suppress the
            // sync-error tray spam. Diagnostics screen shows the one-shot
            // "paste cloud/schema.sql" hint.
            val isSchemaMissing = firstError is CloudException.SchemaMissing
            // Mixed-outcome cycle: if at least one session pushed cleanly
            // (notification already fired), the pill must not scream
            // ERROR while a Channel A "synced" notification sits in the
            // tray — that contradiction was confusing users into thinking
            // sync was broken when data actually landed. Treat partial
            // success as PENDING (work to retry) instead of ERROR; the
            // failed session stays DIRTY/SYNC_ERROR for the next cycle.
            // SchemaMissing always wins because it's blocking, not retry-able.
            val pillState = when {
                isSchemaMissing -> SyncPillState.SCHEMA_MISSING
                anyProgress -> SyncPillState.PENDING
                else -> SyncPillState.ERROR
            }
            updateSummary {
                it.copy(
                    state = pillState,
                    pendingCount = remaining,
                    lastErrorMessage = firstError.message ?: firstError.toString()
                )
            }
            val failures = readConsecutiveFailures() + 1
            writeConsecutiveFailures(failures)
            // Spec §15.5.2: surface the error notification on the 3rd
            // consecutive failure and re-fire every additional 6 so the
            // user isn't spammed on every retry tick but the badge stays
            // sticky. Suppress for SCHEMA_MISSING (pill already explains)
            // AND for partial success (PENDING is not user-actionable
            // worth a tray slot; the per-session success notifications
            // already told the user what landed).
            val shouldNotify = !isSchemaMissing &&
                !anyProgress &&
                (
                    failures == ERROR_NOTIFY_THRESHOLD ||
                    (failures > ERROR_NOTIFY_THRESHOLD &&
                        (failures - ERROR_NOTIFY_THRESHOLD) % ERROR_NOTIFY_REFIRE_EVERY == 0)
                )
            if (shouldNotify) {
                runCatching { notifier.notifySyncError(remaining) }
            }
            // R5 (oracle bg_1eadd75b BLOCKER): clear any stale
            // 'Sync paused' tray notification on partial recovery. The
            // user just got fresh per-session success notifications;
            // leaving a contradicting error notification next to them
            // is the exact UX bug d1d13fc fixed at the pill level.
            if (anyProgress) {
                runCatching { notifier.clearSyncError() }
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
            writeConsecutiveFailures(0)
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
        // Per-orphan transaction: a process kill between stampFinalizeMetadata
        // and markRdNumbersDirtyForSession would leave the cloudId stamped
        // on the session while children stay LOCAL_ONLY — the session
        // would then never push children because the orphan sweep only
        // matches sessions WITHOUT cloudId. Wrapping each orphan in its
        // own transaction (not the whole loop) means a partial failure
        // on orphan N still leaves orphans 0..N-1 fully promoted and
        // the next push cycle resumes from orphan N.
        for (orphan in orphans) {
            val cloudId = orphan.cloudId ?: UUID.randomUUID().toString()
            database.withTransaction {
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
    }

    /**
     * Pushes device diagnostics (cloud schema v11) along with the
     * heartbeat. Called from runPushLocked() on both success and
     * failure exit paths so the portal Devices page always reflects
     * the same picture the in-app sync pill shows for this phone.
     *
     * Param contract:
     *  - pendingCount: rows still DIRTY/SYNC_ERROR after this cycle;
     *    matches the `remaining` count surfaced on the sync pill.
     *  - lastError: null on a clean cycle, error message string on
     *    failure. Null EXPLICITLY clears a stale prior error — see
     *    EncodeDefault on DeviceDto.
     *  - lastPushAtMillis: epoch millis when runPush() finished
     *    (success or failure), distinct from last_seen_at which
     *    bumps on any cloud touch (pull, realtime auth, etc.).
     */
    private suspend fun bumpDeviceLastSeen(
        ownerId: String,
        pendingCount: Int,
        lastError: String?,
        lastPushAtMillis: Long
    ) {
        val settings = deviceSettingsDao.get() ?: return
        val cloudId = settings.deviceCloudId ?: return
        val deviceName = settings.deviceName ?: return
        val nowIso = IsoTime.fromEpochMillis(System.currentTimeMillis())
        val lastPushIso = IsoTime.fromEpochMillis(lastPushAtMillis)
        cloudClient.upsertDevice(
            DeviceDto(
                id = cloudId,
                ownerId = ownerId,
                deviceName = deviceName,
                deviceModel = android.os.Build.MODEL,
                firstSeenAt = nowIso,
                lastSeenAt = nowIso,
                appVersion = null,
                lastSyncError = lastError?.take(DEVICE_ERROR_MESSAGE_MAX_CHARS),
                pendingCount = pendingCount,
                lastPushAt = lastPushIso,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        )
    }

    private suspend fun pushSession(sess: ScanSession, ownerId: String): String {
        val cloudId = sess.cloudId ?: throw IllegalStateException(
            "session ${sess.id} reached push without cloudId — finalize path didn't stamp it"
        )
        // Sessions already have cloudId at finalize time; the call is a
        // no-op here. Lots + rd_numbers below stamp before upsert per R2.
        sessionDao.markSyncing(sess.id)
        // Re-audit gate B finding: SessionMapper.toDto hardcodes
        // defaultCount = 0 with a comment claiming the SyncRepository
        // would compute it, but no caller did. Result: cloud's
        // denormalized scan_sessions.default_count stayed at 0 for every
        // phone-pushed session, breaking the portal session list's
        // 'N defaulters' badge. Computed here at push time from the
        // already-persisted rd_numbers rows — no Room migration needed
        // because the count is derivable on demand. Tombstones excluded
        // so a soft-deleted defaulter doesn't inflate the count.
        val defaultCount = rdNumberDao.getAllRowsInSession(sess.id)
            .count { it.monthsPaid > 1 && it.deletedAt == null }
        val dto = SessionMapper.toDto(sess).copy(
            ownerId = ownerId,
            defaultCount = defaultCount
        )
        val result = cloudClient.upsertSession(dto)
        val displayNumber = result.displayNumber
        if (displayNumber != sess.displayNumber) {
            sessionDao.update(sess.copy(displayNumber = displayNumber, cloudId = cloudId))
        }
        // Stamp local updatedAt to the server-trigger-bumped value so the
        // next realtime echo / pull does NOT re-merge this row (re-audit
        // gate B fix #6). Without this, every push caused one wasted
        // mergeFromCloud write per pull cycle because cloud.updatedAt
        // was always > local.updatedAt by however long the upsert
        // round-trip took.
        sessionDao.markSynced(
            sess.id,
            System.currentTimeMillis(),
            IsoTime.toEpochMillis(result.updatedAt),
            cloudId
        )
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
                // R3 circuit breaker — see pushRdNumber catch.
                val current = lotDao.findById(lot.id)
                if (current != null && current.retryCount >= PUSH_ABANDON_THRESHOLD) {
                    lotDao.markSyncAbandoned(lot.id)
                    android.util.Log.w(
                        "SyncRepository",
                        "scan_lot ${lot.id} abandoned after ${current.retryCount} push failures"
                    )
                    runCatching { notifier.notifySyncAbandoned("LOT", "LOT ${lot.id} in session") }
                }
                throw e
            }
        }
        return resolved
    }

    private suspend fun pushLot(lot: ScanLot, sessionCloudId: String, ownerId: String): String {
        val cloudId = lot.cloudId ?: UUID.randomUUID().toString()
        // R2: persist cloudId BEFORE the cloud call. If we crash or lose
        // the network between upsert and markSynced, the next push reuses
        // this same cloudId — preventing a duplicate cloud row.
        if (lot.cloudId == null) lotDao.stampCloudId(lot.id, cloudId)
        lotDao.markSyncing(lot.id)
        val dto = LotMapper.toDto(lot.copy(cloudId = cloudId), sessionCloudId).copy(ownerId = ownerId)
        val result = cloudClient.upsertLot(dto)
        lotDao.markSynced(
            lot.id,
            System.currentTimeMillis(),
            IsoTime.toEpochMillis(result.updatedAt),
            cloudId
        )
        return cloudId
    }

    /** Returns true iff every rd_number across every lot pushed successfully. */
    private suspend fun pushRdNumbersForLots(
        lotIdMap: Map<Long, String>,
        ownerId: String,
        editorDeviceCloudId: String?
    ): Boolean {
        var allOk = true
        for ((lotLocalId, lotCloudId) in lotIdMap) {
            val rdNumbers = rdNumberDao.getDirtyForLot(lotLocalId)
            for (rd in rdNumbers) {
                if (!pushRdNumber(rd, lotCloudId, ownerId, editorDeviceCloudId)) {
                    allOk = false
                }
            }
        }
        return allOk
    }

    /**
     * Pushes a single rd_account row. Stamps cloudId BEFORE the cloud
     * call so a mid-upsert network failure doesn't lose the id and
     * cause a duplicate row on the next push (Phase 5 R2 invariant).
     * Caller wraps in try/catch and handles markSyncError +
     * markSyncAbandoned around it (matches the dirtyAccounts loop in
     * runPushLocked).
     */
    private suspend fun pushRdAccount(
        account: com.qrscanner.app.data.RdAccount,
        ownerId: String,
        editorDeviceCloudId: String?
    ) {
        // rd_accounts has no synthetic uuid PK — the natural identity
        // IS the rdNumber (composite cloud PK (owner_id, rd_number)).
        // Use rdNumber as the local cloudId marker so findByCloudId
        // lookups + the wire DTO stay aligned without inventing a
        // separate UUID that the cloud table has no column for.
        val cloudId = account.rdNumber
        if (account.cloudId != cloudId) rdAccountDao.stampCloudId(account.rdNumber, cloudId)
        rdAccountDao.markSyncing(account.rdNumber)
        val dto = com.qrscanner.app.cloud.mappers.RdAccountMapper
            .toDto(account, editorDeviceCloudId)
            .copy(ownerId = ownerId)
        val result = cloudClient.upsertRdAccount(dto)
        rdAccountDao.markSynced(
            account.rdNumber,
            System.currentTimeMillis(),
            IsoTime.toEpochMillis(result.updatedAt),
            cloudId
        )
    }

    /** Returns true on success, false on non-auth failure (parent re-marks SYNC_ERROR). AuthExpired re-throws. */
    private suspend fun pushRdNumber(
        rd: RdNumber,
        lotCloudId: String,
        ownerId: String,
        editorDeviceCloudId: String?
    ): Boolean {
        val cloudId = rd.cloudId ?: UUID.randomUUID().toString()
        // R2: persist cloudId BEFORE the cloud call (see pushLot).
        if (rd.cloudId == null) rdNumberDao.stampCloudId(rd.id, cloudId)
        rdNumberDao.markSyncing(rd.id)
        return try {
            val dto = RdNumberMapper
                .toDto(rd.copy(cloudId = cloudId), lotCloudId, editorDeviceCloudId)
                .copy(ownerId = ownerId)
            val result = cloudClient.upsertRdNumber(dto)
            rdNumberDao.markSynced(
                rd.id,
                System.currentTimeMillis(),
                IsoTime.toEpochMillis(result.updatedAt),
                cloudId
            )
            true
        } catch (e: CloudException.AuthExpired) {
            rdNumberDao.markSyncError(rd.id, "auth expired")
            throw e
        } catch (e: Throwable) {
            rdNumberDao.markSyncError(rd.id, e.message ?: e.toString())
            // R3 circuit breaker — read fresh retryCount after the
            // increment in markSyncError. If we've hit the cap, flip to
            // SYNC_ABANDONED so promoteSessionsWithDirtyChildren stops
            // re-promoting the parent forever.
            val current = rdNumberDao.findByCloudId(cloudId) ?: rdNumberDao.findById(rd.id)
            if (current != null && current.retryCount >= PUSH_ABANDON_THRESHOLD) {
                rdNumberDao.markSyncAbandoned(rd.id)
                android.util.Log.w(
                    "SyncRepository",
                    "rd_number ${rd.id} abandoned after ${current.retryCount} push failures"
                )
                runCatching { notifier.notifySyncAbandoned("scan", "RD ${rd.number}") }
            }
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
     *    by `WHERE id = :id AND updatedAt < :updatedAt` (strict less-
     *    than per spec §11 / T5.7 tie-breaker — local wins on equal
     *    timestamps so a DIRTY edit racing its own cloud echo survives).
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
    suspend fun runPull(): Result<Unit> = syncMutex.withLock { runPullLocked() }

    private suspend fun runPullLocked(): Result<Unit> {
        val cloudSession = cloudClient.currentSession()
            ?: return Result.failure(CloudException.AuthExpired())
        val ownerId = cloudSession.ownerId
        val settings = deviceSettingsDao.get()
            ?: return Result.failure(IllegalStateException("device_settings missing; first-run setup incomplete"))

        // Own-device cloudId — banner events originating from this phone are
        // suppressed so the user doesn't see "Counter Phone synced Session #47"
        // about themselves (spec §15.5.3).
        val ownDeviceCloudId = settings.deviceCloudId

        // Phase 5 T5.4 (F6 finding): drain until a partial page comes back.
        // First-run sign-in with thousands of cloud rows previously needed
        // 10+ poll ticks to fully sync; the drain loop wraps that into a
        // single runPull cycle. Bounded by MAX_DRAIN_PAGES for safety so
        // a runaway cursor (clock skew, repeated identical updated_at) can
        // never spin forever. The cursor advances inside each iteration
        // via deviceSettingsDao.updateLastPulledAt, so a worker kill
        // mid-drain just resumes from the last persisted high-water mark.
        val allNotices = mutableListOf<RemoteEditNotice>()
        var sinceCursor = settings.lastPulledAt
        var pages = 0
        // Release-build breadcrumb for "phone is stuck on stale data"
        // support cases: log the cursor the pull is starting from.
        // Single line per runPull invocation; bounded so it stays
        // grep-able in adb logcat without spamming.
        android.util.Log.i(
            "SyncRepository",
            "pull start: sinceCursor=$sinceCursor ownerId=$ownerId"
        )
        while (true) {
            pages++
            // Surface progress only after the first page completes —
            // single-page pulls (the steady-state case) never show the
            // counter. The counter renders only when a drain is
            // actually in progress (pages > 1 or current page is
            // already known to be full).
            if (pages > 1) {
                updateSummary {
                    it.copy(
                        state = SyncPillState.SYNCING,
                        pullProgress = PullProgress(
                            pagesProcessed = pages,
                            pagesUpperBound = MAX_DRAIN_PAGES
                        )
                    )
                }
            }
            val delta = try {
                cloudClient.pullChangesSince(ownerId, sinceCursor)
            } catch (e: CloudException.AuthExpired) {
                updateSummary { it.copy(state = SyncPillState.ERROR, lastErrorMessage = "auth expired") }
                return Result.failure(e)
            } catch (e: CloudException.SchemaMissing) {
                updateSummary {
                    it.copy(state = SyncPillState.SCHEMA_MISSING, lastErrorMessage = e.message)
                }
                return Result.failure(e)
            } catch (e: Throwable) {
                deviceSettingsDao.recordPullError(
                    timestamp = System.currentTimeMillis(),
                    error = e.message ?: e.toString()
                )
                // W2 (oracle bg_1eadd75b): on pull failure, surface the
                // problem via the pill instead of silently leaving the
                // user on "All synced" while the pull is actually
                // broken. Push state takes precedence (ERROR/PENDING
                // already mean attention-needed); only escalate when
                // push state was SYNCED/INITIALIZING — the pre-W2 path
                // hid pull errors when there were no pending pushes.
                updateSummary {
                    val nextState = when (it.state) {
                        SyncPillState.SYNCED,
                        SyncPillState.INITIALIZING -> SyncPillState.ERROR
                        else -> it.state
                    }
                    it.copy(state = nextState, lastErrorMessage = e.message ?: e.toString())
                }
                return Result.failure(e)
            }

            val priorCursor = sinceCursor
            val notices = database.withTransaction {
                val emitted = mutableListOf<RemoteEditNotice>()
                emitted += mergeSessions(delta.sessions, ownDeviceCloudId)
                mergeLots(delta.lots)
                emitted += mergeRdNumbers(delta.rdNumbers, delta.sessions, ownDeviceCloudId)
                mergeRdAccounts(delta.rdAccounts)
                if (delta.highWaterMark > priorCursor) {
                    deviceSettingsDao.updateLastPulledAt(delta.highWaterMark)
                }
                emitted.toList()
            }
            // SyncEvent inserts are intentionally OUTSIDE the merge
            // transaction. A malformed SyncEvent (e.g. a future enum
            // value that fails type conversion) would otherwise roll
            // back the entire merge, losing every row that was just
            // successfully written, and the next pull would re-process
            // identical data and hit the same failure. The bell
            // history is observability, not data — best-effort is
            // the right durability tier. Each insert is independent
            // so a single bad notice doesn't block the others.
            for (notice in notices) {
                runCatching { syncEventDao.insert(notice.toEvent()) }
                    .onFailure {
                        android.util.Log.w(
                            "SyncRepository",
                            "sync_event insert failed (notice=${notice::class.simpleName}); merge data was saved",
                            it
                        )
                    }
            }
            allNotices += notices
            sinceCursor = delta.highWaterMark

            // Phase 5 T5.12: a full page that didn't advance the high-water
            // mark means we're inside a same-millisecond tail (rare but real
            // under bulk writes). Continue draining; the SupabaseCloudClient's
            // (updated_at, id) secondary order keeps the page boundary stable
            // and merge's findByCloudId dedupes any overlap. The mergeFromCloud
            // LWW gate (updatedAt <) makes re-emitting already-applied rows a
            // no-op. MAX_DRAIN_PAGES still bounds total work.
            val shouldContinue = delta.pageWasFull && pages < MAX_DRAIN_PAGES
            if (!shouldContinue) break
        }

        val now = System.currentTimeMillis()
        updateSummary {
            // R4 (oracle bg_0ea195ce): set state to SYNCED so the
            // downstream summaryFlow.combine derives the final pill
            // value (SYNCED / PENDING) from the LIVE observePendingCount
            // Flow rather than this snapshot's stale it.pendingCount.
            // Pull success means cloud is reachable + auth works, so
            // also clear any stale ERROR/SCHEMA_MISSING/lastErrorMessage
            // from a prior push failure.
            it.copy(
                state = SyncPillState.SYNCED,
                lastSuccessfulPullAt = now,
                lastErrorMessage = null,
                pullProgress = null
            )
        }
        notifyRemoteEdits(allNotices)
        return Result.success(Unit)
    }

    /**
     * Fires Channel C "owner edited / other phone synced" tray
     * notifications (spec §15.5.2). Per-event individually to keep
     * tap-routing simple; the in-app banner UI dedupes further.
     */
    private fun notifyRemoteEdits(notices: List<RemoteEditNotice>) {
        if (notices.isEmpty()) return
        for (notice in notices) {
            runCatching {
                notifier.notifyRemoteEdit(
                    type = notice.type,
                    displayNumber = notice.displayNumber,
                    originLabel = notice.originLabel
                )
            }
        }
    }

    /**
     * Single carrier for "remote change worth telling the user about"
     * produced by the merge functions and consumed by both the
     * SyncEvent log writer (in-app banner) and the SyncNotifier (tray).
     * Keeping the two consumers off the same DTO avoids them drifting.
     */
    private data class RemoteEditNotice(
        val type: SyncEventType,
        val displayNumber: Int,
        val sessionCloudId: String,
        val rdNumberCloudId: String? = null,
        val originDeviceCloudId: String?,
        val originDeviceName: String?,
        val originOperatorName: String?,
        val occurredAt: Long,
        val summary: String
    ) {
        val originLabel: String
            get() = when {
                originDeviceCloudId == null -> "Portal"
                !originOperatorName.isNullOrBlank() -> originOperatorName
                !originDeviceName.isNullOrBlank() -> originDeviceName
                else -> "another phone"
            }

        fun toEvent(): SyncEvent = SyncEvent(
            occurredAt = occurredAt,
            type = type,
            sessionCloudId = sessionCloudId,
            rdNumberCloudId = rdNumberCloudId,
            originDeviceCloudId = originDeviceCloudId,
            originDeviceName = originDeviceName,
            originOperatorName = originOperatorName,
            payloadSummary = summary
        )
    }

    private suspend fun mergeSessions(
        dtos: List<com.qrscanner.app.cloud.dto.ScanSessionDto>,
        ownDeviceCloudId: String?
    ): List<RemoteEditNotice> {
        val notices = mutableListOf<RemoteEditNotice>()
        for (dto in dtos) {
            val existing = sessionDao.findByCloudId(dto.id)
            val updatedAt = IsoTime.toEpochMillis(dto.updatedAt)
            val deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
            val isOwn = dto.deviceId == ownDeviceCloudId
            if (existing == null) {
                sessionDao.insert(SessionMapper.toEntity(dto))
                if (!isOwn && deletedAt == null) {
                    notices += RemoteEditNotice(
                        type = SyncEventType.REMOTE_SESSION_FINALIZED,
                        displayNumber = dto.displayNumber,
                        sessionCloudId = dto.id,
                        originDeviceCloudId = dto.deviceId,
                        originDeviceName = null,
                        originOperatorName = dto.operatorName,
                        occurredAt = updatedAt,
                        summary = "finalized Session #${dto.displayNumber} (${dto.totalLots} LOTs)"
                    )
                }
            } else {
                val wasAlive = existing.deletedAt == null
                val priorStatus = existing.syncStatus
                val priorUpdatedAt = existing.updatedAt
                val rowsAffected = sessionDao.mergeFromCloud(
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
                // Spec §11 line 626: WARN on every silent overwrite of
                // a non-SYNCED row so post-hoc "my edit vanished"
                // debugging is possible. Without this log, a phone-side
                // session-level edit being clobbered by a portal echo
                // (rare but possible during operator-switch races)
                // leaves zero diagnostic trail.
                if (rowsAffected == 1 && priorStatus != SyncStatus.SYNCED) {
                    android.util.Log.w(
                        "SyncRepository",
                        "Silent overwrite: scan_sessions.cloudId=${dto.id} " +
                            "local.updatedAt=$priorUpdatedAt (status=$priorStatus) " +
                            "remote.updatedAt=$updatedAt. Local change discarded."
                    )
                }
                // Notice emission gated on rowsAffected so a no-op merge
                // (local was newer) doesn't fire a "deleted" banner for
                // a change that the LWW filter rejected. Without this
                // gate, the bell shows phantom deletions whenever the
                // operator's own delete races a stale remote echo.
                if (rowsAffected == 1 && !isOwn && wasAlive && deletedAt != null) {
                    notices += RemoteEditNotice(
                        type = SyncEventType.REMOTE_SESSION_DELETED,
                        displayNumber = dto.displayNumber,
                        sessionCloudId = dto.id,
                        originDeviceCloudId = dto.deviceId,
                        originDeviceName = null,
                        originOperatorName = dto.operatorName,
                        occurredAt = updatedAt,
                        summary = "deleted Session #${dto.displayNumber}"
                    )
                }
            }
        }
        return notices
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
                val priorStatus = existing.syncStatus
                val priorUpdatedAt = existing.updatedAt
                val rowsAffected = lotDao.mergeFromCloud(
                    id = existing.id,
                    cloudId = dto.id,
                    sessionId = parent.id,
                    lotNumber = dto.lotNumber,
                    timestamp = IsoTime.toEpochMillis(dto.timestamp),
                    updatedAt = updatedAt,
                    deletedAt = deletedAt
                )
                if (rowsAffected == 1 && priorStatus != SyncStatus.SYNCED) {
                    android.util.Log.w(
                        "SyncRepository",
                        "Silent overwrite: scan_lots.cloudId=${dto.id} " +
                            "local.updatedAt=$priorUpdatedAt (status=$priorStatus) " +
                            "remote.updatedAt=$updatedAt. Local change discarded."
                    )
                }
            }
        }
    }

    /**
     * Merge inbound rd_accounts into local Room. LWW by `rdNumber` PK
     * (strict-< Phase 5 T5.7 invariant). Insert-or-merge — no parent
     * dependency (rd_accounts are master data, not session-scoped).
     *
     * No RemoteEditNotice emission for now — the user's spec keeps
     * account profile edits as "silent background sync, see Accounts
     * screen for current state" rather than firing a Channel C
     * notification per portal-CSV-bulk-import or per-row edit. Can
     * revisit if user requests notifications for account changes.
     */
    private suspend fun mergeRdAccounts(dtos: List<com.qrscanner.app.cloud.dto.RdAccountDto>) {
        for (dto in dtos) {
            val existing = rdAccountDao.findByRdNumberIncludingDeleted(dto.rdNumber)
            val updatedAt = IsoTime.toEpochMillis(dto.updatedAt)
            val deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
            if (existing == null) {
                rdAccountDao.insert(
                    com.qrscanner.app.cloud.mappers.RdAccountMapper.toEntity(dto)
                )
            } else {
                rdAccountDao.mergeFromCloud(
                    rdNumber = dto.rdNumber,
                    cloudId = dto.rdNumber,
                    name = dto.name,
                    monthlyAmount = dto.monthlyAmount,
                    lastPaidThrough = dto.lastPaidThrough,
                    source = dto.source,
                    isActive = dto.isActive,
                    accountOpenedDate = dto.accountOpenedDate,
                    accountClosingDate = dto.accountClosingDate,
                    ownerId = dto.ownerId,
                    lastEditorDeviceId = dto.lastEditorDeviceId,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt
                )
            }
        }
    }

    private suspend fun mergeRdNumbers(
        dtos: List<com.qrscanner.app.cloud.dto.RdNumberDto>,
        sessionDtos: List<com.qrscanner.app.cloud.dto.ScanSessionDto>,
        ownDeviceCloudId: String?
    ): List<RemoteEditNotice> {
        val notices = mutableListOf<RemoteEditNotice>()
        // Build lot.cloudId -> (session.cloudId, displayNumber, deviceId, operator)
        // index so per-rd_number defaulter edits can attribute the change
        // to the originating session without an extra DAO query inside the
        // hot pull loop. We derive the lot -> session linkage from the DTOs
        // currently being merged when possible; otherwise fall back to Room.
        val lotToSession = mutableMapOf<String, com.qrscanner.app.cloud.dto.ScanSessionDto>()
        if (dtos.isNotEmpty() && sessionDtos.isNotEmpty()) {
            val sessionByCloudId = sessionDtos.associateBy { it.id }
            for (dto in dtos) {
                val lot = lotDao.findByCloudId(dto.lotId) ?: continue
                val sessionCloudId = sessionDao.findCloudIdByLocalId(lot.sessionId) ?: continue
                sessionByCloudId[sessionCloudId]?.let { lotToSession[dto.lotId] = it }
            }
        }
        for (dto in dtos) {
            val parent = lotDao.findByCloudId(dto.lotId)
            if (parent == null) {
                android.util.Log.w("SyncRepository", "pull: skipping rd_number ${dto.id} — parent lot ${dto.lotId} not local")
                continue
            }
            val existing = rdNumberDao.findByCloudId(dto.id)
            val updatedAt = IsoTime.toEpochMillis(dto.updatedAt)
            val deletedAt = IsoTime.toEpochMillisOrNull(dto.deletedAt)
            val parentSessionDto = lotToSession[dto.lotId]
            // Phase 5 T5.6 (F9 fix): attribution now uses the rd_number's
            // own last_editor_device_id (cloud column), set by whoever
            // wrote the row. Phones stamp own deviceId on push; portal
            // writes leave it NULL. Falls back to the parent session's
            // deviceId only for legacy rows pushed before T5.6 where the
            // column is null AND parent session has a deviceId.
            val editorCloudId = dto.lastEditorDeviceId
                ?: parentSessionDto?.deviceId?.ifBlank { null }
            val isOwn = editorCloudId != null && editorCloudId == ownDeviceCloudId
            val isPortal = dto.lastEditorDeviceId == null &&
                (parentSessionDto?.deviceId?.isBlank() ?: false)
            if (existing == null) {
                rdNumberDao.insert(RdNumberMapper.toEntity(dto).copy(lotId = parent.id))
            } else {
                val priorMonthsPaid = existing.monthsPaid
                val priorMonthsList = existing.monthsList
                val priorStatus = existing.syncStatus
                val priorUpdatedAt = existing.updatedAt
                val rowsAffected = rdNumberDao.mergeFromCloud(
                    id = existing.id,
                    cloudId = dto.id,
                    lotId = parent.id,
                    number = dto.number,
                    position = dto.position,
                    scannedAt = IsoTime.toEpochMillis(dto.scannedAt),
                    monthsPaid = dto.monthsPaid,
                    monthsList = dto.monthsList,
                    lastEditorDeviceId = dto.lastEditorDeviceId,
                    updatedAt = updatedAt,
                    deletedAt = deletedAt
                )
                // Phase 5 T5.8 (F4): spec §11 line 626 explicitly requires a
                // WARN log on every silent overwrite of a DIRTY/SYNCING/
                // SYNC_ERROR row so post-hoc 'my edit vanished' debugging is
                // possible. rowsAffected == 1 AND priorStatus was unpushed
                // means the LWW gate just discarded a pending local edit.
                if (rowsAffected == 1 && priorStatus != SyncStatus.SYNCED) {
                    val priorMonthsListSummary = priorMonthsList ?: "<auto>"
                    val newMonthsListSummary = dto.monthsList ?: "<auto>"
                    android.util.Log.w(
                        "SyncRepository",
                        "Silent overwrite: rd_numbers.cloudId=${dto.id} " +
                            "local.updatedAt=$priorUpdatedAt (status=$priorStatus) " +
                            "remote.updatedAt=$updatedAt. " +
                            "Discarded local change: monthsPaid $priorMonthsPaid->${dto.monthsPaid}, " +
                            "monthsList [$priorMonthsListSummary]->[$newMonthsListSummary]"
                    )
                }
                // Banner-worthy only when defaulter data actually changed
                // AND the merge actually applied (LWW gate at mergeFromCloud
                // ensures we only emit when remote.updatedAt > local). Mid-
                // edit pull races where local already had the values are
                // silently no-op.
                val changed = priorMonthsPaid != dto.monthsPaid ||
                    priorMonthsList != dto.monthsList
                if (!isOwn && changed && deletedAt == null && parentSessionDto != null) {
                    val isPortalEdit = dto.lastEditorDeviceId == null || isPortal
                    notices += RemoteEditNotice(
                        type = if (isPortalEdit)
                            SyncEventType.PORTAL_DEFAULTER_EDIT
                        else
                            SyncEventType.REMOTE_DEFAULTER_EDIT,
                        displayNumber = parentSessionDto.displayNumber,
                        sessionCloudId = parentSessionDto.id,
                        rdNumberCloudId = dto.id,
                        originDeviceCloudId = if (isPortalEdit) null else editorCloudId,
                        originDeviceName = null,
                        originOperatorName = if (isPortalEdit) null else parentSessionDto.operatorName,
                        occurredAt = updatedAt,
                        summary = "edited defaulter on RD #${dto.number}"
                    )
                }
            }
        }
        return notices
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
        // Rate-limited INFO log: emit at most one realtime breadcrumb
        // per 5s window per table so release builds carry a debug trail
        // for "portal edit didn't reach phone" support cases WITHOUT
        // spamming logcat during bulk pushes (CSV import can fire 100+
        // events in seconds). DEBUG builds still log every event via the
        // adjacent Log.d so dev-time tracing stays unchanged.
        val now = System.currentTimeMillis()
        val tableKey: String = payload.table.name
        val shouldLogInfo = synchronized(realtimeLogLastEmittedAt) {
            val last = realtimeLogLastEmittedAt[tableKey] ?: 0L
            if (now - last >= REALTIME_LOG_INTERVAL_MS) {
                realtimeLogLastEmittedAt[tableKey] = now
                true
            } else {
                false
            }
        }
        if (shouldLogInfo) {
            android.util.Log.i(
                "SyncRepository",
                "realtime ${payload.event} on ${payload.table} cloudId=${payload.cloudId}"
            )
        }
        if (com.qrscanner.app.BuildConfig.DEBUG) {
            android.util.Log.d(
                "SyncRepository",
                "realtime ${payload.event} on ${payload.table} cloudId=${payload.cloudId}"
            )
        }
        runPull()
    }

    private val realtimeLogLastEmittedAt: MutableMap<String, Long> = mutableMapOf()

    private fun updateSummary(transform: (SyncSummary) -> SyncSummary) {
        mutableSummary.update(transform)
    }

    companion object {
        /**
         * Pure state-machine derivation function — extracted from the
         * summaryFlow combine block so it can be invariant-tested without
         * spinning up Room/coroutines (see SyncStateMachineTest).
         *
         * Priority order (must match summaryFlow KDoc):
         *  1. NOT_SIGNED_IN  — auth overlay
         *  2. INITIALIZING   — auth bootstrap
         *  3. SCHEMA_MISSING — blocking setup; beats live count
         *  4. SYNCING        — transient mid-cycle
         *  5. ERROR          — full-fail (partial uses PENDING per d1d13fc)
         *  6. liveCount > 0  -> PENDING
         *  7. else           -> SYNCED
         *
         * pendingCount is overridden from the live DB count so the pill
         * always shows the truth, never the snapshot (oracle R4).
         */
        @androidx.annotation.VisibleForTesting
        fun derivePillSummary(summary: SyncSummary, liveCount: Int): SyncSummary {
            val derived = when (summary.state) {
                SyncPillState.NOT_SIGNED_IN,
                SyncPillState.INITIALIZING,
                SyncPillState.SCHEMA_MISSING,
                SyncPillState.SYNCING,
                SyncPillState.ERROR -> summary.state
                SyncPillState.PENDING,
                SyncPillState.SYNCED -> if (liveCount > 0) SyncPillState.PENDING else SyncPillState.SYNCED
            }
            return summary.copy(state = derived, pendingCount = liveCount)
        }

        /**
         * Fire the "sync paused" notification on the Nth consecutive
         * push/pull failure. 3 = roughly 90s of compounded backoff
         * (WorkManager 30s × 3 attempts) before bothering the operator.
         * Lower than the per-row PUSH_ABANDON_THRESHOLD (8) so the
         * banner lights up well before any row gets circuit-broken.
         */
        private const val ERROR_NOTIFY_THRESHOLD = 3

        /** SharedPreferences key for the persisted consecutive-failures counter. */
        const val KEY_CONSECUTIVE_FAILURES = "consecutivePushFailures"

        /** SharedPreferences file name. */
        const val PREFS_FILE = "sync_repository_state"

        /**
         * After the threshold fires, re-fire the notification every
         * Nth subsequent failure to keep the badge sticky without
         * notification-fatiguing the operator. 6 ≈ 30 min between
         * re-fires at WorkManager's 5h backoff plateau.
         */
        private const val ERROR_NOTIFY_REFIRE_EVERY = 6
        // Phase 5 T5.4: hard upper bound on drain-loop iterations. With
        // PULL_PAGE_SIZE = 500 this covers up to 10k rows per cycle which
        // exceeds any realistic shop's data set. If a runaway cursor ever
        // hit this we'd want to log + bail out rather than spin.
        private const val MAX_DRAIN_PAGES = 20

        /**
         * Realtime payload INFO-log rate-limit window per table. Bulk
         * pushes (CSV import) can fire dozens of events in a second;
         * one INFO line per 5s window keeps the release-build log
         * trail useful without spamming logcat or filling crash-
         * reporter breadcrumbs.
         */
        private const val REALTIME_LOG_INTERVAL_MS = 5_000L

        /**
         * Oracle bg_0ea195ce R3 / I6 — after this many consecutive push
         * failures on the same row, flip to [SyncStatus.SYNC_ABANDONED]
         * to break the infinite promote → fail → re-promote loop.
         *
         * 8 attempts × WorkManager's 30s initial + EXPONENTIAL backoff
         * lands at roughly:
         *   1: 30s    2: 60s    3: 2m     4: 4m     5: 8m
         *   6: 16m    7: 32m    8: 64m  (≈ 1h elapsed real-time)
         * A row that's failed 8 times across an hour of retries is
         * structurally broken (schema drift, FK we can't satisfy,
         * CHECK violation), not just flaky network — abandoning it
         * stops the loop without losing the row (state preserved for
         * manual diagnostics).
         */
        const val PUSH_ABANDON_THRESHOLD = 8

        /**
         * Upper bound on the [DeviceDto.lastSyncError] payload size.
         * Postgres `text` has no hard limit but a stack trace pasted
         * verbatim into the column would blow up the realtime payload
         * and waste the portal Devices card layout. 240 chars fits
         * roughly the first three lines of a Throwable.toString() —
         * enough to recognise the error class + immediate cause.
         */
        private const val DEVICE_ERROR_MESSAGE_MAX_CHARS = 240
    }
}
