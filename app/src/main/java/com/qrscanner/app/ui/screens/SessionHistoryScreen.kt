package com.qrscanner.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import java.util.Date
import java.util.Locale

@Composable
fun SessionHistoryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (Long) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()
    
    val completedSessions by app.database.scanSessionDao().getCompletedSessions().collectAsState(initial = emptyList())
    
    var sessionToDelete by remember { mutableStateOf<ScanSession?>(null) }
    var showExportDialog by remember { mutableStateOf<ScanSession?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayFormatter = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF8F0), Color.White, Color(0xFFFFF8F0))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar with gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(
                        Brush.linearGradient(listOf(PrimaryOrange, PrimaryOrangeLight))
                    )
                    .padding(16.dp)
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Session History",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "${completedSessions.size} sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    
                    // Clear All Button
                    if (completedSessions.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearAllDialog = true },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear All",
                                tint = Color.White
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(44.dp))
                    }
                }
            }
            
            if (completedSessions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(Color.Gray.copy(alpha = 0.05f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(50.dp),
                                tint = TextSecondary.copy(alpha = 0.3f)
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "No Sessions Yet",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Start scanning RD books to see your history here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val todayStr = dateFormatter.format(Date())
                    val yesterdayStr = dateFormatter.format(Date(System.currentTimeMillis() - 86400000))
                    
                    val grouped = completedSessions.groupBy { session ->
                        dateFormatter.format(Date(session.startTime))
                    }
                    
                    grouped.forEach { (date, sessionsForDate) ->
                        item(key = "header_$date") {
                            val displayDate = when (date) {
                                todayStr -> "Today"
                                yesterdayStr -> "Yesterday"
                                else -> {
                                    try {
                                        val parsedDate = dateFormatter.parse(date)
                                        if (parsedDate != null) {
                                            displayFormatter.format(parsedDate)
                                        } else {
                                            date
                                        }
                                    } catch (e: Exception) {
                                        date
                                    }
                                }
                            }
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .height(2.dp)
                                        .weight(1f)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Color.Transparent, PrimaryOrange.copy(alpha = 0.3f))
                                            )
                                        )
                                )
                                Text(
                                    text = displayDate,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = PrimaryOrange,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .height(2.dp)
                                        .weight(1f)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(PrimaryOrange.copy(alpha = 0.3f), Color.Transparent)
                                            )
                                        )
                                )
                            }
                        }
                        
                        items(sessionsForDate, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                onClick = { onNavigateToSession(session.id) },
                                onExport = { showExportDialog = session },
                                onDelete = { sessionToDelete = session }
                            )
                        }
                    }
                    
                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(16.dp).navigationBarsPadding())
                    }
                }
            }
        }
        
        // Export Dialog
        showExportDialog?.let { session ->
            Dialog(onDismissRequest = { showExportDialog = null }) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Export Session #${session.displayNumber}",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Choose export format",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val lots = app.database.scanLotDao().getLotsForSessionSync(session.id)
                                            val file = CsvExporter.exportSessionToCsv(context, lots, session.displayNumber)
                                            if (file != null) {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.provider",
                                                    file
                                                )
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/csv"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share CSV"))
                                            } else {
                                                Toast.makeText(context, "Failed to export", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    showExportDialog = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentMint),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("CSV")
                            }
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val lots = app.database.scanLotDao().getLotsForSessionSync(session.id)
                                            val file = CsvExporter.exportSessionToTxt(context, lots, session.displayNumber)
                                            if (file != null) {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.provider",
                                                    file
                                                )
                                                val intent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share TXT"))
                                            } else {
                                                Toast.makeText(context, "Failed to export", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    showExportDialog = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
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
        
        // Delete Dialog
        sessionToDelete?.let { session ->
            AlertDialog(
                onDismissRequest = { sessionToDelete = null },
                title = { 
                    Text(
                        "Delete Session #${session.displayNumber}?",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                text = { 
                    Text("This will permanently delete ${session.totalLots} LOTs and ${session.totalRdNumbers} RD numbers.") 
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                app.database.scanLotDao().deleteLotsForSession(session.id)
                                app.database.scanSessionDao().delete(session)
                                Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                        sessionToDelete = null
                    }) {
                        Text("Delete", color = AccentCoral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sessionToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        // Clear All Dialog
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                title = { 
                    Text(
                        "Clear All Sessions?",
                        fontWeight = FontWeight.Bold
                    ) 
                },
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
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to clear sessions", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showClearAllDialog = false
                    }) {
                        Text("Clear All", color = AccentCoral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun SessionCard(
    session: ScanSession,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val startTime = timeFormat.format(Date(session.startTime))
    val endTime = session.endTime?.let { timeFormat.format(Date(it)) } ?: ""
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(listOf(PrimaryOrange.copy(alpha = 0.1f), PrimaryOrangeLight.copy(alpha = 0.1f)))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "#${session.displayNumber}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = PrimaryOrange
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(14.dp))
                    
                    Column {
                        Text(
                            text = "Session #${session.displayNumber}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$startTime → $endTime",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "View",
                    tint = PrimaryOrange.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            
            // Stats Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFFF8F0))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = session.totalLots.toString(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = AccentMint
                        )
                    )
                    Text(
                        text = "LOTs",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(Color.Gray.copy(alpha = 0.15f))
                )
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = session.totalRdNumbers.toString(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = PrimaryOrange
                        )
                    )
                    Text(
                        text = "RD Numbers",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
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
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
