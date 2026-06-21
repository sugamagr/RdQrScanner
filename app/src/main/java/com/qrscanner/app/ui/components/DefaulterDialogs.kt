package com.qrscanner.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.ui.theme.DisabledBackground
import com.qrscanner.app.ui.theme.DisabledContent
import com.qrscanner.app.ui.theme.RowBackground
import com.qrscanner.app.util.MonthYear
import kotlinx.coroutines.delay

/**
 * Per-row edit state captured by [DefaulterEditDialog].
 *
 * Invariant: `months.size == count`. Mutations go through [withCount] /
 * [shiftWindow] / [swapMonth] so the invariant is impossible to violate
 * from the caller side.
 *
 * `accountLastPaidThrough` is the YYYY-MM month parsed from the matching
 * [com.qrscanner.app.data.RdAccount.lastPaidThrough] (null when the row's
 * RD number has no account profile, or the account has never been paid).
 * Drives two pieces of UX:
 *   - auto-suggest: when the row has no stored monthsList, the block
 *     anchors at nextMonth(lastPaidThrough) instead of LOT date
 *   - banner: a small "Last paid: through Aug 2025" strip above the
 *     chip strip when non-null
 *   - skip-gap detection: Save fires a confirm modal if the block's
 *     oldest month is strictly later than nextMonth(lastPaidThrough)
 */
data class DefaulterRowDraft(
    val count: Int,
    val months: List<MonthYear>,
    val accountLastPaidThrough: MonthYear? = null
) {
    fun withCount(newCount: Int, today: MonthYear = MonthYear.current()): DefaulterRowDraft {
        val safe = newCount.coerceIn(RdNumber.MONTHS_MIN, RdNumber.MONTHS_MAX)
        if (safe == count) return this
        val newMonths = when {
            safe > count -> {
                // Extend the window backward from the current oldest, so adding
                // a month means 'one more month of arrears'.
                val oldest = months.lastOrNull() ?: today
                val extra = (1..(safe - count)).map { delta ->
                    var cursor = oldest
                    repeat(delta) { cursor = cursor.minusOneMonth() }
                    cursor
                }
                months + extra
            }
            else -> months.take(safe)
        }
        return DefaulterRowDraft(safe, newMonths)
    }

    fun shiftWindow(forward: Boolean, today: MonthYear = MonthYear.current()): DefaulterRowDraft {
        if (months.isEmpty()) return this
        // Future months allowed (prepayment scenario). Forward shift can
        // walk past today; the grid + chip strip don't gate on it anymore.
        // 'today' kept on the signature for back-compat with call sites
        // and the corresponding canShiftLater UI hint downstream.
        val shifted = months.map { if (forward) it.plusOneMonth() else it.minusOneMonth() }
        return copy(months = shifted)
    }

    /**
     * Re-anchors the contiguous month block at [picked] (treated as the
     * newest month) and rebuilds backward. RD payments are inherently
     * sequential — the prior swap-single-cell semantics allowed gappy
     * selections that didn't reflect any real-world payment pattern.
     * The chipIndex param is kept on the call site for back-compat but
     * the index itself is now irrelevant: the picked month always
     * becomes the trailing edge of the new block. Matches the portal's
     * EditDefaulterDialog buildBlockEndingAt model exactly so the two
     * surfaces agree on the contract.
     */
    fun swapMonth(index: Int, picked: MonthYear): DefaulterRowDraft {
        val _ignored = index
        if (count < 1) return this
        val rebuilt = buildList {
            var cursor = picked
            repeat(count) {
                add(cursor)
                cursor = cursor.minusOneMonth()
            }
        }
        return copy(months = rebuilt)
    }

    fun encodeOrNull(): String? = if (count <= 1) null else MonthYear.encodeList(months)

    /**
     * The block's oldest month — i.e. the month the operator says
     * payment STARTED. With the contiguous-block sequential rule, this
     * is the last element of [months] (newest-first ordering).
     */
    fun blockStart(): MonthYear? = months.lastOrNull()

    /**
     * True iff this row would skip months between
     * `nextMonth(accountLastPaidThrough)` and [blockStart]. Returns
     * false if there's no account profile or no stored last-paid month
     * (no baseline to detect a skip against).
     */
    fun skipsMonthsAfterLastPaid(): List<MonthYear> {
        val lastPaid = accountLastPaidThrough ?: return emptyList()
        val start = blockStart() ?: return emptyList()
        val expectedStart = lastPaid.plusOneMonth()
        if (start <= expectedStart) return emptyList()
        // Walk forward from expectedStart until reaching start (exclusive)
        val skipped = mutableListOf<MonthYear>()
        var cursor = expectedStart
        while (cursor < start) {
            skipped += cursor
            cursor = cursor.plusOneMonth()
        }
        return skipped
    }

    companion object {
        /**
         * Builds a row draft for an existing [RdNumber] scan. When the
         * row has no stored `monthsList` (operator hasn't picked yet)
         * AND we have a matching account profile with a known
         * `lastPaidThrough`, anchor the auto-window at the next month
         * after that (prepayment-aware default). Otherwise fall back to
         * the LOT-date anchor.
         */
        fun fromRow(
            row: RdNumber,
            today: MonthYear = MonthYear.current(),
            accountLastPaidThrough: MonthYear? = null
        ): DefaulterRowDraft {
            val resolvedAnchor = when {
                row.monthsList != null -> today
                accountLastPaidThrough != null -> accountLastPaidThrough.plusOneMonth()
                else -> today
            }
            val resolved = MonthYear.resolveOrAuto(row.monthsList, row.monthsPaid, resolvedAnchor)
            return DefaulterRowDraft(
                count = row.monthsPaid,
                months = resolved,
                accountLastPaidThrough = accountLastPaidThrough
            )
        }
    }
}

