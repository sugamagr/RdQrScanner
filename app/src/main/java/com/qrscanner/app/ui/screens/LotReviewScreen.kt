package com.qrscanner.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.room.withTransaction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qrscanner.app.R
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.ui.components.GradientTopBar
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.BackgroundWhite
import com.qrscanner.app.ui.theme.DisabledBackground
import com.qrscanner.app.ui.theme.DisabledContent
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.RowBackground
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.util.MonthYear

/**
 * Snapshot of one row in the LOT review screen.
 *
 * The contract: `selected` is the contiguous block of months the operator
 * has chosen for this row. `selected.first()` is the anchor (OLDEST month
 * — extend-forward direction per locked decision). `selected.size == count`.
 * The stored monthsList in [RdNumber.monthsList] is newest-first
 * for storage stability, so the persist boundary reverses this.
 */
data class LotReviewRow(
    val rdNumber: RdNumber,
    val accountName: String?,
    val accountLastPaidThrough: MonthYear?,
    val accountMonthlyAmount: Int?,
    val selected: List<MonthYear>,
    /**
     * Baseline that `selected` is compared against to detect actual
     * edits. Builder populates this equal to `selected` at hydration
     * (= DB state). UI-driven [copy(selected = ...)] mutations leave
     * `originalSelected` untouched, so [hasChanges] correctly reports
     * "did the operator actually change anything?". This is what
     * keeps [LotReviewOutcome.NoChanges] reachable — without it, every
     * Confirm tap would persist every row regardless of intent.
     */
    val originalSelected: List<MonthYear> = selected
) {
    val count: Int get() = selected.size

    /** True iff the operator's in-flight selection differs from the DB baseline. */
    val hasChanges: Boolean get() = selected != originalSelected

    /**
     * Per-row contribution to the LOT's 20,000-rupee total cap, or
     * null when no [accountMonthlyAmount] is known. Null rows are
     * skipped from the sum and surfaced as 'unverified' in the live
     * running total + confirm warning.
     */
    val rupeeContribution: Int?
        get() = accountMonthlyAmount?.takeIf { it > 0 }?.let { it * count }

    /**
     * True iff confirming this row would regress `lastPaidThrough`.
     * Triggers when the newest selected month is at or before the
     * account's stored paid-till anchor. Returns false for rows with
     * no account profile or no stored paid-till (no baseline).
     */
    val isRegression: Boolean
        get() {
            val anchor = accountLastPaidThrough ?: return false
            val newest = selected.maxOrNull() ?: return false
            return newest <= anchor
        }

    /** The month operator says payment is being credited for (newest). */
    val newestSelected: MonthYear? get() = selected.maxOrNull()

    /** Oldest selected month — the block's start (extend-forward direction). */
    val oldestSelected: MonthYear? get() = selected.minOrNull()

    /**
     * Months that would be skipped between `nextMonth(accountLastPaidThrough)`
     * and the operator's chosen block start. Empty when there's no paid-till
     * baseline, no selection, the block starts at or before the expected
     * next month (= no skip), or this is a regression (handled by the
     * separate regression dialog, not the skip-gap dialog). Used to surface
     * "You're skipping Sep 2025 — is that correct?" confirmation on Save.
     */
    fun skipsMonthsAfterLastPaid(): List<MonthYear> {
        val lastPaid = accountLastPaidThrough ?: return emptyList()
        val start = oldestSelected ?: return emptyList()
        val expectedStart = lastPaid.plusOneMonth()
        if (start <= expectedStart) return emptyList()
        val skipped = mutableListOf<MonthYear>()
        var cursor = expectedStart
        while (cursor < start) {
            skipped += cursor
            cursor = cursor.plusOneMonth()
        }
        return skipped
    }
}

/**
 * Discriminates the two paths that share the editor. Sealed so future
 * variants (e.g. a read-only inspector mode) extend without modifying
 * every call site — exhaustive `when` will surface every consumer.
 *
 * The mode controls a single behavioral fork: whether the OverLimit
 * dialog offers a "rescan this LOT" CTA. Fresh-scan allows it because
 * the operator can legitimately reshoot the just-finished LOT before
 * persisting. Recorded-edit disallows it because the LOT has already
 * been committed + (likely) pushed to cloud — destroying it from this
 * surface would orphan downstream state and bypass the proper
 * delete-session flow.
 */
sealed class LotReviewMode {
    data object FreshScan : LotReviewMode()
    data object RecordedEdit : LotReviewMode()
}

/**
 * Result of [LotReviewPersister.persist]. Sealed so call sites are
 * forced to acknowledge every terminal state (compile error on a new
 * case is the feature, not the bug). Saved carries the count so the
 * UI can render the right toast; SessionTombstoned tells the caller
 * the parent session was deleted (likely by another device) between
 * dialog open + Save tap, so the caller should also navigate away.
 */
sealed class LotReviewOutcome {
    data class Saved(val editedCount: Int) : LotReviewOutcome()
    data object NoChanges : LotReviewOutcome()
    data object SessionTombstoned : LotReviewOutcome()
    data class Error(val cause: Throwable) : LotReviewOutcome()
}

/**
 * Row-level edit summary returned to RDScannerScreen on Confirm. Carries
 * only what the persist boundary needs to write: per-row month count +
 * encoded monthsList, plus the per-account paid-till update (or null
 * when there's nothing to write for that rd_number).
 */
