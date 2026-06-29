package com.qrscanner.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.data.AccountSource
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.ui.components.DeleteOrInactivateDialog
import com.qrscanner.app.ui.components.EditAccountDialog
import com.qrscanner.app.ui.components.GradientTopBar
import com.qrscanner.app.ui.components.IconSnackbarHost
import com.qrscanner.app.ui.components.IconSnackbarKind
import com.qrscanner.app.ui.components.showIconSnackbar
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.BackgroundWhite
import com.qrscanner.app.ui.theme.CardBackground
import com.qrscanner.app.ui.theme.GradientPeach
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.TextTertiary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.util.MonthYear
import com.qrscanner.app.util.QrPdfExporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Accounts browse screen. Three modes:
 *
 *   Browse — default. Per-row [QR] button (single-account PDF), edit
 *   icon (MANUAL rows) or Lock badge (CSV rows). Overflow menu opens
 *   the shared Delete-or-Inactivate dialog (MANUAL only).
 *
 *   Selection — entered by tapping the top-right Bulk QR icon. Each
 *   row's QR icon swaps for a Checkbox; top bar shows "N selected" +
 *   Generate / Cancel. Tap Generate → shared QrPdfExporter → ACTION_SEND.
 *
 *   Inactive — toggled by the "Show inactive" pill below the search
 *   bar. Inactive rows render muted with an "Inactive" pill where
 *   "Paid till" normally sits.
 *
 * CSV-sourced rows are locked on phone: tap edit → Snackbar "This
 * account can only be edited by Sugam — please contact him" with a
 * Lock icon. Auto-reactivate-on-scan lives in RDScannerScreen's
 * scan-success path; the rd_accounts Flow here picks up the flipped
 * isActive state immediately.
 */
