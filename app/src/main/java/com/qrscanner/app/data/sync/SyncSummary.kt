package com.qrscanner.app.data.sync

/**
 * Aggregated sync state surfaced to the UI status pill on Home and
 * the diagnostics screen in Settings.
 *
 * Computed once by [SyncRepository] from the underlying flows
 * (DeviceSettings, dirty-row count, current auth state) so the UI
 * never has to compose its own derived state.
 *
 * Spec reference: §15.5 (status pill), §18.5 (diagnostics).
 */
data class SyncSummary(
    val state: SyncPillState,
    val pendingCount: Int,
    val lastSuccessfulPushAt: Long?,
    val lastSuccessfulPullAt: Long?,
    val lastErrorMessage: String?
)

/**
 * Drives the visual treatment of the status pill on Home.
 *
 * NOT_SIGNED_IN takes precedence over everything else — the pill
 * routes the user to SignInScreen on tap. ERROR + PENDING are distinct
 * because pending rows are a normal, in-flight state (amber dot, no
 * action required) whereas error means manual intervention may help
 * (red dot, tap opens diagnostics).
 */
enum class SyncPillState {
    NOT_SIGNED_IN,
    INITIALIZING,
    SYNCED,
    PENDING,
    SYNCING,
    ERROR,
    /**
     * Cloud database schema has not been applied yet — phone signed in
     * successfully but PostgREST returns table-not-found / RPC-not-found.
     * Distinct from ERROR so the pill copy + dot color can be calmer
     * (PrimaryOrange, not ErrorRed) and the diagnostics screen can show
     * a one-shot "paste cloud/schema.sql into Supabase Studio" hint
     * instead of unactionable error spam. Phase 5 T5.1, spec §15.5.4.
     */
    SCHEMA_MISSING
}