data class LotReviewEdit(
    val rowId: Long,
    val rdNumber: String,
    val newCount: Int,
    /** newest-first storage form ready for [RdNumber.monthsList], or null when count == 1. */
    val encodedMonthsList: String?,
    /** Newest selected month, used to advance the account's paid-till. */
    val newestSelected: MonthYear?,
    /** True when [newestSelected] regresses [accountLastPaidThrough]. */
    val isRegression: Boolean,
    /** Snapshot of the account's stored paid-till at review time. Null = never paid. */
    val accountLastPaidThrough: MonthYear?,
    val accountName: String?
)

/**
 * Full-screen review of every account in a LOT. Single editor for both
 * paths: fresh-scan (just-finished LOT, [LotReviewMode.FreshScan]) and
 * recorded-session edit (historical LOT, [LotReviewMode.RecordedEdit]).
 *
 * Replaced the legacy DefaulterAskDialog (yes/no popup) + DefaulterEditDialog
 * (per-row chip strip) with one always-visible UI per operator request.
 * Each row shows:
 *   - RD number + account name (or "(no profile yet)" subtext)
 *   - "Last paid: through {month}" banner when a paid-till exists
 *   - Inline regression hint when the current selection would move
 *     paid-till backward (warning, but not blocking)
 *   - Count stepper (1..MONTHS_MAX) on the right
 *   - Horizontal month bar of 12 back + 12 forward from auto-anchor
 *     (lazy-extends as scrolled), with the contiguous selected block
 *     highlighted
 *
 * Confirm collects every changed row + every paid-till advance into one
 * map and hands it back to the caller for atomic persistence. When any
 * row is a regression, a single batch confirm modal lists all of them
 * before persisting.
 */
@Composable
fun LotReviewScreen(
    mode: LotReviewMode,
    lotNumber: Int,
    rows: List<LotReviewRow>,
    onUpdateRow: (rowId: Long, newSelected: List<MonthYear>) -> Unit,
    onConfirm: (edits: List<LotReviewEdit>) -> Unit,
    onDiscard: () -> Unit,
    onRescanLot: (() -> Unit)? = null
) {
    var discardConfirmShown by remember { mutableStateOf(false) }
    var regressionConfirmShown by remember { mutableStateOf(false) }
    var overLimitShown by remember { mutableStateOf(false) }
    var skipGapPayload by remember {
        mutableStateOf<Pair<List<LotReviewEdit>, List<Pair<String, List<MonthYear>>>>?>(null)
    }
    var pendingEdits by remember { mutableStateOf<List<LotReviewEdit>?>(null) }

    // Android hardware/gesture back routes to the same discard-confirm
    // gate as the top-bar back arrow so in-flight edits aren't lost
    // when the operator hits the system back button. When a sub-dialog
    // is showing, the Dialog composable intercepts back via its own
    // onDismissRequest, so this BackHandler stays dormant in that
    // state — no manual `enabled` flag needed.
    BackHandler {
        discardConfirmShown = true
    }

    val changedCount by remember(rows) {
        derivedStateOf {
            // For now every row is "changed" because the new screen replaces
            // the old defaulter-only filter — the operator confirms every
            // account each LOT. Confirm count == row count.
            rows.size
        }
    }

    val lotTotal by remember(rows) {
        derivedStateOf { computeLotTotal(rows) }
    }

    // paneTitle tells TalkBack this is a new screen pane so focus
    // moves into it on appearance instead of remaining on the
    // parent (RDScannerScreen or SessionDetailScreen). The screen
    // reader announces "LOT #N review" when the overlay opens.
    val paneTitleText = stringResource(R.string.lotreview_title, lotNumber)
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { paneTitle = paneTitleText },
        color = BackgroundWhite
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            ReviewHeader(
                lotNumber = lotNumber,
                accountCount = rows.size,
                onBack = { discardConfirmShown = true }
            )

            HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rows, key = { it.rdNumber.id }) { row ->
                    ReviewRow(
                        row = row,
                        onUpdateSelected = { newSelected ->
                            onUpdateRow(row.rdNumber.id, newSelected)
                        }
                    )
                }
            }

            HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))

            ConfirmBar(
                changedCount = changedCount,
                lotTotal = lotTotal,
                onConfirm = {
                    // Validation chain order matters:
                    //   1. 20K cap — hard rule the portal rejects. Block save.
                    //   2. Skip-gap — soft warning. Operator can confirm through.
                    //   3. Regression — soft warning. Operator can confirm through.
                    // Skip-gap precedes regression because a row can be BOTH
                    // a skip and a regression in pathological cases; we want
                    // the skip-gap surface to fire first since it's the more
                    // operator-friendly framing ("you're skipping Sep 2025"
                    // vs "you're moving paid-till backward").
                    if (lotTotal.isOver) {
                        overLimitShown = true
                        return@ConfirmBar
                    }
                    // Only persist rows the operator actually changed —
                    // unedited rows are filtered out via [hasChanges]. This
                    // is what makes [LotReviewOutcome.NoChanges] reachable
                    // and avoids markDirty churn on rows the push worker
                    // doesn't need to re-send.
                    val edits = rows.mapNotNull { row ->
                        if (row.hasChanges) row.toEdit() else null
                    }
                    val gapNotices = collectSkipGapNotices(rows)
                    if (gapNotices.isNotEmpty()) {
                        skipGapPayload = edits to gapNotices
                        return@ConfirmBar
                    }
                    val regressions = edits.filter { it.isRegression }
                    if (regressions.isNotEmpty()) {
                        pendingEdits = edits
                        regressionConfirmShown = true
                    } else {
                        onConfirm(edits)
                    }
                }
            )
        }
    }

    if (discardConfirmShown) {
        DiscardConfirmDialog(
            onCancel = { discardConfirmShown = false },
            onDiscard = {
                discardConfirmShown = false
                onDiscard()
            }
        )
    }

    skipGapPayload?.let { (edits, notices) ->
        SkipGapConfirmDialog(
            notices = notices,
            onCancel = { skipGapPayload = null },
            onConfirm = {
                skipGapPayload = null
                // After skip-gap confirm, regression check still has to
                // run — these are independent safeguards covering
                // different invariants.
                val regressions = edits.filter { it.isRegression }
                if (regressions.isNotEmpty()) {
                    pendingEdits = edits
                    regressionConfirmShown = true
                } else {
                    onConfirm(edits)
                }
            }
        )
    }

    if (regressionConfirmShown) {
        val edits = pendingEdits ?: emptyList()
        val regressions = edits.filter { it.isRegression }
        RegressionConfirmDialog(
            regressions = regressions,
            onCancel = {
                regressionConfirmShown = false
                pendingEdits = null
            },
            onConfirm = {
                regressionConfirmShown = false
                pendingEdits = null
                onConfirm(edits)
            }
        )
    }

    if (overLimitShown) {
        OverLimitDialog(
            lotTotal = lotTotal,
            mode = mode,
            onCancel = { overLimitShown = false },
            onRescan = onRescanLot?.let { cb ->
                {
                    overLimitShown = false
                    cb()
                }
            }
        )
    }
}

