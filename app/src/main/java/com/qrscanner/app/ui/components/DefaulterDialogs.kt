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
import com.qrscanner.app.util.MonthYear
import kotlinx.coroutines.delay

/**
 * Per-row edit state captured by [DefaulterEditDialog].
 *
 * Invariant: `months.size == count`. Mutations go through [withCount] /
 * [withMonths] / [shiftWindow] / [swapMonth] so the invariant is impossible
 * to violate from the caller side.
 */
data class DefaulterRowDraft(
    val count: Int,
    val months: List<MonthYear>
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
        val newest = months.first()
        if (forward && newest >= today) return this
        val shifted = months.map { if (forward) it.plusOneMonth() else it.minusOneMonth() }
        return copy(months = shifted)
    }

    fun swapMonth(index: Int, picked: MonthYear): DefaulterRowDraft {
        if (index !in months.indices) return this
        if (picked in months) return this
        val updated = months.toMutableList().apply { this[index] = picked }
        return copy(months = updated)
    }

    fun encodeOrNull(): String? = if (count <= 1) null else MonthYear.encodeList(months)

    companion object {
        fun fromRow(row: RdNumber, today: MonthYear = MonthYear.current()): DefaulterRowDraft {
            val resolved = MonthYear.resolveOrAuto(row.monthsList, row.monthsPaid, today)
            return DefaulterRowDraft(row.monthsPaid, resolved)
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
                        containerColor = Color(0xFFF1F3F5),
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
    onDismiss: () -> Unit,
    onSave: (changes: Map<Long, Pair<Int, String?>>) -> Unit
) {
    val today = remember { MonthYear.current() }
    val initial = remember(numbers) {
        numbers.associate { it.id to DefaulterRowDraft.fromRow(it, today) }
    }
    val draft = remember(initial) {
        mutableStateMapOf<Long, DefaulterRowDraft>().apply { putAll(initial) }
    }

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
                        "$defaulterCount marked • ${numbers.size} total · long-press a chip to swap"
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
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))
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
                            onSave(changes)
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
        targetValue = if (isDefaulter) PrimaryOrange.copy(alpha = 0.08f) else Color(0xFFF7F8FA),
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

        if (isDefaulter) {
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
    val canShiftLater = months.firstOrNull()?.let { it < today } ?: false
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
            )
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
                color = if (enabled) Color.White else Color(0xFFF1F3F5),
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) PrimaryOrange else Color(0xFFCBD0D6),
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
                color = if (enabled) Color.White else Color(0xFFF1F3F5),
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
            tint = if (enabled) PrimaryOrange else Color(0xFFCBD0D6),
            modifier = Modifier.size(18.dp)
        )
    }
}
