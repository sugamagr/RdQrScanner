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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.R
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
 * Sort key for the accounts list. Ordered by expected-use frequency
 * so [NAME] (the default) ends up as the first chip in the sort row.
 *
 * - [NAME]        A→Z on lowercased name. Default for browse.
 * - [LAST_PAID]   YYYY-MM ascending; null (never paid) surfaces first
 *                 so the operator sees "needs attention" accounts on
 *                 top instead of buried at the tail.
 * - [AMOUNT]      monthlyAmount descending. Big-ticket accounts first.
 * - [RECENT]      updatedAt descending. Fresh edits/scans at the top.
 */
enum class SortKey { NAME, LAST_PAID, AMOUNT, RECENT }

/**
 * Filter selector for the accounts list. Operates OVER-AND-ABOVE the
 * existing "Show inactive" toggle (which changes the base set), so
 * filter modes here only touch active-set accounts unless noted.
 *
 * - [ALL]         reset — no filter beyond active/inactive base
 * - [DEFAULTERS]  lastPaidThrough != currentMonth (YYYY-MM) AND active
 *                 Covers underpaid (last_paid_through < currentMonth),
 *                 never-paid (last_paid_through == null), AND forward-
 *                 paid (last_paid_through > currentMonth — they paid
 *                 for a future month but not for the current one).
 *                 Per user's operational definition: "if it is current
 *                 month account, then it is not default. Every other
 *                 condition means a default account." (R2 discussion.)
 * - [NEVER_PAID]  lastPaidThrough == null AND active — a strict subset
 *                 of DEFAULTERS but kept as a separate data-slice
 *                 filter so the operator can drill down to brand-new
 *                 accounts distinct from lapsed/forward-paid rows.
 * - [MANUAL]      source == MANUAL — phone-added accounts
 * - [CSV]         source == CSV — portal-imported accounts
 */