@Composable
fun AccountsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val allAccounts by app.database.rdAccountDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val activeCount by app.database.rdAccountDao().observeActiveCount().collectAsStateWithLifecycle(initialValue = 0)
    val inactiveCount by app.database.rdAccountDao().observeInactiveCount().collectAsStateWithLifecycle(initialValue = 0)

    // Suppress the EmptyState flash that fires for 1 frame between the
    // initial `emptyList()` seed and the Room Flow's first real emit on
    // cold open. Skeleton placeholders shown until the Flow speaks.
    var hasReceivedFirstEmit by remember { mutableStateOf(false) }
    LaunchedEffect(allAccounts) {
        if (!hasReceivedFirstEmit) hasReceivedFirstEmit = true
    }

    // C2-P4 NITPICK saveable filter state: survive rotation + low-mem
    // process death. Selection state (selectionMode + selectedIds) stays
    // as plain remember because it's transient gesture state that
    // shouldn't persist past a process restart.
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showInactive by rememberSaveable { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    var editing by remember { mutableStateOf<RdAccount?>(null) }
    var deleting by remember { mutableStateOf<RdAccount?>(null) }
    var overflowFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showInactive) {
        if (!showInactive) {
            val inactiveSet = allAccounts.filter { !it.isActive }.map { it.rdNumber }.toSet()
            selectedIds.removeAll { it in inactiveSet }
        }
    }

    val visibleAccounts by remember {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            allAccounts.filter { account ->
                account.deletedAt == null &&
                    (showInactive || account.isActive) &&
                    (q.isEmpty() ||
                        account.name.lowercase().contains(q) ||
                        account.rdNumber.contains(q))
            }
        }
    }

    Scaffold(
        snackbarHost = { IconSnackbarHost(hostState = snackbarHostState) },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(GradientPeach, Color.White, GradientPeach))
                )
                .padding(padding)
                .statusBarsPadding()
        ) {
            AccountsHeader(
                activeCount = activeCount,
                inactiveCount = inactiveCount,
                selectionMode = selectionMode,
                selectedCount = selectedIds.size,
                onNavigateBack = {
                    if (selectionMode) {
                        selectionMode = false
                        selectedIds.clear()
                    } else onNavigateBack()
                },
                onCancelSelection = {
                    selectionMode = false
                    selectedIds.clear()
                },
                onEnterSelection = { selectionMode = true },
                onGenerateBulk = {
                    val targets = allAccounts.filter {
                        it.rdNumber in selectedIds && it.deletedAt == null
                    }
                    selectionMode = false
                    selectedIds.clear()
                    if (targets.isNotEmpty()) {
                        scope.launch {
                            // P5γ HIGH: PDF generation (ZXing matrix +
                            // setPixels + PdfDocument.writeTo file IO)
                            // blocks ~hundreds of ms for bulk QR. Offload
                            // to IO so the Compose recomposition doesn't
                            // freeze. startActivity must run on Main.
                            val uri = withContext(Dispatchers.IO) {
                                QrPdfExporter.generate(context, targets)
                            }
                            if (uri != null) {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Share QR PDF"))
                            }
                        }
                    }
                }
            )

            if (!selectionMode) {
                FilterBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    showInactive = showInactive,
                    inactiveCount = inactiveCount,
                    onToggleShowInactive = { showInactive = it }
                )
            }

            if (!hasReceivedFirstEmit && allAccounts.isEmpty()) {
                AccountListSkeleton()
            } else if (visibleAccounts.isEmpty()) {
                EmptyState(
                    isFiltered = searchQuery.isNotBlank() || (!showInactive && inactiveCount > 0)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = visibleAccounts, key = { it.rdNumber }) { account ->
                        AccountRow(
                            account = account,
                            selectionMode = selectionMode,
                            selected = account.rdNumber in selectedIds,
                            onToggleSelection = {
                                if (account.rdNumber in selectedIds) selectedIds.remove(account.rdNumber)
                                else selectedIds.add(account.rdNumber)
                            },
                            onGenerateSingleQr = {
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) {
                                        QrPdfExporter.generate(context, listOf(account))
                                    }
                                    if (uri != null) {
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Share QR PDF"))
                                    }
                                }
                            },
                            onEditAttempt = {
                                if (account.source == AccountSource.CSV) {
                                    scope.launch {
                                        snackbarHostState.showIconSnackbar(
                                            message = "This account can only be edited by Sugam — please contact him",
                                            icon = Icons.Default.Lock,
                                            kind = IconSnackbarKind.Info
                                        )
                                    }
                                } else {
                                    editing = account
                                }
                            },
                            onOverflowOpen = { overflowFor = account.rdNumber },
                            onOverflowDismiss = { overflowFor = null },
                            overflowOpen = overflowFor == account.rdNumber,
                            onMarkInactive = {
                                overflowFor = null
                                deleting = account
                            }
                        )
                    }
                }
            }
        }
    }

    editing?.let { acc ->
        EditAccountDialog(
            account = acc,
            onDismiss = { editing = null },
            onSave = { newName, newAmount, newActive, paidTillEdit ->
                editing = null
                scope.launch {
                    val now = System.currentTimeMillis()
                    app.database.rdAccountDao().editManualRow(
                        rdNumber = acc.rdNumber,
                        name = newName,
                        monthlyAmount = newAmount,
                        isActive = newActive,
                        updatedAt = now
                    )
                    // Operator-driven paid-till edits route to the explicit
                    // DAO methods (no monotonic guard). The dialog has
                    // already confirmed backward moves + clears via modal
                    // before reaching this branch, so we never need to
                    // re-validate here.
                    when (paidTillEdit) {
                        is com.qrscanner.app.ui.components.PaidTillEdit.Unchanged -> Unit
                        is com.qrscanner.app.ui.components.PaidTillEdit.Cleared ->
                            app.database.rdAccountDao().clearLastPaidThrough(acc.rdNumber, now)
                        is com.qrscanner.app.ui.components.PaidTillEdit.SetTo -> {
                            val token = paidTillEdit.newValue.toToken()
                            if (paidTillEdit.isRegression) {
                                app.database.rdAccountDao()
                                    .setLastPaidThroughExplicit(acc.rdNumber, token, now)
                            } else {
                                app.database.rdAccountDao()
                                    .updateLastPaidThroughMonotonic(acc.rdNumber, token, now)
                            }
                        }
                    }
                    runCatching { app.syncScheduler.enqueuePush() }
                }
            }
        )
    }

    deleting?.let { acc ->
        DeleteOrInactivateDialog(
            accountName = acc.name,
            rdNumber = acc.rdNumber,
            onCancel = { deleting = null },
            onMarkInactive = {
                val target = acc
                deleting = null
                scope.launch {
                    val now = System.currentTimeMillis()
                    app.database.rdAccountDao().markInactive(target.rdNumber, now)
                    runCatching { app.syncScheduler.enqueuePush() }
                    snackbarHostState.showIconSnackbar(
                        message = "${target.name} marked inactive",
                        icon = Icons.Default.Check
                    )
                }
            },
            onDelete = {
                val target = acc
                deleting = null
                scope.launch {
                    val now = System.currentTimeMillis()
                    app.database.rdAccountDao().softDelete(target.rdNumber, now)
                    runCatching { app.syncScheduler.enqueuePush() }
                    snackbarHostState.showIconSnackbar(
                        message = "${target.name} deleted",
                        icon = Icons.Default.Close,
                        kind = IconSnackbarKind.Error
                    )
                }
            }
        )
    }
}

