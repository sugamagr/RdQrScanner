package com.qrscanner.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qrscanner.app.R
import com.qrscanner.app.data.RdNumber
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
    val selected: List<MonthYear>
) {
    val count: Int get() = selected.size

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
 * Mandatory full-screen review of every account in the just-finished LOT.
 *
 * Replaces the old DefaulterAskDialog (yes/no popup) + DefaulterEditDialog
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
    lotNumber: Int,
    rows: List<LotReviewRow>,
    onUpdateRow: (rowId: Long, newSelected: List<MonthYear>) -> Unit,
    onConfirm: (edits: List<LotReviewEdit>) -> Unit,
    onDiscard: () -> Unit
) {
    var discardConfirmShown by remember { mutableStateOf(false) }
    var regressionConfirmShown by remember { mutableStateOf(false) }
    var pendingEdits by remember { mutableStateOf<List<LotReviewEdit>?>(null) }

    val changedCount by remember(rows) {
        derivedStateOf {
            // For now every row is "changed" because the new screen replaces
            // the old defaulter-only filter — the operator confirms every
            // account each LOT. Confirm count == row count.
            rows.size
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = BackgroundWhite) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                onConfirm = {
                    val edits = rows.map { it.toEdit() }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceWhite)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.content_desc_back),
                tint = TextPrimary
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.lotreview_title, lotNumber),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
            Text(
                text = if (accountCount == 1) {
                    stringResource(R.string.lotreview_subtitle_one)
                } else {
                    stringResource(R.string.lotreview_subtitle_many, accountCount)
                },
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
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
                    )
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
                            R.string.lotreview_regression_one,
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(SurfaceWhite, RoundedCornerShape(22.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        StepperButton(
            icon = Icons.Default.Remove,
            enabled = canDec,
            onClick = { onCountChange(count - 1) }
        )
        Box(
            modifier = Modifier
                .width(60.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.lotreview_months_label, count),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (count > 1) PrimaryOrange else TextSecondary
                )
            )
        }
        StepperButton(
            icon = Icons.Default.Add,
            enabled = canInc,
            onClick = { onCountChange(count + 1) }
        )
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (enabled) DisabledBackground else DisabledBackground.copy(alpha = 0.5f),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) PrimaryOrange else DisabledContent,
            modifier = Modifier.size(18.dp)
        )
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
    val anchor = remember(selected) { selected.minOrNull() ?: today }
    val range = remember(anchor) {
        MonthYear.range(
            from = anchor.minusBy(MONTH_BAR_BACK),
            to = anchor.plusBy(MONTH_BAR_FORWARD)
        )
    }
    val selectedSet = remember(selected) { selected.toSet() }
    val listState = rememberLazyListState()

    LaunchedEffect(anchor, range) {
        val idx = range.indexOf(anchor).coerceAtLeast(0)
        // Center the anchor: scroll so the selected block sits ~2 cells
        // from the left edge to leave room for backward picks.
        listState.scrollToItem((idx - 2).coerceAtLeast(0))
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(range, key = { it.toToken() }) { month ->
            val isSelected = month in selectedSet
            MonthBarCell(
                month = month,
                isSelected = isSelected,
                onClick = { if (!isSelected) onAnchorPick(month) }
            )
        }
    }
}

@Composable
private fun MonthBarCell(
    month: MonthYear,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isSelected) PrimaryOrange else SurfaceWhite
    val textColor = if (isSelected) Color.White else TextPrimary
    val borderColor = if (isSelected) PrimaryOrange else TextSecondary.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .height(40.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(enabled = !isSelected, onClick = onClick)
            .padding(horizontal = 12.dp),
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

@Composable
private fun ConfirmBar(changedCount: Int, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceWhite)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
private fun DiscardConfirmDialog(onCancel: () -> Unit, onDiscard: () -> Unit) {
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
                        onClick = onDiscard,
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
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
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
