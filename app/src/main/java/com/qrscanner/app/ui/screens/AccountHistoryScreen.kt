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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.data.AccountHistoryRow
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.ui.components.GradientTopBar
import com.qrscanner.app.ui.components.GradientTopBarHeaderRow
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeDark
import com.qrscanner.app.ui.theme.SuccessGreen
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.TextTertiary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.util.MonthYear
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AccountHistoryScreen(
    rdNumber: String,
    onNavigateBack: () -> Unit,
    onOpenSession: (sessionId: Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as QRScannerApp

    val account by remember(rdNumber) {
        app.database.rdAccountDao().observeByRdNumber(rdNumber)
    }.collectAsStateWithLifecycle(initialValue = null)

    val history by remember(rdNumber) {
        app.database.rdNumberDao().observeHistoryForRdNumber(rdNumber)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val totals = remember(history) { computeTotals(history) }
    val timeline = remember(history) { computeTimeline(history) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        GradientTopBar {
            GradientTopBarHeaderRow(
                title = "Payment history",
                subtitle = account?.name ?: rdNumber,
                onNavigateBack = onNavigateBack
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HeaderCard(rdNumber = rdNumber, account = account, totals = totals)
            }

            item {
                TimelineCard(timeline = timeline)
            }

            if (history.isEmpty()) {
                item { EmptyHistoryState() }
            } else {
                item {
                    Text(
                        text = "Scan history",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                    )
                }
                items(items = history, key = { it.rdNumberId }) { row ->
                    HistoryRow(row = row, onClick = { onOpenSession(row.sessionId) })
                }
            }
        }
    }
}

@Composable
private fun HeaderCard(
    rdNumber: String,
    account: RdAccount?,
    totals: HistoryTotals
) {
    // Design-system invariant: HeaderCard, TimelineCard, and HistoryRow
    // all share containerColor = SurfaceWhite + 1.dp elevation. Tinting
    // any one of them breaks the "three separate floating cards on the
    // background" affordance the operator relies on to scan the screen.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account?.name ?: "Account",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (account?.monthlyAmount != null) {
                    Text(
                        text = "\u20B9${account.monthlyAmount}/mo",
                        color = PrimaryOrangeDark,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = rdNumber,
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricPill(
                    label = "Scans",
                    value = totals.scanCount.toString(),
                    accent = AccentMint,
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "Months paid",
                    value = totals.totalMonthsPaid.toString(),
                    accent = SuccessGreen,
                    modifier = Modifier.weight(1f)
                )
                MetricPill(
                    label = "Paid till",
                    value = account?.lastPaidThrough?.let { formatPaidTill(it) } ?: "–",
                    accent = PrimaryOrange,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            color = TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HistoryRow(
    row: AccountHistoryRow,
    onClick: () -> Unit
) {
    val anchor = remember(row.lotTimestamp) { MonthYear.fromEpochMillis(row.lotTimestamp) }
    val months = remember(row.monthsList, row.monthsPaid, anchor) {
        MonthYear.resolveOrAuto(row.monthsList, row.monthsPaid, anchor)
    }
    val isDefaulterEdit = row.monthsPaid > 1

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = PrimaryOrange.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "#${row.sessionNumber}",
                        color = PrimaryOrangeDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LOT ${row.lotNumber}",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isDefaulterEdit) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = WarningAmber.copy(alpha = 0.18f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WarningAmber,
                                    contentDescription = null,
                                    tint = WarningAmber,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "${row.monthsPaid}m",
                                    color = WarningAmber,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = months.joinToString(", ") { it.formatShort() },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatSessionSubtitle(row),
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open session",
                tint = TextTertiary
            )
        }
    }
}

@Composable
private fun EmptyHistoryState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No scan history yet",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Scan this account in a session to build history.",
            color = TextSecondary,
            fontSize = 12.sp
        )
    }
}

private data class HistoryTotals(
    val scanCount: Int,
    val totalMonthsPaid: Int
)

private fun computeTotals(rows: List<AccountHistoryRow>): HistoryTotals =
    HistoryTotals(
        scanCount = rows.size,
        totalMonthsPaid = rows.sumOf { it.monthsPaid }
    )

private val sessionDateFormat = ThreadLocal.withInitial {
    SimpleDateFormat("d MMM yyyy", Locale.getDefault())
}

private fun formatSessionSubtitle(row: AccountHistoryRow): String {
    val when_ = row.sessionEnd ?: row.sessionStart
    val date = sessionDateFormat.get()!!.format(Date(when_))
    val op = row.operatorName?.takeIf { it.isNotBlank() }
    return if (op != null) "$date · $op" else date
}