@Composable
private fun AccountsHeader(
    activeCount: Int,
    inactiveCount: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    onNavigateBack: () -> Unit,
    onCancelSelection: () -> Unit,
    onEnterSelection: () -> Unit,
    onGenerateBulk: () -> Unit
) {
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
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (selectionMode) {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onCancelSelection,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White.copy(alpha = 0.2f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onGenerateBulk,
                    enabled = selectedCount > 0,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            Color.White.copy(alpha = if (selectedCount > 0) 0.2f else 0.18f),
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = "Generate QR PDF",
                        tint = Color.White.copy(alpha = if (selectedCount > 0) 1f else 0.6f)
                    )
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Accounts",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Text(
                        text = if (inactiveCount == 0) "$activeCount active"
                        else "$activeCount active · $inactiveCount inactive",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                IconButton(
                    onClick = onEnterSelection,
                    enabled = activeCount > 0,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            Color.White.copy(alpha = if (activeCount > 0) 0.2f else 0.18f),
                            CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.QrCode2,
                        contentDescription = "Bulk QR",
                        tint = Color.White.copy(alpha = if (activeCount > 0) 1f else 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    showInactive: Boolean,
    inactiveCount: Int,
    onToggleShowInactive: (Boolean) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search by name or RD number", color = TextTertiary) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryOrange,
                unfocusedBorderColor = SurfaceWhite,
                focusedContainerColor = SurfaceWhite,
                unfocusedContainerColor = SurfaceWhite
            )
        )
        if (inactiveCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = if (showInactive) AccentMint.copy(alpha = 0.15f) else CardBackground,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.clickable { onToggleShowInactive(!showInactive) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (showInactive) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = if (showInactive) AccentMint else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Show inactive ($inactiveCount)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (showInactive) AccentMint else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: RdAccount,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onGenerateSingleQr: () -> Unit,
    onEditAttempt: () -> Unit,
    onOverflowOpen: () -> Unit,
    onOverflowDismiss: () -> Unit,
    overflowOpen: Boolean,
    onMarkInactive: () -> Unit
) {
    val mutedAlpha = if (account.isActive) 1f else 0.6f
    Surface(
        color = if (account.isActive) SurfaceWhite else CardBackground,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectionMode) { onToggleSelection() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PrimaryOrange,
                        uncheckedColor = TextTertiary
                    )
                )
            } else {
                // 44dp outer wrapper owns the click + tap area (WCAG);
                // 40dp inner Box owns the visual circle. This is the
                // wrap-pattern used throughout the app for chip-sized
                // controls so the touch target stays compliant even
                // when the rendered chip is intentionally smaller.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(enabled = account.isActive) { onGenerateSingleQr() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(PrimaryOrange.copy(alpha = 0.12f * mutedAlpha), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = "Generate QR",
                            tint = PrimaryOrange.copy(alpha = mutedAlpha),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary.copy(alpha = mutedAlpha),
                    maxLines = 1
                )
                Text(
                    text = account.rdNumber,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary.copy(alpha = mutedAlpha)
                )
                Text(
                    text = "INR ${account.monthlyAmount} / month",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary.copy(alpha = mutedAlpha)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (account.isActive) {
                    Text(
                        text = "Paid till",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                    Text(
                        text = formatPaidTill(account.lastPaidThrough),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (account.lastPaidThrough != null) AccentMint else TextSecondary
                    )
                } else {
                    Surface(
                        color = WarningAmber.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Inactive",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = WarningAmber
                        )
                    }
                }

                if (!selectionMode) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        // 44dp meets WCAG touch-target minimum. Earlier
                        // comment claimed 40dp was sufficient via the
                        // MD3 IconButton ripple padding — that was wrong;
                        // setting `.size(40.dp)` on IconButton forces
                        // the touch area down to 40dp, below the WCAG
                        // floor. Glyph stays at 18dp; only the tap area
                        // grows.
                        if (account.source == AccountSource.CSV) {
                            IconButton(onClick = onEditAttempt, modifier = Modifier.size(44.dp)) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Locked — contact Sugam",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            IconButton(onClick = onEditAttempt, modifier = Modifier.size(44.dp)) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Box {
                                IconButton(onClick = onOverflowOpen, modifier = Modifier.size(44.dp)) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        contentDescription = "More",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = overflowOpen,
                                    onDismissRequest = onOverflowDismiss
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Mark inactive / Delete", color = TextPrimary) },
                                        onClick = onMarkInactive
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(isFiltered: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.People,
                contentDescription = null,
                tint = PrimaryOrange,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isFiltered) "No accounts match" else "No accounts yet",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isFiltered)
                    "Try a different search or toggle Show inactive."
                else
                    "Add accounts from Home → Add Account.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

private fun formatPaidTill(yyyyMm: String?): String {
    if (yyyyMm == null) return "Not started"
    val parsed = MonthYear.parseToken(yyyyMm) ?: return "—"
    return parsed.formatShort()
}

@Composable
private fun AccountListSkeleton() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "accounts_shimmer")
    val shimmer by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 900,
                easing = androidx.compose.animation.core.LinearEasing
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )
    val avatar = Color.Gray.copy(alpha = 0.12f * shimmer)
    val line = Color.Gray.copy(alpha = 0.10f * shimmer)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(6) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(avatar)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(avatar)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.35f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(line)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(line)
                    )
                }
            }
        }
    }
}