enum class FilterMode { ALL, DEFAULTERS, NEVER_PAID, MANUAL, CSV }

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
fun AccountsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddAccount: () -> Unit,
    onNavigateToAccountHistory: (rdNumber: String) -> Unit,
) {
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
    var sortKey by rememberSaveable { mutableStateOf(SortKey.NAME) }
    var filterMode by rememberSaveable { mutableStateOf(FilterMode.ALL) }
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
            // Defaulter check needs a live "current month" reference. Using
            // MonthYear.current().toToken() (see MonthYear.kt) so the phone's
            // local-time YYYY-MM matches the same source the RD scan flow +
            // portal defaulter view already share. Keeps defaulter semantics
            // consistent across surfaces.
            val currentMonth = MonthYear.current().toToken()
            val filtered = allAccounts.filter { account ->
                if (account.deletedAt != null) return@filter false
                if (!showInactive && !account.isActive) return@filter false
                if (q.isNotEmpty() &&
                    !account.name.lowercase().contains(q) &&
                    !account.rdNumber.contains(q)) return@filter false
                when (filterMode) {
                    FilterMode.ALL -> true
                    // R2 SEMANTIC: defaulter = "not exactly current month"
                    // (equality, not < comparison). Covers null / < / >
                    // in one expression. See FilterMode KDoc above for
                    // rationale. Must stay in sync with portal's
                    // dashboardQueries.ts computeAccountStatus.
                    FilterMode.DEFAULTERS -> account.isActive &&
                        account.lastPaidThrough != currentMonth
                    FilterMode.NEVER_PAID -> account.isActive &&
                        account.lastPaidThrough == null
                    FilterMode.MANUAL -> account.source == AccountSource.MANUAL
                    FilterMode.CSV -> account.source == AccountSource.CSV
                }
            }
            when (sortKey) {
                SortKey.NAME -> filtered.sortedBy { it.name.lowercase() }
                // Nulls first so never-paid accounts sit at the top of the
                // "Last paid" view — mirrors the SortKey.LAST_PAID docstring
                // rationale (needs-attention accounts surface first).
                SortKey.LAST_PAID -> filtered.sortedWith(
                    compareBy(nullsFirst()) { it.lastPaidThrough }
                )
                SortKey.AMOUNT -> filtered.sortedByDescending { it.monthlyAmount }
                SortKey.RECENT -> filtered.sortedByDescending { it.updatedAt }
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
                    onToggleShowInactive = { showInactive = it },
                    sortKey = sortKey,
                    onSortKeyChange = { sortKey = it },
                    filterMode = filterMode,
                    onFilterModeChange = { filterMode = it }
                )
            }

            if (!hasReceivedFirstEmit && allAccounts.isEmpty()) {
                AccountListSkeleton()
            } else if (visibleAccounts.isEmpty()) {
                EmptyState(
                    isFiltered = searchQuery.isNotBlank() ||
                        (!showInactive && inactiveCount > 0) ||
                        filterMode != FilterMode.ALL,
                    onAddAccount = onNavigateToAddAccount,
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
                            },
                            onViewHistory = {
                                overflowFor = null
                                onNavigateToAccountHistory(account.rdNumber)
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
            // Primary nav slot carries the "escape hatch" of the current
            // mode: ArrowBack when browsing (back stack), Close when in
            // selection mode (exit selection). Keeping ONE escape control
            // avoids two synonymous buttons on the same screen. Icon +
            // action must stay coupled — swapping only the icon while
            // keeping the back-stack callback is a footgun.
            IconButton(
                onClick = if (selectionMode) onCancelSelection else onNavigateBack,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = if (selectionMode) Icons.Default.Close
                        else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (selectionMode) stringResource(R.string.content_desc_exit_selection)
                        else stringResource(R.string.content_desc_back),
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
                // Generate-QR affordance in selection mode: icon-only here
                // because the row already shows "N selected" + the count
                // dynamically shrinks the available label width. The
                // adjacent text label pattern (see normal mode below) is
                // used ONLY where there's room without pushing the count
                // text off-screen on narrow devices.
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
                        contentDescription = stringResource(R.string.acc_action_generate_qr),
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
                // Icon + literal "Generate QR" label. The icon alone is not
                // discoverable — new operators hover on the QR icon expecting
                // a scanner. The text label locks the affordance's meaning
                // (bulk-print → generate → QR) as a Dribbble-style pill
                // rather than an ambiguous glyph. Localized string so the
                // Hindi build shows "क्यूआर बनाएं" per the strings-hi entry.
                Surface(
                    onClick = onEnterSelection,
                    enabled = activeCount > 0,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = if (activeCount > 0) 0.2f else 0.18f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = if (activeCount > 0) 1f else 0.6f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.acc_action_generate_qr),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = if (activeCount > 0) 1f else 0.6f)
                            )
                        )
                    }
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
    onToggleShowInactive: (Boolean) -> Unit,
    sortKey: SortKey,
    onSortKeyChange: (SortKey) -> Unit,
    filterMode: FilterMode,
    onFilterModeChange: (FilterMode) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search by name or RD number", color = TextTertiary) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = PrimaryOrange
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryOrange,
                unfocusedBorderColor = PrimaryOrange,
                focusedContainerColor = SurfaceWhite,
                unfocusedContainerColor = SurfaceWhite
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        SortChipRow(sortKey = sortKey, onSortKeyChange = onSortKeyChange)
        Spacer(modifier = Modifier.height(8.dp))
        FilterChipRow(filterMode = filterMode, onFilterModeChange = onFilterModeChange)
        if (inactiveCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = if (showInactive) AccentMint.copy(alpha = 0.15f) else CardBackground,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onToggleShowInactive(!showInactive) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
    onMarkInactive: () -> Unit,
    onViewHistory: () -> Unit
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
            // Selection checkbox slides in from the left (fadeIn +
            // expandHorizontally) rather than popping instantly. 220ms
            // FastOutSlowIn tween matches Material 3's list-item
            // affordance timing so the animation composes with the
            // Row's implicit re-layout instead of racing it.
            AnimatedVisibility(
                visible = selectionMode,
                enter = fadeIn(animationSpec = tween(220)) +
                    expandHorizontally(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(180)) +
                    shrinkHorizontally(animationSpec = tween(180))
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = PrimaryOrange,
                        uncheckedColor = TextTertiary
                    )
                )
            }
            if (!selectionMode) {
                // Per-row Generate-QR chip. Hidden entirely in selection
                // mode because bulk-generate lives in the top bar and the
                // row's click surface is already claimed by toggle-select.
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
                            contentDescription = stringResource(R.string.acc_action_generate_qr),
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
                        // P3 SEMANTIC: leading icon still gates on
                        // CSV-vs-MANUAL for the edit affordance (Lock
                        // reminds operator CSV rows are portal-only),
                        // but the trailing overflow (3-dot) now
                        // appears for BOTH sources so the read-only
                        // "View history" action is always reachable.
                        // CSV rows just get a shorter menu without
                        // the destructive "Mark inactive / Delete"
                        // item since those actions require unlock.
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
                                    text = { Text("View history", color = TextPrimary) },
                                    onClick = onViewHistory
                                )
                                if (account.source != AccountSource.CSV) {
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
private fun EmptyState(
    isFiltered: Boolean,
    onAddAccount: () -> Unit,
) {
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
                    "Add your first RD account to start tracking deposits.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            if (!isFiltered) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onAddAccount,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryOrange,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Add Account",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
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

/**
 * Sort-selector chip row. Single-select behaviour — tapping a chip
 * commits its [SortKey] as the active sort. Selected chip renders as
 * a PrimaryOrange pill with white text; unselected chips are muted
 * cards with TextSecondary text. Follows the same "pill" pattern the
 * Show inactive toggle uses so all filter/sort affordances feel like
 * one design family.
 *
 * Horizontally scrollable so the row survives narrower phones + Hindi
 * labels (which are ~20% wider than English for common sort words).
 * The "Sort" leading label anchors screen-reader traversal.
 */
@Composable
private fun SortChipRow(
    sortKey: SortKey,
    onSortKeyChange: (SortKey) -> Unit
) {
    val options = listOf(
        SortKey.NAME to "Name",
        SortKey.LAST_PAID to "Last paid",
        SortKey.AMOUNT to "Amount",
        SortKey.RECENT to "Recent"
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp)
    ) {
        item {
            Text(
                text = "Sort",
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        items(options.size) { index ->
            val (key, label) = options[index]
            val selected = key == sortKey
            SelectablePill(
                label = label,
                selected = selected,
                accent = PrimaryOrange,
                onClick = { onSortKeyChange(key) }
            )
        }
    }
}

/**
 * Filter-selector chip row. Single-select over the FilterMode enum,
 * with AccentMint as the selected accent (deliberately distinct from
 * the sort row's PrimaryOrange so an operator can see at a glance
 * which affordance is "sort" vs "filter"). The trailing chips are
 * source filters (Manual / CSV) so they cluster together separate
 * from the state-based filters (All / Defaulters / Never paid).
 */
@Composable
private fun FilterChipRow(
    filterMode: FilterMode,
    onFilterModeChange: (FilterMode) -> Unit
) {
    val options = listOf(
        FilterMode.ALL to "All",
        FilterMode.DEFAULTERS to "Defaulters",
        FilterMode.NEVER_PAID to "Never paid",
        FilterMode.MANUAL to "Manual",
        FilterMode.CSV to "CSV"
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp)
    ) {
        item {
            Text(
                text = "Filter",
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        items(options.size) { index ->
            val (mode, label) = options[index]
            val selected = mode == filterMode
            SelectablePill(
                label = label,
                selected = selected,
                accent = AccentMint,
                onClick = { onFilterModeChange(mode) }
            )
        }
    }
}

/**
 * Shared pill primitive used by both sort + filter rows. Kept private
 * to this file so the exact rendering stays under the same design
 * gravity — extracting to a shared component would tempt other screens
 * to reuse it and drift the visual language. If that ever becomes a
 * genuine need, lift to ui/components/ intentionally.
 *
 * The selected state uses the passed [accent] at 0.15 alpha as fill
 * plus full-strength text/border, which matches the "Show inactive"
 * toggle exactly. Unselected uses CardBackground fill with TextSecondary,
 * matching the same neutral surface tokens used across the app so the
 * chips read as part of the existing design system, not a new one.
 */
@Composable
private fun SelectablePill(
    label: String,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val fillColor = if (selected) accent.copy(alpha = 0.15f) else CardBackground
    val textColor = if (selected) accent else TextSecondary
    Surface(
        color = fillColor,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = textColor
            )
        }
    }
}
