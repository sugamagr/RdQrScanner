package com.qrscanner.app.data

/**
 * Per-row sync lifecycle state for [ScanSession], [ScanLot], and [RdNumber].
 *
 * The values are stored in Room as TEXT (Room's enum support) and read by
 * [com.qrscanner.app.data.sync.SyncRepository] to decide what to push to the
 * cloud on each worker tick. Transitions:
 *
 * ```
 *      new (active session)          new (finalized / defaulter edit)
 *               │                               │
 *               ▼                               ▼
 *        ┌─────────────┐                 ┌─────────────┐
 *        │ LOCAL_ONLY  │ ─ finalize ──► │   DIRTY     │
 *        └─────────────┘                 └─────────────┘
 *                                               │
 *                                       worker picks up
 *                                               ▼
 *                                        ┌─────────────┐
 *                                        │   SYNCING   │
 *                                        └─────────────┘
 *                                            ↙     ↘
 *                                       OK         fail
 *                                       │           │
 *                                       ▼           ▼
 *                                  ┌─────────┐ ┌──────────────┐
 *                                  │ SYNCED  │ │ SYNC_ERROR   │
 *                                  └─────────┘ └──────────────┘
 *                                       │           │
 *                                       └───────────┘
 *                                            │
 *                                       local edit
 *                                            ▼
 *                                        (DIRTY)
 * ```
 *
 * Spec reference: §6, §8.
 */
enum class SyncStatus {
    /**
     * Row exists locally but is not eligible to sync yet. Only used for
     * children of an active (in-progress) session — the entire active
     * session is device-private until the user taps "End Session", at
     * which point every row flips [LOCAL_ONLY] → [DIRTY] in one atomic
     * pass and the push worker is enqueued.
     */
    LOCAL_ONLY,

    /**
     * Row has local changes the push worker must send to the cloud.
     * Covers three creation moments: a newly-finalized session and its
     * children, a defaulter edit on an already-synced row, and a
     * soft-deleted row (deletedAt non-null) whose tombstone must be
     * propagated.
     */
    DIRTY,

    /**
     * The push worker has the row in flight. Held briefly — the worker
     * sets this immediately before its REST call and flips it to
     * [SYNCED] / [SYNC_ERROR] within milliseconds of the response. If
     * the process is killed mid-flight, the next worker run treats
     * [SYNCING] rows identically to [DIRTY] (re-push is idempotent
     * because the cloudId is client-generated).
     */
    SYNCING,

    /**
     * Row is in sync with the cloud as of the last successful push or
     * pull. Stays here until a local edit flips it back to [DIRTY] or a
     * pull merge overwrites the local copy with a newer remote.
     */
    SYNCED,

    /**
     * Last push failed. The error is recorded in the row's `lastSyncError`
     * column. The worker retries with exponential backoff (WorkManager
     * BackoffPolicy.EXPONENTIAL, 30s initial, capped at WorkRequest's
     * MAX_BACKOFF_MILLIS of 5h) until the push succeeds or the user
     * signs out.
     */
    SYNC_ERROR,

    /**
     * Permanently failed after exceeding the retry cap (currently 8
     * consecutive push failures, see [SyncRepository.PUSH_ABANDON_THRESHOLD]).
     *
     * Oracle bg_0ea195ce R3 / I6 — without this, a row whose cloud-side
     * write is structurally impossible (cloud schema drift, FK constraint
     * the local row will never satisfy, etc.) creates an infinite DIRTY
     * promotion loop:
     *
     *   1. `promoteSessionsWithDirtyChildren` re-promotes the parent
     *      session to DIRTY every push cycle (the child stays SYNC_ERROR).
     *   2. Parent push succeeds (idempotent upsert).
     *   3. Child push fails again, parent re-marked SYNC_ERROR.
     *   4. Pill stuck PENDING/ERROR forever.
     *
     * Rows in this state are EXCLUDED from `getDirtyForPush`,
     * `observePendingCount`, and `promoteSessionsWithDirtyChildren`.
     * They surface only in the diagnostics screen for manual intervention
     * (clear-and-rescan, or contact support).
     */
    SYNC_ABANDONED
}
