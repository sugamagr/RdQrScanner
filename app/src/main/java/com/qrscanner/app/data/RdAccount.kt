package com.qrscanner.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Customer-facing RD account profile. One row per (owner, rd_number).
 *
 * Distinct from [RdNumber] which is a per-scan occurrence inside a
 * [ScanLot]. This table holds the *who* and *how much* metadata that
 * survives across sessions — customer name, monthly denomination, the
 * latest month they've paid through, and lifecycle markers.
 *
 * Identity model: `rdNumber` is the natural primary key. Globally
 * unique per signed-in owner. Two phones syncing the same owner share
 * the same set of rows.
 *
 * Sync columns mirror [RdNumber] exactly so the existing push / pull /
 * LWW / retry+abandon / attribution machinery in
 * [com.qrscanner.app.data.sync.SyncRepository] applies unchanged.
 *
 * Lifecycle:
 * - Created via AddAccountsScreen (source = MANUAL) or portal CSV
 *   upload (source = CSV). MANUAL is editable everywhere; CSV is
 *   editable on portal only — phone shows a lock + "contact Sugam"
 *   snackbar on edit attempt.
 * - Mark Inactive (isActive = false) hides the row from the default
 *   Accounts list. Scanning the rdNumber in any LOT silently reactivates.
 * - Delete (deletedAt non-null) hard-tombstones; a future
 *   account at the same rdNumber may then be created.
 */
@Entity(
    tableName = "rd_accounts",
    indices = [
        Index(value = ["name"]),
        Index(value = ["source"]),
        Index(value = ["isActive"])
    ]
)
data class RdAccount(
    /**
     * Natural primary key. Validated to match `^\d{9,15}$` at the
     * write boundary (AddAccountsScreen + CSV ingest both reject
     * malformed inputs); we don't re-check at the DAO layer.
     */
    @PrimaryKey
    val rdNumber: String,

    /** Customer name, trimmed, max 60 chars enforced at the UI boundary. */
    val name: String,

    /** Monthly RD installment in rupees (no decimals). Positive integer. */
    val monthlyAmount: Int,

    /**
     * Latest month this account has paid through, as a YYYY-MM token
     * (e.g. "2025-08"). Null = never paid. Updated monotonically by
     * [com.qrscanner.app.data.sync.SyncRepository.markSessionForSync]
     * after each finalized session: max(monthsList tail) across all
     * scans of this rd_number, strictly-greater write only. Powers the
     * defaulter dialog auto-suggest (block builds backward from
     * nextMonth(lastPaidThrough)) and the "Paid till — Aug 2025"
     * label on the Accounts screen.
     */
    val lastPaidThrough: String? = null,

    /** Where this row came from. Drives the editability gate on the phone. */
    val source: AccountSource,

    /**
     * False after Mark Inactive. Hidden from the default Accounts list;
     * a "Show inactive" toggle reveals them. Scanning the rdNumber in
     * any LOT auto-flips back to true (silent + DIRTY + toast).
     * Blocks creation of a new account at the same rdNumber — operator
     * must reactivate, or hard-delete first.
     */
    val isActive: Boolean = true,

    /**
     * ISO YYYY-MM-DD or null. Schema-only this round; not surfaced in
     * UI. RD accounts have a natural 5y default tenure extendable to
     * 10y. Future versions render this on the detail screen + use it
     * for maturity reminders.
     */
    val accountOpenedDate: String? = null,

    /** ISO YYYY-MM-DD or null. Same schema-only treatment as [accountOpenedDate]. */
    val accountClosingDate: String? = null,

    // ── Cloud sync metadata (mirrors RdNumber pattern v6+) ───────────

    val ownerId: String? = null,
    val cloudId: String? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val lastSyncError: String? = null,
    val deletedAt: Long? = null,
    /** See [ScanSession.retryCount]. */
    val retryCount: Int = 0,
    /** See [RdNumber] last_editor_device_id (Phase 5 T5.6 attribution). */
    val lastEditorDeviceId: String? = null
)
