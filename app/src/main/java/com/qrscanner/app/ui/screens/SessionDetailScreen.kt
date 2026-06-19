package com.qrscanner.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.data.ScanLot
import com.qrscanner.app.data.ScanSession
import com.qrscanner.app.ui.components.DefaulterEditDialog
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.util.LotImageGenerator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp

    val lots by app.database.scanLotDao().getLotsForSession(sessionId).collectAsState(initial = emptyList())
    val totalDefaults by app.database.rdNumberDao()
        .observeDefaultCountForSession(sessionId)
        .collectAsState(initial = 0)
    var session by remember { mutableStateOf<ScanSession?>(null) }
    var expandedLotIds by rememberSaveable(stateSaver = LongSetSaver) {
        mutableStateOf(emptySet<Long>())
    }

    LaunchedEffect(sessionId) {
        session = app.database.scanSessionDao().getSessionById(sessionId)
    }

    val totalRdNumbers = session?.totalRdNumbers ?: 0
    val displayNumber = session?.displayNumber ?: 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFFFF8F0), Color.White)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.Gray.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Session #$displayNumber",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = buildString {
                            append("${lots.size} LOTs • $totalRdNumbers RD Numbers")
                            if (totalDefaults > 0) append(" • $totalDefaults default${if (totalDefaults == 1) "" else "s"}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(48.dp))
            }

            // LOTs List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(lots, key = { it.id }) { lot ->
                    LotCard(
                        lot = lot,
                        isExpanded = lot.id in expandedLotIds,
                        onToggleExpanded = {
                            expandedLotIds = if (lot.id in expandedLotIds) {
                                expandedLotIds - lot.id
                            } else {
                                expandedLotIds + lot.id
                            }
                        }
                    )
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

private val LongSetSaver: Saver<Set<Long>, List<Long>> = Saver(
    save = { it.toList() },
    restore = { it.toSet() }
)

@Composable
private fun LotCard(
    lot: ScanLot,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()
    var showEditDialog by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val rdNumberEntities by app.database.rdNumberDao()
        .getNumbersForLot(lot.id)
        .collectAsState(initial = emptyList())
    val rdNumberStrings = rdNumberEntities.map { it.number }
    val rdNumberCount = rdNumberStrings.size
    val defaulters = rdNumberEntities.filter { it.monthsPaid > 1 }
    val defaultCount = defaulters.size

    fun copyToClipboard() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val rdNumbersString = rdNumberStrings.joinToString(", ")
            clipboard.setPrimaryClip(ClipData.newPlainText("LOT ${lot.lotNumber}", rdNumbersString))
            Toast.makeText(context, "Copied $rdNumberCount numbers", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy", Toast.LENGTH_SHORT).show()
        }
    }

    fun buildShareText(): String {
        val numbers = rdNumberStrings.joinToString(", ")
        if (defaulters.isEmpty()) return numbers
        val defaulterLine = defaulters.joinToString(", ") { "${it.number} (${it.monthsPaid}m)" }
        return "$numbers\n\nDefaulters: $defaulterLine"
    }

    fun shareViaWithImage() {
        try {
            val imageFile = LotImageGenerator.generateLotImage(
                context,
                lot.lotNumber,
                rdNumberCount,
                defaultCount
            )
            val shareText = buildShareText()

            if (imageFile != null) {
                val imageUri = LotImageGenerator.getShareableUri(context, imageFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_STREAM, imageUri)
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share LOT"))
            } else {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(intent, "Share LOT"))
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share", Toast.LENGTH_SHORT).show()
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onToggleExpanded() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(PrimaryOrange.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lot.lotNumber.toString(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = PrimaryOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "LOT ${lot.lotNumber}",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = buildString {
                                append("$rdNumberCount RD Numbers")
                                if (defaultCount > 0) append(" • $defaultCount default${if (defaultCount == 1) "" else "s"}")
                                append(" • ${timeFormat.format(Date(lot.timestamp))}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = { copyToClipboard() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = PrimaryOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { shareViaWithImage() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = AccentMint,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { showEditDialog = true },
                        enabled = rdNumberEntities.isNotEmpty(),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditCalendar,
                            contentDescription = "Edit defaulters",
                            tint = WarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF8F0), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    rdNumberEntities.forEachIndexed { index, rd ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                text = rd.number,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            if (rd.monthsPaid > 1) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            WarningAmber.copy(alpha = 0.16f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "${rd.monthsPaid} mo",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = WarningAmber
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "As list: ${rdNumberStrings.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2
                )
            }
        }
    }

    if (showEditDialog) {
        DefaulterEditDialog(
            lotNumber = lot.lotNumber,
            numbers = rdNumberEntities,
            onDismiss = { showEditDialog = false },
            onSave = { changes ->
                showEditDialog = false
                scope.launch {
                    changes.forEach { (id, valueAndList) ->
                        val (months, monthsList) = valueAndList
                        app.database.rdNumberDao().updateMonths(id, months, monthsList)
                    }
                    if (changes.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            "Updated ${changes.size} row${if (changes.size == 1) "" else "s"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}