/**
 * Converts the in-memory [LotReviewRow] state into the persist-boundary
 * [LotReviewEdit]. Storage stays newest-first per the locked decision —
 * the UI's oldest-first selection is reversed here so SyncRepository +
 * portal continue to round-trip without churn.
 */
private fun LotReviewRow.toEdit(): LotReviewEdit {
    val newestFirst = selected.sortedDescending()
    val encoded = if (count <= 1) null else MonthYear.encodeList(newestFirst)
    return LotReviewEdit(
        rowId = rdNumber.id,
        rdNumber = rdNumber.number,
        newCount = count,
        encodedMonthsList = encoded,
        newestSelected = newestSelected,
        isRegression = isRegression,
        accountLastPaidThrough = accountLastPaidThrough,
        accountName = accountName
    )
}

@Composable
private fun ReviewHeader(lotNumber: Int, accountCount: Int, onBack: () -> Unit) {
    GradientTopBar {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.content_desc_back),
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.lotreview_title, lotNumber),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Text(
                    text = if (accountCount == 1) {
                        stringResource(R.string.lotreview_subtitle_one)
                    } else {
                        stringResource(R.string.lotreview_subtitle_many, accountCount)
                    },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.85f)
                    )
                )
            }
        }
    }
}

@Composable
private fun ReviewRow(row: LotReviewRow, onUpdateSelected: (List<MonthYear>) -> Unit) {
    val isRegression = row.isRegression
    val bg = when {
        isRegression -> WarningAmber.copy(alpha = 0.08f)
        row.count > 1 -> PrimaryOrange.copy(alpha = 0.06f)
        else -> RowBackground
    }
    val borderColor = when {
        isRegression -> WarningAmber.copy(alpha = 0.45f)
        row.count > 1 -> PrimaryOrange.copy(alpha = 0.35f)
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.rdNumber.number,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitle = row.accountName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.lotreview_no_profile)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (row.accountName.isNullOrBlank()) TextSecondary else TextPrimary,
                        fontWeight = if (row.accountName.isNullOrBlank()) FontWeight.Normal else FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            CountStepper(
                count = row.count,
                onCountChange = { newCount ->
                    val safe = newCount.coerceIn(RdNumber.MONTHS_MIN, RdNumber.MONTHS_MAX)
                    if (safe == row.count) return@CountStepper
                    val newSelection = adjustSelection(row.selected, safe)
                    onUpdateSelected(newSelection)
                }
            )
        }

        row.accountLastPaidThrough?.let { lastPaid ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.lotreview_last_paid, lastPaid.formatShort()),
                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
            )
        }

        if (isRegression) {
            Spacer(modifier = Modifier.height(4.dp))
            val newest = row.newestSelected
            val lastPaid = row.accountLastPaidThrough
            if (newest != null && lastPaid != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = WarningAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            R.string.lotreview_regression_warning,
                            newest.formatShort(),
                            lastPaid.formatShort()
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = WarningAmber,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        MonthBar(
            selected = row.selected,
            onAnchorPick = { picked ->
                // Tap = re-anchor to picked (oldest). Extend forward by
                // (count - 1) months so the selection grows newer from
                // the tapped month, matching the locked direction.
                val newSelection = extendForward(picked, row.count)
                onUpdateSelected(newSelection)
            }
        )
    }
}

