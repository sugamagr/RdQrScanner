package com.qrscanner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.DisabledBackground
import com.qrscanner.app.ui.theme.DisabledContent
import com.qrscanner.app.util.MonthYear

private const val PICKER_YEARS_BACK = 10
private const val PICKER_YEARS_FORWARD = 2

/**
 * Pop-up dialog used to swap a single month chip in the defaulter editor.
 *
 * Single-tap commit: tapping any enabled month immediately invokes [onPick]
 * and dismisses. The dialog is light-touch (dismiss-on-outside-tap is
 * intentionally kept on, since the worst case is cancelling a single chip
 * swap — no in-progress edits at stake).
 *
 * Year range is [MonthYear.current()] minus [PICKER_YEARS_BACK] up to the
 * current year, extended downward if [initialSelection] sits earlier than
 * that window (so a chip the user previously set to e.g. 2010 stays
 * reachable when its picker reopens).
 */
@Composable
fun MonthPickerDialog(
    initialSelection: MonthYear,
    disabledMonths: Set<MonthYear>,
    onDismiss: () -> Unit,
    onPick: (MonthYear) -> Unit
) {
    val today = remember { MonthYear.current() }
    val minYear = remember(initialSelection, today) {
        minOf(today.year - PICKER_YEARS_BACK, initialSelection.year)
    }
    val maxYear = remember(initialSelection, today) {
        // Future months allowed (prepayment scenario). Extend the year
        // selector ceiling to today + 2 years, with a floor of the
        // currently-selected month's year so a stored future selection
        // remains addressable. Capped at MonthYear.MAX_YEAR (2099) to
        // stay inside the YYYY-MM token format range used by both apps.
        maxOf(today.year + PICKER_YEARS_FORWARD, initialSelection.year)
            .coerceAtMost(MonthYear.MAX_YEAR)
    }
    val years = remember(minYear, maxYear) { (minYear..maxYear).toList() }
    var selectedYear by remember { mutableIntStateOf(initialSelection.year) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            tonalElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                PickerHeader()
                Spacer(modifier = Modifier.height(16.dp))
                YearRow(
                    years = years,
                    selectedYear = selectedYear,
                    onYearSelected = { selectedYear = it }
                )
                Spacer(modifier = Modifier.height(16.dp))
                MonthGrid(
                    year = selectedYear,
                    today = today,
                    initialSelection = initialSelection,
                    disabledMonths = disabledMonths,
                    onPick = { month ->
                        val picked = MonthYear(selectedYear, month)
                        onPick(picked)
                    }
                )
            }
        }
    }
}

@Composable
private fun PickerHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(PrimaryOrange.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = PrimaryOrange,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = "Pick month",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Tap to swap",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun YearRow(
    years: List<Int>,
    selectedYear: Int,
    onYearSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedYear, years) {
        val idx = years.indexOf(selectedYear).coerceAtLeast(0)
        listState.scrollToItem(idx)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(years, key = { it }) { year ->
            val isSelected = year == selectedYear
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) PrimaryOrange else DisabledBackground,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onYearSelected(year) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else TextSecondary
                    )
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    year: Int,
    today: MonthYear,
    initialSelection: MonthYear,
    disabledMonths: Set<MonthYear>,
    onPick: (Int) -> Unit
) {
    val months = (1..12).toList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        months.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { month ->
                    val candidate = MonthYear(year, month)
                    val isDuplicate = candidate in disabledMonths
                    val isSelected = candidate == initialSelection
                    // Future months allowed (prepayment scenario).
                    val enabled = !isDuplicate
                    MonthCell(
                        label = candidate.formatShort().substringBefore(' '),
                        enabled = enabled,
                        isSelected = isSelected,
                        onClick = { if (enabled) onPick(month) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    label: String,
    enabled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = when {
        isSelected -> PrimaryOrange
        !enabled -> DisabledBackground
        else -> Color.White
    }
    val textColor = when {
        isSelected -> Color.White
        !enabled -> DisabledContent
        else -> Color(0xFF1A1A2E)
    }
    Box(
        modifier = modifier
            .height(44.dp)
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = textColor
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}