private fun formatPaidTill(token: String): String =
    MonthYear.parseToken(token)?.formatShort() ?: token

private const val TIMELINE_MONTHS = 12

private data class TimelineEntry(
    val month: MonthYear,
    val paid: Boolean,
    /**
     * True iff this month was ONLY ever credited via a defaulter-cycle
     * scan (monthsPaid > 1). If the same month was credited in a
     * correct-month scan (monthsPaid == 1) at any point, this stays
     * false — the green "paid correctly" wins over amber "caught up
     * late" for legend clarity. Meaningless when [paid] is false.
     */
    val paidAsDefaulter: Boolean,
    val isCurrent: Boolean
)

private data class TimelineSummary(
    val entries: List<TimelineEntry>,
    val earlierPaidCount: Int
)

private fun computeTimeline(rows: List<AccountHistoryRow>): TimelineSummary {
    val now = MonthYear.current()
    var cursor = now
    repeat(TIMELINE_MONTHS - 1) { cursor = cursor.minusOneMonth() }
    val windowStart = cursor
    // monthToken -> paidAsDefaulter. Presence in the map = paid.
    // Value invariant: false wins over true. A single correct-month
    // scan (monthsPaid == 1) pins the month to false permanently,
    // even if a later defaulter-cycle scan touches the same month.
    // Mirrors the TimelineEntry.paidAsDefaulter KDoc contract.
    val paidByMonth = HashMap<String, Boolean>()
    for (row in rows) {
        val anchor = MonthYear.fromEpochMillis(row.lotTimestamp)
        val fromDefaulterCycle = row.monthsPaid > 1
        for (m in MonthYear.resolveOrAuto(row.monthsList, row.monthsPaid, anchor)) {
            val token = m.toToken()
            val existing = paidByMonth[token]
            paidByMonth[token] = if (existing == false) false else fromDefaulterCycle
        }
    }
    val entries = MonthYear.range(windowStart, now).map { m ->
        val token = m.toToken()
        val paidAsDef = paidByMonth[token]
        TimelineEntry(
            month = m,
            paid = paidAsDef != null,
            paidAsDefaulter = paidAsDef == true,
            isCurrent = m == now
        )
    }
    val earlierPaidCount = paidByMonth.keys.count { token ->
        val parsed = MonthYear.parseToken(token) ?: return@count false
        parsed < windowStart
    }
    return TimelineSummary(entries = entries, earlierPaidCount = earlierPaidCount)
}

@Composable
private fun TimelineCard(timeline: TimelineSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Last 12 months",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (timeline.earlierPaidCount > 0) {
                    Text(
                        text = "+${timeline.earlierPaidCount} earlier",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Weight-based row so the 12 cells scale from narrow Pixel-4a
            // to wide foldable without horizontal scroll. Dot color
            // encodes state (green = paid on time, amber = paid as
            // defaulter, muted = unpaid); current month gets a
            // PrimaryOrange ring anchor. Cell dot colors MUST match
            // the legend order below or the operator will misread the
            // timeline (P3 cross-widget invariant).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                timeline.entries.forEach { entry ->
                    TimelineCell(
                        entry = entry,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // Three-legend rule: all three chips render unconditionally
            // even when the current 12-month window contains only one
            // color of dot. Operator learning-curve: consistent legend
            // means the operator learns "green vs amber vs empty" once
            // and doesn't have to re-derive the mapping when the
            // window changes shape.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LegendDot(color = SuccessGreen, label = "Paid on time")
                LegendDot(color = WarningAmber, label = "Paid as defaulter")
                LegendDot(color = TextTertiary.copy(alpha = 0.35f), label = "Not paid")
            }
        }
    }
}

@Composable
private fun TimelineCell(entry: TimelineEntry, modifier: Modifier = Modifier) {
    val dotColor = when {
        !entry.paid -> TextTertiary.copy(alpha = 0.35f)
        entry.paidAsDefaulter -> WarningAmber
        else -> SuccessGreen
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            if (entry.isCurrent) {
                // Concentric ring around current-month dot. Uses PrimaryOrange
                // at 18% alpha for a clear anchor without overwhelming the
                // paid/unpaid dots on either side.
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = PrimaryOrange.copy(alpha = 0.18f),
                            shape = CircleShape
                        )
                )
            }
            Box(
                modifier = Modifier
                    .size(if (entry.paid) 14.dp else 10.dp)
                    .background(color = dotColor, shape = CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.month.formatShort().take(3),
            color = if (entry.isCurrent) PrimaryOrangeDark else TextTertiary,
            fontSize = 9.sp,
            fontWeight = if (entry.isCurrent) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