/**
 * Builds a new contiguous block of [count] months ending [count-1] months
 * after [anchor]. Anchor is treated as the OLDEST month per the locked
 * extend-forward direction, so the result is [anchor, anchor+1, ...,
 * anchor+count-1] in calendar order. Caller persists newest-first by
 * sorting descending at the persist boundary.
 */
private fun extendForward(anchor: MonthYear, count: Int): List<MonthYear> {
    val safe = count.coerceIn(RdNumber.MONTHS_MIN, RdNumber.MONTHS_MAX)
    val result = ArrayList<MonthYear>(safe)
    var cursor = anchor
    repeat(safe) {
        result.add(cursor)
        cursor = cursor.plusOneMonth()
    }
    return result
}

/**
 * Adjusts an existing selection to a new count without changing its
 * oldest anchor. Used by the count stepper so + adds newer months
 * forward and - drops the newest tail first. Empty selection falls
 * back to a forward block starting at MonthYear.current().
 */
private fun adjustSelection(current: List<MonthYear>, newCount: Int): List<MonthYear> {
    if (current.isEmpty()) return extendForward(MonthYear.current(), newCount)
    val anchor = current.minOrNull() ?: MonthYear.current()
    return extendForward(anchor, newCount)
}

@Composable
private fun CountStepper(count: Int, onCountChange: (Int) -> Unit) {
    val canDec = count > RdNumber.MONTHS_MIN
    val canInc = count < RdNumber.MONTHS_MAX
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(SurfaceWhite, RoundedCornerShape(24.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            contentDescription = stringResource(R.string.lotreview_stepper_decrease),
            enabled = canDec,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCountChange(count - 1)
            }
        )
        Box(
            modifier = Modifier
                .width(56.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.lotreview_months_label, count, count
                ),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (count > 1) PrimaryOrange else TextSecondary
                )
            )
        }
        StepperButton(
            icon = Icons.Default.Add,
            contentDescription = stringResource(R.string.lotreview_stepper_increase),
            enabled = canInc,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCountChange(count + 1)
            }
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // 44dp WCAG-compliant outer touch target wrapping a 36dp visible
    // chip — the standard wrap-pattern used across the app for chip-
    // sized controls so the touch target stays compliant even when
    // the rendered chip is intentionally smaller than the floor.
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (enabled) DisabledBackground else DisabledBackground.copy(alpha = 0.5f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) PrimaryOrange else DisabledContent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Horizontal scrollable month strip. Renders MONTH_BAR_BACK months before
 * the auto-anchor + MONTH_BAR_FORWARD months after, in calendar order
 * (oldest left, newest right). The contiguous selected block is rendered
 * with the primary fill; non-selected months use a muted chip; tap on a
 * non-selected month re-anchors via [onAnchorPick]. Tap on an already-
 * selected month is a no-op per the locked decision.
 *
 * Lazy: uses LazyRow with stable keys on [MonthYear.toToken] so scroll
 * performance is bounded by visible-cell count, not the 24-cell range.
 */
