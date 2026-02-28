package com.qrscanner.app.ui.screens

import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.data.ScanSession
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeLight
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.util.CsvExporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class HistoryFilter(val label: String) {
    ALL("All"), TODAY("Today"), THIS_WEEK("This Week"), THIS_MONTH("This Month")
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (Long) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()

    val completedSessions by app.database.scanSessionDao().getCompletedSessions().collectAsState(initial = emptyList())

    // Dialogs
    var sessionToDelete by remember { mutableStateOf<ScanSession?>(null) }
    var showExportDialog by remember { mutableStateOf<ScanSession?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // Search & filter
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(HistoryFilter.ALL) }

    // Multi-select
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateSetOf<Long>() }

    // Date range millis for filter chips
    val (todayStart, weekStart, monthStart) = remember {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val today = cal.timeInMillis
        val week = today - (6L * 24 * 60 * 60 * 1000)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val month = cal.timeInMillis
        Triple(today, week, month)
    }

    val filteredSessions = remember(completedSessions, searchQuery, activeFilter) {
        completedSessions.filter { session ->
            val matchesSearch = searchQuery.isBlank() ||
                session.displayNumber.toString().contains(searchQuery) ||
                session.totalRdNumbers.toString().contains(searchQuery) ||
                session.totalLots.toString().contains(searchQuery)
            val matchesFilter = when (activeFilter) {
                HistoryFilter.ALL -> true
                HistoryFilter.TODAY -> session.startTime >= todayStart
                HistoryFilter.THIS_WEEK -> session.startTime >= weekStart
                HistoryFilter.THIS_MONTH -> session.startTime >= monthStart
            }
            matchesSearch && matchesFilter
        }
    }

    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayFormatter = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color(0xFFFFF8F0), Color.White, Color(0xFFFFF8F0)))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── Top bar ──────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(Brush.linearGradient(listOf(PrimaryOrange, PrimaryOrangeLight)))
                    .padding(16.dp)
                    .padding(bottom = 8.dp)
            ) {
                if (isSelectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            isSelectionMode = false
                            selectedIds.clear()
                        }) {
                            Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold, color = Color.White
                            )
                        )
                        TextButton(onClick = {
                            if (selectedIds.size == filteredSessions.size) {
                                selectedIds.clear()
                                isSelectionMode = false
                            } else {
                                selectedIds.addAll(filteredSessions.map { it.id })
                            }
                        }) {
                            Text(
                                text = if (selectedIds.size == filteredSessions.size) "Deselect All" else "Select All",
                                color = Color.White, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Session History",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color.White)
                            )
                            Text(
                                "${completedSessions.size} sessions",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                        if (completedSessions.isNotEmpty()) {
                            IconButton(
                                onClick = { showClearAllDialog = true },
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))
                            ) {
                                Icon(Icons.Default.DeleteSweep, "Clear All", tint = Color.White)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(44.dp))
                        }
                    }
                }
            }

            // ── Search bar + filter chips (hidden in selection mode) ──────────
            AnimatedVisibility(
                visible = completedSessions.isNotEmpty() && !isSelectionMode,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it }
            ) {
                Column {
                    Spacer(modifier = Modifier.height(14.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search by session # or count...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                        },
                        trailingIcon = {
                            AnimatedVisibility(
                                visible = searchQuery.isNotBlank(),
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut()
                            ) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, "Clear search", tint = TextSecondary)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(50.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .shadow(4.dp, RoundedCornerShape(50.dp))
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HistoryFilter.entries.forEach { filter ->
                            val isSelected = activeFilter == filter
                            val chipScale by animateFloatAsState(
                                targetValue = if (isSelected) 1.05f else 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                label = "chip_scale"
                            )
                            FilterChip(
                                selected = isSelected,
                                onClick = { activeFilter = filter },
                                label = {
                                    Text(
                                        filter.label,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                modifier = Modifier.scale(chipScale),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PrimaryOrange,
                                    selectedLabelColor = Color.White,
                                    containerColor = Color.White,
                                    labelColor = TextSecondary
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            when {
                completedSessions.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(100.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.05f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.History, null, modifier = Modifier.size(50.dp), tint = TextSecondary.copy(alpha = 0.3f))
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("No Sessions Yet", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Start scanning RD books to see your history here", style = MaterialTheme.typography.bodyMedium, color = TextSecondary.copy(alpha = 0.7f))
                        }
                    }
                }

                filteredSessions.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.05f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Search, null, modifier = Modifier.size(40.dp), tint = TextSecondary.copy(alpha = 0.3f))
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No results found", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = TextSecondary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Try clearing the search or\nchanging the filter",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val todayStr = dateFormatter.format(Date())
                        val yesterdayStr = dateFormatter.format(Date(System.currentTimeMillis() - 86400000))

                        val grouped = filteredSessions.groupBy { session ->
                            dateFormatter.format(Date(session.startTime))
                        }

                        grouped.forEach { (date, sessionsForDate) ->
                            item(key = "header_$date") {
                                val displayDate = when (date) {
                                    todayStr -> "Today"
                                    yesterdayStr -> "Yesterday"
                                    else -> try {
                                        val parsed = dateFormatter.parse(date)
                                        if (parsed != null) displayFormatter.format(parsed) else date
                                    } catch (_: Exception) { date }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.height(2.dp).weight(1f).background(Brush.horizontalGradient(listOf(Color.Transparent, PrimaryOrange.copy(alpha = 0.3f)))))
                                    Text(displayDate, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = PrimaryOrange, modifier = Modifier.padding(horizontal = 16.dp))
                                    Box(modifier = Modifier.height(2.dp).weight(1f).background(Brush.horizontalGradient(listOf(PrimaryOrange.copy(alpha = 0.3f), Color.Transparent))))
                                }
                            }

                            items(sessionsForDate, key = { it.id }) { session ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart && !isSelectionMode) {
                                            sessionToDelete = session
                                        }
                                        false  // always spring back; dialog handles the actual delete
                                    }
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    backgroundContent = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(AccentCoral),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.padding(end = 28.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("Delete", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                ) {
                                    SessionCard(
                                        session = session,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = session.id in selectedIds,
                                        onClick = {
                                            if (isSelectionMode) {
                                                if (session.id in selectedIds) selectedIds.remove(session.id)
                                                else selectedIds.add(session.id)
                                                if (selectedIds.isEmpty()) isSelectionMode = false
                                            } else {
                                                onNavigateToSession(session.id)
                                            }
                                        },
                                        onLongPress = {
                                            if (!isSelectionMode) {
                                                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                                                isSelectionMode = true
                                                selectedIds.add(session.id)
                                            }
                                        },
                                        onExport = { showExportDialog = session },
                                        onDelete = { sessionToDelete = session }
                                    )
                                }
                            }
                        }

                        item(key = "bottom_spacer") {
                            Spacer(modifier = Modifier.height(16.dp).navigationBarsPadding())
                        }
                    }
                }
            }
        }

        // ── Multi-select bottom action bar ────────────────────────────────────
        AnimatedVisibility(
            visible = isSelectionMode && selectedIds.isNotEmpty(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 16.dp,
                color = Color.White
            ) {
                Button(
                    onClick = { showDeleteSelectedDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCoral),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .navigationBarsPadding()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    val count = selectedIds.size
                    Text("Delete $count session${if (count > 1) "s" else ""}", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ── Export dialog ─────────────────────────────────────────────────────
        showExportDialog?.let { session ->
            Dialog(onDismissRequest = { showExportDialog = null }) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color.White, tonalElevation = 8.dp) {
                    Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Export Session #${session.displayNumber}",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Choose export format", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val lots = app.database.scanLotDao().getLotsForSessionSync(session.id)
                                            val rdNumbersPerLot = lots.map { lot ->
                                                app.database.rdNumberDao().getNumbersForLotSync(lot.id).map { it.number }
                                            }
                                            val file = CsvExporter.exportSessionToCsv(context, lots, rdNumbersPerLot, session.displayNumber)
                                            if (file != null) {
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/csv"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share CSV"))
                                            } else {
                                                Toast.makeText(context, "Failed to export", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    showExportDialog = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentMint),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.TableChart, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("CSV")
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val lots = app.database.scanLotDao().getLotsForSessionSync(session.id)
                                            val rdNumbersPerLot = lots.map { lot ->
                                                app.database.rdNumberDao().getNumbersForLotSync(lot.id).map { it.number }
                                            }
                                            val file = CsvExporter.exportSessionToTxt(context, lots, rdNumbersPerLot, session.displayNumber)
                                            if (file != null) {
                                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share TXT"))
                                            } else {
                                                Toast.makeText(context, "Failed to export", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    showExportDialog = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Description, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("TXT")
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(onClick = { showExportDialog = null }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    }
                }
            }
        }

        // ── Single delete dialog ──────────────────────────────────────────────
        sessionToDelete?.let { session ->
            AlertDialog(
                onDismissRequest = { sessionToDelete = null },
                title = { Text("Delete Session #${session.displayNumber}?", fontWeight = FontWeight.Bold) },
                text = { Text("This will permanently delete ${session.totalLots} LOTs and ${session.totalRdNumbers} RD numbers.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                app.database.scanLotDao().deleteLotsForSession(session.id)
                                app.database.scanSessionDao().delete(session)
                                Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                        sessionToDelete = null
                    }) {
                        Text("Delete", color = AccentCoral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") }
                }
            )
        }

        // ── Bulk delete dialog ────────────────────────────────────────────────
        if (showDeleteSelectedDialog) {
            val count = selectedIds.size
            AlertDialog(
                onDismissRequest = { showDeleteSelectedDialog = false },
                title = { Text("Delete $count Session${if (count > 1) "s" else ""}?", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "This will permanently delete $count session${if (count > 1) "s" else ""} and all their LOTs and RD numbers.\n\nThis action cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val toDelete = selectedIds.toSet()
                        scope.launch {
                            try {
                                toDelete.forEach { id ->
                                    app.database.scanLotDao().deleteLotsForSession(id)
                                    app.database.scanSessionDao().deleteById(id)
                                }
                                Toast.makeText(context, "Deleted $count session${if (count > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                        selectedIds.clear()
                        isSelectionMode = false
                        showDeleteSelectedDialog = false
                    }) {
                        Text("Delete All", color = AccentCoral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteSelectedDialog = false }) { Text("Cancel") }
                }
            )
        }

        // ── Clear all dialog ──────────────────────────────────────────────────
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                title = { Text("Clear All Sessions?", fontWeight = FontWeight.Bold) },
                text = {
                    val totalLots = completedSessions.sumOf { it.totalLots }
                    val totalRdNumbers = completedSessions.sumOf { it.totalRdNumbers }
                    Text("This will permanently delete all ${completedSessions.size} sessions, $totalLots LOTs, and $totalRdNumbers RD numbers.\n\nThis action cannot be undone.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                completedSessions.forEach { session ->
                                    app.database.scanLotDao().deleteLotsForSession(session.id)
                                    app.database.scanSessionDao().delete(session)
                                }
                                Toast.makeText(context, "All sessions cleared", Toast.LENGTH_SHORT).show()
                            } catch (_: Exception) {
                                Toast.makeText(context, "Failed to clear sessions", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showClearAllDialog = false
                    }) {
                        Text("Clear All", color = AccentCoral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: ScanSession,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val startTime = timeFormat.format(Date(session.startTime))
    val endTime = session.endTime?.let { timeFormat.format(Date(it)) } ?: ""

    val borderColor by animateColorAsState(
        targetValue = if (isSelected) PrimaryOrange else Color.Transparent,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "border_color"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) PrimaryOrange.copy(alpha = 0.04f) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 0.dp else 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(PrimaryOrange.copy(alpha = 0.1f), PrimaryOrangeLight.copy(alpha = 0.1f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "#${session.displayNumber}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = PrimaryOrange)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text("Session #${session.displayNumber}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("$startTime → $endTime", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                // Animated: arrow ↔ checkbox
                AnimatedContent(
                    targetState = isSelectionMode,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label = "card_trailing_icon"
                ) { inSelectionMode ->
                    if (inSelectionMode) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Not selected",
                            tint = if (isSelected) PrimaryOrange else TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "View",
                            tint = PrimaryOrange.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Stats row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFFF8F0))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(session.totalLots.toString(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = AccentMint))
                    Text("LOTs", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Box(modifier = Modifier.width(1.dp).height(36.dp).background(Color.Gray.copy(alpha = 0.15f)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(session.totalRdNumbers.toString(), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = PrimaryOrange))
                    Text("RD Numbers", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }

            // Action buttons — slide away in selection mode
            AnimatedVisibility(
                visible = !isSelectionMode,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it }
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onExport,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentMint.copy(alpha = 0.15f),
                                contentColor = AccentMint
                            ),
                            shape = RoundedCornerShape(10.dp),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onDelete,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentCoral.copy(alpha = 0.1f),
                                contentColor = AccentCoral
                            ),
                            shape = RoundedCornerShape(10.dp),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