/**
 * Asks whether the just-saved LOT contains any defaulter accounts (paid > 1 month).
 *
 * Lightweight prompt with two clear actions: dismiss to the "all 1 month"
 * happy path, or proceed to the editor for the rare-but-important case.
 */
@Composable
fun DefaulterAskDialog(
    lotNumber: Int,
    onNo: () -> Unit,
    onYes: () -> Unit
) {
    Dialog(
        onDismissRequest = { /* require explicit choice */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                DialogHeader(
                    title = "Any defaulters in LOT $lotNumber?",
                    subtitle = "Mark accounts that paid for more than one month."
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onYes,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Yes, mark defaulters", fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onNo,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DisabledBackground,
                        contentColor = Color(0xFF1A1A2E)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("No, all paid 1 month", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Editable list of RD numbers with a per-row stepper, month-window chip
 * strip, and a < > pair to shift the window earlier / later. Long-pressing
 * a chip swaps that single month via [MonthPickerDialog].
 *
 * The dialog tracks a [DefaulterRowDraft] per row locally and only emits
 * changed rows (different count or different month list) to [onSave], so
 * the caller writes the minimum set of UPDATEs.
 *
 * Each change is `(newCount, encodedMonthsList?)`. The list is null when
 * the row is being demoted back to 1 month (non-defaulter).
 */
@Composable
fun DefaulterEditDialog(
    lotNumber: Int,
    numbers: List<RdNumber>,
    anchorTimestamp: Long,
    onDismiss: () -> Unit,
    onSave: (changes: Map<Long, Pair<Int, String?>>) -> Unit,
    accountLastPaidLookup: suspend (String) -> MonthYear? = { null }
) {
    val today = remember(anchorTimestamp) { MonthYear.fromEpochMillis(anchorTimestamp) }

    // Initial drafts seeded WITHOUT account memory so the dialog opens
    // immediately on the row data; the lookup result feeds in later via
    // a focused state update that preserves operator edits in flight.
    val initial = remember(numbers) {
        numbers.associate { it.id to DefaulterRowDraft.fromRow(it, today, null) }
    }
    val draft = remember(initial) {
        mutableStateMapOf<Long, DefaulterRowDraft>().apply { putAll(initial) }
    }
    val accountMonths = remember(numbers) {
        mutableStateMapOf<String, MonthYear?>().apply {
            numbers.forEach { put(it.number, null) }
        }
    }

    // Look up rd_accounts.lastPaidThrough off the composition thread.
    // When a result lands, we:
    //   1. record it in accountMonths (drives the per-row banner)
    //   2. patch the draft IN PLACE for rows that still match their
    //      original (operator-untouched) state — preserves in-flight
    //      edits + re-anchors the auto-suggest for unedited rows.
    // Critically, we do NOT re-key remember(initial) on accountMonths;
    // that would wipe the operator's in-progress edits every time a
    // lookup completed (oracle finding self-sweep A).
    LaunchedEffect(numbers) {
        for (rd in numbers.distinctBy { it.number }) {
            val last = runCatching { accountLastPaidLookup(rd.number) }
                .onFailure { android.util.Log.w("DefaulterEditDialog", "lookup failed for ${rd.number}", it) }
                .getOrNull()
            accountMonths[rd.number] = last
            if (last == null) continue
            for (row in numbers.filter { it.number == rd.number }) {
                val current = draft[row.id] ?: continue
                val original = initial[row.id] ?: continue
                val untouched = current.count == original.count &&
                    current.months == original.months
                if (!untouched) {
                    draft[row.id] = current.copy(accountLastPaidThrough = last)
                    continue
                }
                val reseeded = DefaulterRowDraft.fromRow(row, today, last)
                draft[row.id] = reseeded
            }
        }
    }
    var pendingSkipGap by remember { mutableStateOf<Map<Long, Pair<Int, String?>>?>(null) }
    var skipGapMessage by remember { mutableStateOf<String?>(null) }

    val changedCount = numbers.count { rd ->
        val original = initial[rd.id] ?: return@count false
        val current = draft[rd.id] ?: return@count false
        current.count != original.count || current.months != original.months
    }
    val defaulterCount = numbers.count { (draft[it.id]?.count ?: it.monthsPaid) > 1 }

    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }

    Dialog(
        onDismissRequest = { /* prevent accidental loss of in-progress edits */ },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                DialogHeader(
                    title = "LOT $lotNumber defaulters",
                    subtitle = if (defaulterCount > 0) {
                        "$defaulterCount marked • ${numbers.size} total · long-press to anchor block end"
                    } else {
                        "Tap + on rows that paid more than one month"
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(numbers, key = { it.id }) { rdNumber ->
                        val current = draft[rdNumber.id] ?: DefaulterRowDraft.fromRow(rdNumber, today)
                        DefaulterRow(
                            number = rdNumber.number,
                            draftState = current,
                            today = today,
                            onCountChange = { newCount ->
                                draft[rdNumber.id] = current.withCount(newCount, today)
                            },
                            onShiftEarlier = {
                                draft[rdNumber.id] = current.shiftWindow(forward = false, today = today)
                            },
                            onShiftLater = {
                                draft[rdNumber.id] = current.shiftWindow(forward = true, today = today)
                            },
                            onLongPressChip = { index ->
                                pickerTarget = PickerTarget(rdNumber.id, index)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = com.qrscanner.app.ui.theme.TextTertiary.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = {
                            val changes = numbers.mapNotNull { rd ->
                                val before = initial[rd.id] ?: return@mapNotNull null
                                val after = draft[rd.id] ?: return@mapNotNull null
                                if (after.count == before.count && after.months == before.months) return@mapNotNull null
                                rd.id to (after.count to after.encodeOrNull())
                            }.toMap()
                            // Skip-gap detection per user spec: if any
                            // changed row has block_start > nextMonth(
                            // lastPaidThrough), surface a confirm modal
                            // listing the gap months. Operator can
                            // override (paper book is truth).
                            val gapNotices = numbers.mapNotNull { rd ->
                                val after = draft[rd.id] ?: return@mapNotNull null
                                if (after.count <= 1) return@mapNotNull null
                                if (!changes.containsKey(rd.id)) return@mapNotNull null
                                val skipped = after.skipsMonthsAfterLastPaid()
                                if (skipped.isEmpty()) null
                                else rd.number to skipped
                            }
                            if (gapNotices.isNotEmpty()) {
                                skipGapMessage = buildSkipGapMessage(gapNotices)
                                pendingSkipGap = changes
                            } else {
                                onSave(changes)
                            }
                        },
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        val label = if (changedCount > 0) "Save $changedCount change${if (changedCount == 1) "" else "s"}" else "Save"
                        Text(label, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    pickerTarget?.let { target ->
        val current = draft[target.rowId] ?: return@let
        val existing = current.months
        val initialSelection = existing.getOrNull(target.chipIndex) ?: today
        val disabled = existing
            .withIndex()
            .filter { it.index != target.chipIndex }
            .map { it.value }
            .toSet()
        MonthPickerDialog(
            initialSelection = initialSelection,
            disabledMonths = disabled,
            onDismiss = { pickerTarget = null },
            onPick = { picked ->
                draft[target.rowId] = current.swapMonth(target.chipIndex, picked)
                pickerTarget = null
            }
        )
    }

    skipGapMessage?.let { message ->
        val pending = pendingSkipGap
        Dialog(
            onDismissRequest = {
                skipGapMessage = null
                pendingSkipGap = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .heightIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Skipping months?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            onClick = {
                                skipGapMessage = null
                                pendingSkipGap = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = {
                                val toSave = pending ?: emptyMap()
                                skipGapMessage = null
                                pendingSkipGap = null
                                onSave(toSave)
                            },
                            modifier = Modifier.weight(2f),
                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Yes, skip and save", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders the skip-gap warning body. For 1 row with a single gap month:
 *   "You're skipping Sep 2025 — is that correct? Last paid: through Aug 2025."
 * For 1 row with multiple consecutive gap months:
 *   "You're skipping Sep 2025 + Oct 2025 — is that correct? Last paid: through Aug 2025."
 * For multiple rows, lists each rd_number with its own gap range so the
 * operator can audit before confirming.
 */
private fun buildSkipGapMessage(
    notices: List<Pair<String, List<MonthYear>>>
): String {
    if (notices.size == 1) {
        val (rd, skipped) = notices.single()
        val skippedLabel = skipped.joinToString(" + ") { it.formatShort() }
        val lastPaid = skipped.first().minusOneMonth().formatShort()
        return "You're skipping $skippedLabel — is that correct? " +
            "RD #$rd last paid: through $lastPaid."
    }
    return buildString {
        append("Multiple rows skip months:\n")
        for ((rd, skipped) in notices) {
            val skippedLabel = skipped.joinToString(" + ") { it.formatShort() }
            val lastPaid = skipped.first().minusOneMonth().formatShort()
            append("\nRD #$rd: skips $skippedLabel (last paid: $lastPaid)")
        }
    }
}

private data class PickerTarget(val rowId: Long, val chipIndex: Int)

@Composable
private fun DialogHeader(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(WarningAmber.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.EditCalendar,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun DefaulterRow(
    number: String,
    draftState: DefaulterRowDraft,
    today: MonthYear,
    onCountChange: (Int) -> Unit,
    onShiftEarlier: () -> Unit,
    onShiftLater: () -> Unit,
    onLongPressChip: (Int) -> Unit
) {
    val isDefaulter = draftState.count > 1
    val rowBg by animateColorAsState(
        targetValue = if (isDefaulter) PrimaryOrange.copy(alpha = 0.08f) else RowBackground,
        animationSpec = tween(220),
        label = "rowBg"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = number,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.weight(1f)
            )

            MonthStepper(
                months = draftState.count,
                onMonthsChange = onCountChange
            )
        }

        // "Last paid: through {month}" banner is gated on isDefaulter
        // because the auto-suggest + skip-gap warning it documents are
        // only actionable when the operator is making a multi-month
        // decision. For count=1 rows the banner is just noise.
        if (isDefaulter) {
            draftState.accountLastPaidThrough?.let { lastPaid ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Last paid: through ${lastPaid.formatShort()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            MonthChipStrip(
                months = draftState.months,
                today = today,
                onShiftEarlier = onShiftEarlier,
                onShiftLater = onShiftLater,
                onLongPressChip = onLongPressChip
            )
        }
    }
}

@Composable
private fun MonthChipStrip(
    months: List<MonthYear>,
    today: MonthYear,
    onShiftEarlier: () -> Unit,
    onShiftLater: () -> Unit,
    onLongPressChip: (Int) -> Unit
) {
    val canShiftLater = months.isNotEmpty()
    val scrollState = rememberScrollState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        ChipShiftButton(
            icon = Icons.Default.ChevronLeft,
            enabled = true,
            onClick = onShiftEarlier
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            months.forEachIndexed { index, month ->
                MonthChip(
                    label = month.formatShort(),
                    onLongPress = { onLongPressChip(index) }
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        ChipShiftButton(
            icon = Icons.Default.ChevronRight,
            enabled = canShiftLater,
            onClick = onShiftLater
        )
    }
}

@Composable
private fun MonthChip(
    label: String,
    onLongPress: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .background(
                color = WarningAmber.copy(alpha = 0.18f),
                shape = RoundedCornerShape(10.dp)
            )
            .pointerInput(label) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
            }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                color = WarningAmber
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun ChipShiftButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(
                color = if (enabled) Color.White else DisabledBackground,
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) PrimaryOrange else DisabledContent,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun MonthStepper(
    months: Int,
    onMonthsChange: (Int) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val canDecrease = months > RdNumber.MONTHS_MIN
    val canIncrease = months < RdNumber.MONTHS_MAX

    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton(
            icon = Icons.Default.Remove,
            enabled = canDecrease,
            onTick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onMonthsChange((months - 1).coerceAtLeast(RdNumber.MONTHS_MIN))
            }
        )

        Box(
            modifier = Modifier.width(56.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = months,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInVertically { -it } + fadeIn()) togetherWith
                            (slideOutVertically { it } + fadeOut())
                    } else {
                        (slideInVertically { it } + fadeIn()) togetherWith
                            (slideOutVertically { -it } + fadeOut())
                    }
                },
                label = "monthValue"
            ) { value ->
                Text(
                    text = "$value mo",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (value > 1) PrimaryOrange else TextSecondary,
                        fontSize = 15.sp
                    )
                )
            }
        }

        StepperButton(
            icon = Icons.Default.Add,
            enabled = canIncrease,
            onTick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onMonthsChange((months + 1).coerceAtMost(RdNumber.MONTHS_MAX))
            }
        )
    }
}

/**
 * Round 36dp tappable button with press-scale and long-press auto-repeat.
 *
 * Ticks every 250ms initially; after 5 ticks the cadence accelerates to 80ms
 * so users can sweep to high month counts without 30 separate taps.
 */
@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onTick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "stepperScale"
    )

    LaunchedEffect(isPressed, enabled) {
        if (isPressed && enabled) {
            delay(450)
            var ticks = 0
            while (isPressed && enabled) {
                onTick()
                ticks++
                delay(if (ticks > 5) 80L else 250L)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .scale(scale)
            .background(
                color = if (enabled) Color.White else DisabledBackground,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onTick
            ),
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