@Composable
private fun MonthBar(
    selected: List<MonthYear>,
    onAnchorPick: (MonthYear) -> Unit
) {
    val today = remember { MonthYear.current() }
    val haptics = LocalHapticFeedback.current
    val initialAnchor = remember { selected.minOrNull() ?: today }
    val range = remember(initialAnchor) {
        MonthYear.range(
            from = initialAnchor.minusBy(MONTH_BAR_BACK),
            to = initialAnchor.plusBy(MONTH_BAR_FORWARD)
        )
    }
    val selectedSet = remember(selected) { selected.toSet() }
    val listState = rememberLazyListState()

    // Visual midpoint of the selected block. For a single month this is the
    // month itself; for a contiguous block it is the middle index so the
    // block reads as centered to the operator on tap or count change.
    val centerIdx = remember(selected, range) {
        if (selected.isEmpty()) {
            range.indexOf(today).coerceAtLeast(0)
        } else {
            val firstIdx = range.indexOf(selected.first()).coerceAtLeast(0)
            val lastIdx = range.indexOf(selected.last()).coerceAtLeast(firstIdx)
            (firstIdx + lastIdx) / 2
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Side padding = (viewport - cell) / 2 makes the item at scroll
        // position 0 appear visually centered in the viewport, so
        // animateScrollToItem(centerIdx) centers the selection block.
        val sidePadding = ((maxWidth - MONTH_BAR_CELL_APPROX_WIDTH) / 2)
            .coerceAtLeast(2.dp)

        LaunchedEffect(centerIdx) {
            if (centerIdx in range.indices) {
                listState.animateScrollToItem(centerIdx)
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = sidePadding)
        ) {
            items(range, key = { it.toToken() }) { month ->
                val isSelected = month in selectedSet
                val isToday = month == today
                MonthBarCell(
                    month = month,
                    isSelected = isSelected,
                    isToday = isToday,
                    onClick = {
                        if (!isSelected) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onAnchorPick(month)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MonthBarCell(
    month: MonthYear,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PrimaryOrange else SurfaceWhite
    val textColor = if (isSelected) Color.White else TextPrimary
    // 44dp WCAG touch target wraps the 40dp visible chip. Operator can
    // tap any non-selected cell to re-anchor; selected cells are no-ops
    // per the locked decision.
    Box(
        modifier = Modifier
            .height(44.dp)
            .clickable(enabled = !isSelected, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(40.dp)
                .background(bg, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp)
                .then(
                    if (isToday && !isSelected) Modifier.drawBehind {
                        val r = 3.dp.toPx()
                        drawCircle(
                            color = PrimaryOrange,
                            radius = r,
                            center = Offset(size.width / 2f, size.height - r * 2f)
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = month.formatShort(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun ConfirmBar(
    changedCount: Int,
    lotTotal: LotTotal,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceWhite)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        LotTotalLine(lotTotal = lotTotal)
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = if (changedCount == 1) {
                    stringResource(R.string.lotreview_confirm_one)
                } else {
                    stringResource(R.string.lotreview_confirm_many, changedCount)
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun LotTotalLine(lotTotal: LotTotal) {
    val isOver = lotTotal.isOver
    val accentColor = if (isOver) AccentCoral else AccentMint
    val totalText = formatRupees(lotTotal.verifiedRupees)
    val limitText = formatRupees(lotTotal.limit)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accentColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isOver) {
                    stringResource(R.string.lotreview_total_over_limit, totalText, limitText)
                } else {
                    stringResource(R.string.lotreview_total_under_limit, totalText, limitText)
                },
                style = MaterialTheme.typography.labelMedium.copy(
                    color = if (isOver) AccentCoral else TextSecondary,
                    fontWeight = if (isOver) FontWeight.SemiBold else FontWeight.Medium,
                    fontFamily = FontFamily.SansSerif
                )
            )
        }
        if (lotTotal.unverifiedRowCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.lotreview_total_unverified,
                    lotTotal.unverifiedRowCount
                ),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextSecondary.copy(alpha = 0.75f)
                ),
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

@Composable
private fun OverLimitDialog(
    lotTotal: LotTotal,
    mode: LotReviewMode,
    onCancel: () -> Unit,
    onRescan: (() -> Unit)?
) {
    // Rescan visibility is gated on BOTH the mode AND the callback
    // being present — defense in depth. The mode is the authoritative
    // contract (RecordedEdit must never destroy a committed LOT); the
    // null-callback check is the belt-and-braces fallback in case a
    // caller misconfigures FreshScan + null callback. If a careless
    // future refactor passes RecordedEdit + non-null callback, the
    // mode check still hides the destructive button.
    //
    // We derive a single nullable callback so Kotlin can smart-cast
    // inside the `if (rescanCallback != null)` branch — avoids the
    // 'condition is always true' lint warning that fires when you
    // double-check `boolean && nullable != null` after a derived val.
    val rescanCallback: (() -> Unit)? =
        if (mode is LotReviewMode.FreshScan) onRescan else null
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceWhite,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(AccentCoral.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = AccentCoral,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.lotreview_over_limit_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.lotreview_over_limit_body,
                        formatRupees(lotTotal.verifiedRupees),
                        formatRupees(lotTotal.excess)
                    ),
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                if (lotTotal.unverifiedRowCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.lotreview_over_limit_unverified_note,
                            lotTotal.unverifiedRowCount
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = AccentCoral,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                if (rescanCallback != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.lotreview_over_limit_cancel),
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Button(
                            onClick = rescanCallback,
                            modifier = Modifier.weight(1.4f),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.lotreview_over_limit_rescan),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.lotreview_over_limit_close),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Walks the rows and collects per-row skip-gap notices: rd_number paired
 * with the list of months that would be silently skipped between
 * `nextMonth(accountLastPaidThrough)` and the operator's chosen block
 * start. Empty when no row skips. Used as the input to
 * [SkipGapConfirmDialog].
 */
private fun collectSkipGapNotices(
    rows: List<LotReviewRow>
): List<Pair<String, List<MonthYear>>> = rows.mapNotNull { row ->
    val skipped = row.skipsMonthsAfterLastPaid()
    if (skipped.isEmpty()) null
    else row.rdNumber.number to skipped
}

/**
 * Surfaced before save when one or more rows would skip months between
 * their account's last-paid baseline and the chosen block start. Soft
 * warning — operator can confirm through ("paper book is truth"); the
 * dialog exists to catch input errors not to forbid skips. Skip-gap +
 * regression are independent invariants and the confirm flow runs the
 * regression check AFTER this dialog closes.
 */
@Composable
private fun SkipGapConfirmDialog(
    notices: List<Pair<String, List<MonthYear>>>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceWhite,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.lotreview_skipgap_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (notices.size == 1) {
                        stringResource(R.string.lotreview_skipgap_body_intro_one)
                    } else {
                        stringResource(R.string.lotreview_skipgap_body_intro_many, notices.size)
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    notices.forEach { (rdNumber, skipped) ->
                        val skippedLabel = skipped.joinToString(" + ") { it.formatShort() }
                        val lastPaid = skipped.first().minusOneMonth().formatShort()
                        Text(
                            text = stringResource(
                                R.string.lotreview_skipgap_body_row,
                                rdNumber,
                                skippedLabel,
                                lastPaid
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.lotreview_skipgap_body_outro),
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.lotreview_skipgap_cancel),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.lotreview_skipgap_confirm),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formats a rupee integer as a locale-aware grouped string. ₹20000 →
 * "20,000" in en-US and "20,000" in hi-IN (Indian grouping uses lakhs:
 * 1,00,000 not 100,000). Symbol stays out of the format; callers
 * prefix the rendered string with their own ₹.
 *
 * Composable + remember caches the NumberFormat instance per
 * composition (QC-F HIGH fix). Locale.getDefault() at remember time
 * is fine: locale change triggers a config-change → composition
 * recreate → new formatter.
 */
@Composable
private fun formatRupees(value: Int): String {
    val formatter = remember {
        java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault())
    }
    return formatter.format(value.toLong())
}

@Composable
private fun DiscardConfirmDialog(onCancel: () -> Unit, onDiscard: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceWhite,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.lotreview_discard_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.lotreview_discard_body),
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.lotreview_discard_cancel),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDiscard()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.lotreview_discard_confirm),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegressionConfirmDialog(
    regressions: List<LotReviewEdit>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceWhite,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.lotreview_regression_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (regressions.size == 1) {
                        stringResource(R.string.lotreview_regression_body_intro, 1)
                    } else {
                        stringResource(
                            R.string.lotreview_regression_body_intro_many,
                            regressions.size
                        )
                    },
                    style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    regressions.forEach { edit ->
                        val nameLabel = edit.accountName?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.lotreview_no_profile)
                        Text(
                            text = stringResource(
                                R.string.lotreview_regression_body_row,
                                "RD #${edit.rdNumber}",
                                nameLabel,
                                edit.accountLastPaidThrough?.formatShort() ?: "—",
                                edit.newestSelected?.formatShort() ?: "—"
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.lotreview_regression_body_outro),
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.lotreview_regression_cancel),
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.lotreview_regression_confirm),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun MonthYear.minusBy(n: Int): MonthYear {
    var cursor = this
    repeat(n.coerceAtLeast(0)) { cursor = cursor.minusOneMonth() }
    return cursor
}

private fun MonthYear.plusBy(n: Int): MonthYear {
    var cursor = this
    repeat(n.coerceAtLeast(0)) { cursor = cursor.plusOneMonth() }
    return cursor
}

private const val MONTH_BAR_BACK = 12
private const val MONTH_BAR_FORWARD = 12

// Approximate visual width of a MonthBarCell ("MMM YYYY" label + 12dp×2
// inner padding). Used to compute the LazyRow side padding so that
// animateScrollToItem(idx) lands the cell at the viewport's horizontal
// midpoint. Off-by-a-few-dp is invisible at this scale; the contract is
// "feels centered", not "pixel exact".
private val MONTH_BAR_CELL_APPROX_WIDTH = 72.dp

/**
 * Hard limit the portal enforces on each LOT's combined rupee total
 * (∑ monthlyAmount × monthsPaid across all rows). We surface the same
 * cap on the phone so the operator catches over-limit LOTs before
 * sync would reject them at the portal-edit boundary. Per-LOT, not
 * per-session.
 */
const val LOT_TOTAL_LIMIT_RUPEES = 20_000

/**
 * Snapshot of a LOT's total state used by the live running total +
 * the over-limit popup. [verifiedRupees] only sums rows with a known
 * [LotReviewRow.accountMonthlyAmount]; rows without a profile are
 * counted in [unverifiedRowCount] and shown to the operator so they
 * know the verified figure is a floor, not the absolute total.
 */
data class LotTotal(
    val verifiedRupees: Int,
    val unverifiedRowCount: Int,
    val totalRows: Int,
    val limit: Int = LOT_TOTAL_LIMIT_RUPEES
) {
    // Cap is EXCLUSIVE — portal contract per spec D24 / §15.5.12
    // is `Σ <= 20,000`, so exactly ₹20,000 IS allowed by the
    // portal and the phone must mirror that. Using `>=` here was
    // a regression I shipped in commit 9824fed that wrongly
    // rejected exactly-the-limit LOTs the portal would have
    // accepted. The matching live-total chip at
    // RDScannerScreen.kt:1579 already uses `>`, so this also
    // restores phone/chip consistency. If the portal contract
    // ever changes to `<` (strict), both phone surfaces flip
    // together via this single const.
    val isOver: Boolean get() = verifiedRupees > limit
    val excess: Int get() = (verifiedRupees - limit).coerceAtLeast(0)
    val hasAnyVerified: Boolean get() = totalRows > unverifiedRowCount
}

fun computeLotTotal(rows: List<LotReviewRow>): LotTotal {
    var sum = 0
    var unverified = 0
    rows.forEach { row ->
        val contribution = row.rupeeContribution
        if (contribution != null) sum += contribution
        else unverified++
    }
    return LotTotal(
        verifiedRupees = sum,
        unverifiedRowCount = unverified,
        totalRows = rows.size
    )
}

/**
 * Input-side boundary of the editor. Builds [LotReviewRow] snapshots
 * for the rows in a LOT, eagerly resolving account profiles BEFORE the
 * screen opens so the operator never sees an async-race flip the
 * auto-anchor mid-interaction.
 *
 * Used by both [RDScannerScreen] (fresh-scan after finishing a LOT) and
 * [SessionDetailScreen] (retroactive edit on a recorded LOT). Storing
 * this logic next to [LotReviewRow] keeps the data shape and its
 * builder in one place — a future field added to [LotReviewRow] forces
 * a corresponding update here.
 *
 * Auto-anchor contract: when an account profile carries a non-null
 * `lastPaidThrough`, the auto-suggested block starts at
 * `nextMonth(lastPaidThrough)`; otherwise it falls back to the LOT
 * date. A stored monthsList on the rd_number wins over both (we honor
 * the operator's prior decision).
 */
object LotReviewBuilder {
    suspend fun build(
        app: com.qrscanner.app.QRScannerApp,
        lotId: Long,
        lotTimestamp: Long
    ): List<LotReviewRow> {
        val rdRows = app.database.rdNumberDao().getNumbersForLotSync(lotId)
        val anchor = MonthYear.fromEpochMillis(
            lotTimestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
        // Single batched lookup instead of N+1 per-row queries. For an
        // 80-row LOT this drops 80 round-trips to 1 (~80-240ms saved
        // before the review screen renders). The Map gives O(1) access
        // in the map{} below.
        val accountsByRdNumber: Map<String, RdAccount> = if (rdRows.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                app.database.rdAccountDao()
                    .findByRdNumbers(rdRows.map { it.number })
                    .associateBy { it.rdNumber }
            }.onFailure {
                // Diagnostic trail for a DB error here is critical: a
                // silent empty map causes every LOT review row to lose
                // its name/monthlyAmount/lastPaidThrough hint, and the
                // operator picks wrong months. Without the log,
                // intermittent DB corruption masquerades as "the data
                // just isn't there yet" and the bug never gets filed.
                android.util.Log.w(
                    "LotReviewBuilder",
                    "batched account lookup failed for lotId=$lotId; rows will render without account hints",
                    it
                )
            }.getOrDefault(emptyMap())
        }
        return rdRows.map { rd ->
            val account = accountsByRdNumber[rd.number]
            val accountLastPaid = account?.lastPaidThrough?.let { MonthYear.parseToken(it) }
            val existing = MonthYear.parseList(rd.monthsList, rd.monthsPaid)
            val selected: List<MonthYear> = if (existing != null) {
                // Storage is newest-first; UI wants oldest-first (anchor=first).
                existing.sorted()
            } else {
                val startAt = accountLastPaid?.plusOneMonth() ?: anchor
                buildList {
                    var cursor = startAt
                    repeat(rd.monthsPaid.coerceAtLeast(1)) {
                        add(cursor)
                        cursor = cursor.plusOneMonth()
                    }
                }
            }
            LotReviewRow(
                rdNumber = rd,
                accountName = account?.name,
                accountLastPaidThrough = accountLastPaid,
                accountMonthlyAmount = account?.monthlyAmount,
                selected = selected,
                // originalSelected reflects the DB's ACTUAL stored
                // state, not the UI's auto-suggested default. For
                // rows with no stored monthsList (just-scanned RDs),
                // baseline is empty — so confirming the auto-anchor
                // counts as a real edit and advances lastPaidThrough.
                // Using `selected` here was the regression that
                // silently dropped the scan-and-confirm advance.
                originalSelected = existing?.sorted() ?: emptyList()
            )
        }
    }
}

/**
 * Persists the LOT review's per-row month-list deltas across config
 * change + process death. Wire format: "rowId=YYYY-MM,YYYY-MM;...".
 * Empty map encodes to empty string. Malformed segments are skipped
 * on restore (defensive — a Bundle truncation never crashes the
 * screen). Bundle-size guarantee: each segment ~30 bytes for typical
 * 1-3 month selections; a 50-row LOT stays well under 2KB.
 *
 * Lives in LotReviewScreen.kt (not the call sites) so BOTH
 * RDScannerScreen + SessionDetailScreen use the same saver — process
 * death restores in-flight edits identically on both editor paths.
 */
internal val LotReviewEditsSaver: androidx.compose.runtime.saveable.Saver<Map<Long, List<MonthYear>>, String> =
    androidx.compose.runtime.saveable.Saver(
        save = { deltas ->
            if (deltas.isEmpty()) "" else deltas.entries.joinToString(";") { (id, months) ->
                "$id=${MonthYear.encodeList(months)}"
            }
        },
        restore = { token ->
            if (token.isBlank()) emptyMap()
            else token.split(";").mapNotNull { entry ->
                val sep = entry.indexOf('=')
                if (sep <= 0) return@mapNotNull null
                val id = entry.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                val listToken = entry.substring(sep + 1)
                if (listToken.isBlank()) return@mapNotNull null
                val count = listToken.count { it == ',' } + 1
                val parsed = MonthYear.parseList(listToken, count) ?: return@mapNotNull null
                id to parsed
            }.toMap()
        }
    )

/**
 * Output-side boundary of the editor. Atomically applies a confirmed
 * edit batch and returns an exhaustive [LotReviewOutcome] so the
 * caller can branch on every terminal state at compile time (adding a
 * new outcome breaks every consumer until handled — by design).
 *
 * Responsibilities collected here so future edits to the editor's
 * persistence contract happen in exactly one place:
 *   1. Tombstone guard — if the parent session was deleted from
 *      another device while the editor was open, return
 *      [LotReviewOutcome.SessionTombstoned] without touching anything
 *      else; resurrecting orphan rd_numbers via a doomed push would
 *      create a sync mess.
 *   2. Per-row writes: `rd_numbers.updateMonths` + `markDirty`. The
 *      DIRTY flip is what tells the push worker this row needs to
 *      sync; updateMonths alone leaves the row at its prior
 *      syncStatus. Steps 2 and 3 run inside a single Room
 *      transaction so a process kill mid-loop can never leave the
 *      DB with some rows updated-but-not-DIRTY (data the operator
 *      thinks is saved but the push worker never sees) or with N
 *      rows persisted and M unpersisted — partial saves would
 *      confuse the operator and produce divergent cloud state.
 *   3. `rd_accounts.lastPaidThrough` writeback, routed by
 *      [LotReviewEdit.isRegression]. Regressions use the explicit
 *      setter (no monotonic guard) because the operator has confirmed
 *      a backward move through [RegressionConfirmDialog] — "paper
 *      book is truth". Advances use the monotonic setter to match
 *      auto-finalize semantics; concurrent newer writes from another
 *      device won't be overwritten.
 *   4. `LOCAL_DEFAULTER_EDIT` sync event insertion so the bell
 *      history reflects "You edited defaulter months for N accounts".
 *   5. `syncScheduler.enqueuePush()` to kick the push worker.
 *
 * Errors from step 2-3 propagate as [LotReviewOutcome.Error]; errors
 * from step 4-5 are swallowed and logged because they're observability
 * concerns, not data-correctness concerns (the rows are already
 * DIRTY; the push will retry).
 */
object LotReviewPersister {
    suspend fun persist(
        app: com.qrscanner.app.QRScannerApp,
        sessionId: Long,
        edits: List<LotReviewEdit>
    ): LotReviewOutcome {
        if (edits.isEmpty()) return LotReviewOutcome.NoChanges
        return try {
            val parent = app.database.scanSessionDao().getSessionById(sessionId)
            if (parent == null || parent.deletedAt != null) {
                LotReviewOutcome.SessionTombstoned
            } else {
                val now = System.currentTimeMillis()
                // Single transaction across the whole edit batch: a
                // process kill mid-loop rolls back ALL writes rather
                // than leaving partial state. Both per-row writes
                // (updateMonths + markDirty) and the rd_accounts
                // writeback must be atomic — see KDoc step 2 for the
                // rationale.
                app.database.withTransaction {
                    edits.forEach { edit ->
                        app.database.rdNumberDao().updateMonths(
                            edit.rowId,
                            edit.newCount,
                            edit.encodedMonthsList
                        )
                        app.database.rdNumberDao().markDirty(edit.rowId, now)
                        val newest = edit.newestSelected ?: return@forEach
                        val token = newest.toToken()
                        if (edit.isRegression) {
                            app.database.rdAccountDao()
                                .setLastPaidThroughExplicit(edit.rdNumber, token, now)
                        } else {
                            app.database.rdAccountDao()
                                .updateLastPaidThroughMonotonic(edit.rdNumber, token, now)
                        }
                    }
                    // Re-mark the parent session DIRTY so the next push
                    // cycle re-runs pushSession which recomputes the
                    // denormalized `default_count` from the current
                    // rd_numbers set. Without this, an already-SYNCED
                    // session whose defaulters are edited after the
                    // fact leaves cloud's default_count stale, which is
                    // what the portal Sessions list reads directly.
                    // See ScanSessionDao.markSessionDirtyForChildEdit
                    // KDoc for the full causal chain.
                    app.database.scanSessionDao().markSessionDirtyForChildEdit(sessionId, now)
                }
                // sync_event insert + push enqueue stay OUTSIDE the
                // transaction: they're observability concerns. If the
                // sync_event insert fails, the bell history misses
                // this entry but the data is correctly persisted +
                // DIRTY. If enqueuePush fails, the rows are still
                // DIRTY so the next scheduled WorkManager tick picks
                // them up — and the Home screen's sync_pill surfaces
                // PENDING state to the operator regardless.
                runCatching {
                    val settings = app.database.deviceSettingsDao().get()
                    val rowWord = if (edits.size == 1) "account" else "accounts"
                    app.database.syncEventDao().insert(
                        com.qrscanner.app.data.SyncEvent(
                            occurredAt = now,
                            type = com.qrscanner.app.data.SyncEventType.LOCAL_DEFAULTER_EDIT,
                            sessionCloudId = parent.cloudId,
                            originDeviceCloudId = settings?.deviceCloudId,
                            originDeviceName = settings?.deviceName,
                            originOperatorName = settings?.operatorName,
                            payloadSummary = "edited defaulter months for ${edits.size} $rowWord"
                        )
                    )
                }.onFailure {
                    android.util.Log.w(
                        "LotReviewPersister",
                        "local sync_event insert failed",
                        it
                    )
                }
                runCatching { app.syncScheduler.enqueuePush() }
                    .onFailure {
                        android.util.Log.w(
                            "LotReviewPersister",
                            "deferred sync enqueue failed",
                            it
                        )
                    }
                LotReviewOutcome.Saved(editedCount = edits.size)
            }
        } catch (t: Throwable) {
            LotReviewOutcome.Error(cause = t)
        }
    }
}
