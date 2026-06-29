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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.qrscanner.app.data.ScanLot
import com.qrscanner.app.data.ScanSession
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import com.qrscanner.app.ui.components.GradientTopBar
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeLight
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.ui.theme.GradientPeach
import com.qrscanner.app.util.LotImageGenerator
import com.qrscanner.app.util.MonthYear
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()

    val lots by app.database.scanLotDao().getLotsForSession(sessionId).collectAsStateWithLifecycle(initialValue = emptyList())
    var hasReceivedFirstEmit by remember { mutableStateOf(false) }
    LaunchedEffect(lots) {
        if (!hasReceivedFirstEmit) hasReceivedFirstEmit = true
    }

    // Editor state hoisted to SessionDetailScreen (parent of every
    // LotCard) for two reasons:
    //   1. LotReviewScreen is a full-screen Surface — rendering it
    //      inside a LazyColumn item would scope-clip it to the row.
    //   2. The editor is a session-level concern, not a card-level
    //      one — only one LOT can be edited at a time across the
    //      whole session.
    //
    // Saveable state survives process death so an operator who got
    // interrupted mid-edit (incoming call, OS kill) comes back to
    // their in-flight changes:
    //   - editingLotId: which LOT is open. Restored on rehydrate,
    //     LaunchedEffect rebuilds baseRows from it.
    //   - editorEdits: the operator's per-row month deltas, keyed by
    //     rd_numbers.id (globally unique so cross-LOT collisions
    //     can't happen). Uses the shared LotReviewEditsSaver.
    //   - editorBaseRows: TRANSIENT — never saved, rebuilt from
    //     editingLotId on every LaunchedEffect. Embedding the base
    //     rows in the Bundle would be wasteful (DB is the source of
    //     truth) and risk staleness vs. concurrent edits from other
    //     devices that landed during the kill window.
    //
    // hasNavigatedAway guards the tombstone-mid-edit race: the
    // sessionFlow observer and the LotReviewOutcome.SessionTombstoned
    // handler can BOTH fire onNavigateBack(). The flag ensures only
    // the first one runs.
    var editingLotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorBaseRows by remember { mutableStateOf<List<LotReviewRow>?>(null) }
    var editorEdits by rememberSaveable(stateSaver = LotReviewEditsSaver) {
        mutableStateOf<Map<Long, List<MonthYear>>>(emptyMap())
    }
    var hasNavigatedAway by remember { mutableStateOf(false) }

    LaunchedEffect(editingLotId, lots) {
        val targetId = editingLotId
        if (targetId == null) {
            editorBaseRows = null
        } else {
            val lot = lots.firstOrNull { it.id == targetId }
            if (lot != null) {
                editorBaseRows = null
                val built = LotReviewBuilder.build(app, lot.id, lot.timestamp)
                // Discard stale results from a rapid LOT switch. Compose
                // cancels the previous LaunchedEffect coroutine on key
                // change, but Room's suspend DAO calls are NOT
                // cooperative with cancellation — they run to completion
                // and CAN return a result for an editingLotId that has
                // since changed. Re-check before publishing to state.
                if (editingLotId == targetId) {
                    editorBaseRows = built
                }
            } else {
                // editingLotId points to a LOT that no longer exists
                // — most likely an FK cascade from a session tombstone
                // (the sessionFlow observer below will navigate away).
                // Clean up local state defensively.
                editingLotId = null
                editorEdits = emptyMap()
            }
        }
    }
    val totalDefaults by app.database.rdNumberDao()
        .observeDefaultCountForSession(sessionId)
        .collectAsStateWithLifecycle(initialValue = 0)
    // Phase 5 T5.5 (F7): observe the session as a Flow so a tombstone
    // arriving from another device (or the portal) while the user is
    // viewing this screen pops them back to History instead of stranding
    // them on ghost data. The first emission seeds the session state;
    // subsequent null emissions trigger the back navigation.
    val sessionFlow by app.database.scanSessionDao()
        .observeSessionById(sessionId)
        .collectAsStateWithLifecycle(initialValue = null)
    var session by remember { mutableStateOf<ScanSession?>(null) }
    var sessionEverLoaded by rememberSaveable { mutableStateOf(false) }
    var expandedLotIds by rememberSaveable(stateSaver = LongSetSaver) {
        mutableStateOf(emptySet<Long>())
    }

    LaunchedEffect(sessionFlow) {
        val next = sessionFlow
        if (next != null) {
            session = next
            sessionEverLoaded = true
        } else if (sessionEverLoaded && !hasNavigatedAway) {
            // Tombstone arrived after we'd already loaded — get out
            // cleanly. hasNavigatedAway guards against double-pop
            // when the persister's SessionTombstoned outcome also
            // fires onNavigateBack from a mid-edit race.
            hasNavigatedAway = true
            Toast.makeText(
                context,
                "Session deleted by another device",
                Toast.LENGTH_SHORT
            ).show()
            onNavigateBack()
        }
    }

    val totalRdNumbers = session?.totalRdNumbers ?: 0
    val displayNumber = session?.displayNumber ?: 0
    val sessionAnchor = remember(session?.endTime, session?.startTime) {
        val anchorEpoch = session?.endTime ?: session?.startTime ?: System.currentTimeMillis()
        MonthYear.fromEpochMillis(anchorEpoch)
    }
    val totalDefaulterMonths by app.database.rdNumberDao()
        .observeTotalDefaulterMonthsForSession(sessionId)
        .collectAsStateWithLifecycle(initialValue = 0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GradientPeach, Color.White, GradientPeach)
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            GradientTopBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Session #$displayNumber",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = buildString {
                                append("${lots.size} LOTs · $totalRdNumbers RD")
                                if (totalDefaults > 0) {
                                    append(" · $totalDefaults default${if (totalDefaults == 1) "" else "s"}")
                                    if (totalDefaulterMonths > 0) {
                                        append(", $totalDefaulterMonths mo")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            if (!hasReceivedFirstEmit && lots.isEmpty()) {
                LotListSkeleton()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(lots, key = { it.id }) { lot ->
                        LotCard(
                            lot = lot,
                            sessionAnchor = sessionAnchor,
                            isExpanded = lot.id in expandedLotIds,
                            onToggleExpanded = {
                                expandedLotIds = if (lot.id in expandedLotIds) {
                                    expandedLotIds - lot.id
                                } else {
                                    expandedLotIds + lot.id
                                }
                            },
                            onEditLot = { editingLotId = lot.id }
                        )
                    }

                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }

        // Full-screen editor overlay — rendered as the outer Box's last
        // child so it stacks above the LazyColumn / GradientTopBar. The
        // single LotReviewScreen instance for the whole session matches
        // the contract that exactly one LOT can be edited at a time;
        // mode = RecordedEdit + null onRescanLot blocks the OverLimit
        // dialog's destructive "Rescan LOT" CTA because committed LOTs
        // can't be safely thrown away from this surface.
        // Resolve the live ScanLot from the saveable editingLotId. The
        // ID survives process death; the ScanLot doesn't (it gets
        // restored from Room via the lots Flow). Using `firstOrNull`
        // means if the LOT was deleted in the background, activeLot
        // becomes null and the editor dismisses gracefully.
        val activeLot = editingLotId?.let { id -> lots.firstOrNull { it.id == id } }
        val baseRows = editorBaseRows
        // Memoize the merged display rows so recompositions inside the
        // editor (per-keystroke, animation ticks, etc.) don't allocate
        // a fresh List<LotReviewRow> + per-row copies on every frame.
        // Matches the RDScannerScreen remember-pattern for parity.
        val displayRows = remember(baseRows, editorEdits) {
            baseRows?.map { base ->
                val edited = editorEdits[base.rdNumber.id]
                if (edited != null) base.copy(selected = edited) else base
            }
        }
        if (activeLot != null && displayRows != null && displayRows.isNotEmpty()) {
            LotReviewScreen(
                mode = LotReviewMode.RecordedEdit,
                lotNumber = activeLot.lotNumber,
                rows = displayRows,
                onUpdateRow = { rowId, newSelected ->
                    editorEdits = editorEdits + (rowId to newSelected)
                },
                onConfirm = { edits ->
                    editingLotId = null
                    editorEdits = emptyMap()
                    scope.launch {
                        // Persister owns updateMonths + markDirty +
                        // lastPaidThrough writeback + LOCAL_DEFAULTER_EDIT
                        // insert + push enqueue + the tombstone guard
                        // that used to live inline here. Exhaustive
                        // outcome handling forces every future variant
                        // to be addressed at this call site.
                        when (val outcome = LotReviewPersister.persist(app, sessionId, edits)) {
                            is LotReviewOutcome.Saved -> {
                                Toast.makeText(
                                    context,
                                    "Updated ${outcome.editedCount} row${if (outcome.editedCount == 1) "" else "s"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            is LotReviewOutcome.NoChanges -> Unit
                            is LotReviewOutcome.SessionTombstoned -> {
                                if (!hasNavigatedAway) {
                                    hasNavigatedAway = true
                                    Toast.makeText(
                                        context,
                                        "Session was deleted — edit not saved",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onNavigateBack()
                                }
                            }
                            is LotReviewOutcome.Error -> {
                                android.util.Log.e(
                                    "SessionDetailScreen",
                                    "LotReview persist failed",
                                    outcome.cause
                                )
                                Toast.makeText(
                                    context,
                                    "Save failed — try again",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                onDiscard = {
                    editingLotId = null
                    editorEdits = emptyMap()
                }
            )
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
    sessionAnchor: MonthYear,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEditLot: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val rdNumberEntities by app.database.rdNumberDao()
        .getNumbersForLot(lot.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rdNumberStrings = rdNumberEntities.map { it.number }
    val rdNumberCount = rdNumberStrings.size
    val defaulters = rdNumberEntities.filter { it.monthsPaid > 1 }
    val defaultCount = defaulters.size
    val resolvedMonths = remember(defaulters, sessionAnchor) {
        defaulters.associate { it.id to MonthYear.resolveOrAuto(it.monthsList, it.monthsPaid, sessionAnchor) }
    }
    val totalMonthsInLot = resolvedMonths.values.sumOf { it.size }

    fun copyToClipboard() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val rdNumbersString = rdNumberStrings.joinToString(", ")
            val clip = ClipData.newPlainText("LOT ${lot.lotNumber}", rdNumbersString)
            // PII: rd_numbers are sensitive financial identifiers. On
            // Android 13+ the system shows a clipboard preview in the
            // keyboard suggestion strip and notification shade; without
            // the sensitive flag, comma-separated rd_numbers are
            // visible to bystanders and to screenshot capture tools.
            // The IS_SENSITIVE extra suppresses preview while still
            // letting the user paste into the target app.
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied $rdNumberCount numbers", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to copy", Toast.LENGTH_SHORT).show()
        }
    }

    fun buildShareText(): String {
        val numbers = rdNumberStrings.joinToString(", ")
        if (defaulters.isEmpty()) return numbers
        val defaulterLine = defaulters.joinToString(", ") { rd ->
            val months = resolvedMonths[rd.id].orEmpty()
            val formatted = months.joinToString(", ") { it.formatExport() }
            "${rd.number} ($formatted)"
        }
        return "$numbers\n\nDefaulters: $defaulterLine"
    }

    fun shareViaWithImage() {
        // P5γ HIGH: bitmap render + PNG compress + FileOutputStream
        // were running on the Main thread, causing a visible freeze
        // when sharing high-count LOTs. Offload to IO; startActivity
        // resumes on Main via scope's default dispatcher.
        scope.launch {
            try {
                val imageFile = withContext(Dispatchers.IO) {
                    LotImageGenerator.generateLotImage(
                        context,
                        lot.lotNumber,
                        rdNumberCount,
                        defaultCount,
                        totalMonthsInLot
                    )
                }
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
                        modifier = Modifier.size(44.dp)
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
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = AccentMint,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onEditLot,
                        enabled = rdNumberEntities.isNotEmpty(),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditCalendar,
                            contentDescription = "Edit defaulters",
                            // IconButton's built-in disabled overlay only applies when the
                            // tint inherits from LocalContentColor. An explicit tint bypasses
                            // it, so the disabled state must encode its own alpha — otherwise
                            // every LOT's edit icon looks tappable even when it is not.
                            tint = if (rdNumberEntities.isNotEmpty()) WarningAmber else WarningAmber.copy(alpha = 0.32f),
                            modifier = Modifier.size(22.dp)
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
                        .background(GradientPeach, RoundedCornerShape(12.dp))
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
                                val months = resolvedMonths[rd.id].orEmpty()
                                Box(
                                    modifier = Modifier
                                        .background(
                                            WarningAmber.copy(alpha = 0.16f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = months.joinToString(", ") {
                                            it.formatShort().substringBefore(' ')
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
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

}

@Composable
private fun LotListSkeleton() {
    val transition = rememberInfiniteTransition(label = "lots_shimmer")
    val shimmer by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )
    val block = Color.Gray.copy(alpha = 0.12f * shimmer)
    val line = Color.Gray.copy(alpha = 0.08f * shimmer)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(block)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.45f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(block)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.3f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(line)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(line)
                    )
                }
            }
        }
    }
}
