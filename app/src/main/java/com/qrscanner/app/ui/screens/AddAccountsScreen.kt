package com.qrscanner.app.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.data.AccountSource
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.data.SyncEvent
import com.qrscanner.app.data.SyncEventType
import com.qrscanner.app.data.SyncStatus
import com.qrscanner.app.ui.components.GradientTopBar
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.BackgroundWhite
import com.qrscanner.app.ui.theme.CardBackground
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.GradientPeach
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.util.QrPdfExporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * In-memory draft for a single spreadsheet row. The `id` is stable
 * across recompositions so LazyColumn `key = { it.id }` keeps focus on
 * the active TextField even as rows fade in beneath it. `dupFlag` is
 * recomputed asynchronously each time `rdNumber` changes — the heavy
 * lookup runs off the composition thread via a LaunchedEffect.
 */
private data class AccountDraft(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var rdNumber: String = "",
    var denomination: String = "",
    var dupFlag: DupFlag = DupFlag.None
)

private sealed interface DupFlag {
    data object None : DupFlag
    data class Active(val ownerName: String) : DupFlag
    data class Inactive(val ownerName: String) : DupFlag
}

private val RD_NUMBER_REGEX = Regex("^\\d{9,15}$")

@Composable
fun AddAccountsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAccounts: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current

    val drafts = remember { listOf(AccountDraft(), AccountDraft()).toMutableStateList() }
    var saveModal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val validRowCount by remember {
        derivedStateOf {
            drafts.count { it.isFullyValid() }
        }
    }
    val anyInvalid by remember {
        derivedStateOf {
            drafts.any { it.isPartiallyFilled() && !it.isFullyValid() }
        }
    }
    val saveEnabled by remember {
        derivedStateOf { validRowCount >= 1 && !anyInvalid && !saving }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(GradientPeach, Color.White, GradientPeach))
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AddAccountsHeader(
                countLabel = if (validRowCount == 0) "Fill in to begin"
                else "$validRowCount ready to save",
                onNavigateBack = onNavigateBack
            )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item("legend") {
                ColumnLegend()
            }
            items(items = drafts, key = { it.id }) { draft ->
                // animateItem() (Compose 1.7+) replaces the broken
                // AnimatedVisibility(visible=true) — it animates row
                // additions/removals at the LazyColumn level instead
                // of inside a no-op visibility wrapper.
                SpreadsheetRow(
                    modifier = Modifier.animateItem(),
                    draft = draft,
                    onChange = { updated ->
                        val idx = drafts.indexOfFirst { it.id == draft.id }
                        if (idx >= 0) {
                            drafts[idx] = updated
                            maintainTrailingEmpty(drafts)
                        }
                    },
                    onRdLookup = { rdNumber, onResult ->
                        scope.launch {
                            val hit = if (rdNumber.matches(RD_NUMBER_REGEX)) {
                                app.database.rdAccountDao().findByRdNumberIncludingDeleted(rdNumber)
                            } else null
                            onResult(
                                when {
                                    hit == null || hit.deletedAt != null -> DupFlag.None
                                    hit.isActive -> DupFlag.Active(hit.name)
                                    else -> DupFlag.Inactive(hit.name)
                                }
                            )
                        }
                    },
                    focusManager = focusManager
                )
            }
            item("footer-spacer") {
                Spacer(modifier = Modifier.height(96.dp))
            }
        }

            SaveFooter(
                saveEnabled = saveEnabled,
                validRowCount = validRowCount,
                onSave = {
                    kb?.hide()
                    saveModal = true
                }
            )
        }
    }

    if (saveModal) {
        SaveConfirmDialog(
            count = validRowCount,
            onCancel = { saveModal = false },
            onSaveOnly = {
                saveModal = false
                if (saving) return@SaveConfirmDialog
                saving = true
                scope.launch {
                    try {
                        persistAll(app, drafts)
                        onNavigateToAccounts()
                    } catch (t: Throwable) {
                        android.util.Log.e("AddAccountsScreen", "persistAll failed", t)
                    } finally {
                        saving = false
                    }
                }
            },
            onSaveAndQr = {
                saveModal = false
                if (saving) return@SaveConfirmDialog
                saving = true
                scope.launch {
                    try {
                        val saved = persistAll(app, drafts)
                        val uri = withContext(Dispatchers.IO) {
                            runCatching { QrPdfExporter.generate(context, saved) }
                                .onFailure { android.util.Log.e("AddAccountsScreen", "QR PDF gen failed", it) }
                                .getOrNull()
                        }
                        if (uri != null) {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Share QR PDF"))
                        }
                        onNavigateToAccounts()
                    } catch (t: Throwable) {
                        android.util.Log.e("AddAccountsScreen", "Save & Generate QR failed", t)
                    } finally {
                        saving = false
                    }
                }
            }
        )
    }
}

/** Walks drafts and ensures EXACTLY one trailing empty row at all times. */
private fun maintainTrailingEmpty(drafts: SnapshotStateList<AccountDraft>) {
    while (drafts.size >= 2 && drafts[drafts.lastIndex].isEmpty() && drafts[drafts.lastIndex - 1].isEmpty()) {
        drafts.removeAt(drafts.lastIndex)
    }
    if (drafts.isEmpty() || !drafts.last().isEmpty()) {
        drafts.add(AccountDraft())
    }
}

private suspend fun persistAll(
    app: QRScannerApp,
    drafts: SnapshotStateList<AccountDraft>
): List<RdAccount> {
    val now = System.currentTimeMillis()
    val out = mutableListOf<RdAccount>()
    val dao = app.database.rdAccountDao()
    for (d in drafts) {
        if (!d.isFullyValid()) continue
        val rdNumber = d.rdNumber.trim()
        val name = d.name.trim()
        val amount = d.denomination.trim().toInt()

        // Tombstone-resurrect path: if a soft-deleted row exists at
        // this rdNumber the duplicate-flag check already let us through
        // (DupFlag.None for deletedAt != null), but a plain insert
        // would PK-conflict + get swallowed. Resurrect in place so the
        // user's intent (new name + amount) persists and the cloud
        // sees a single row transitioning deleted -> alive
        // (oracle bg_6543c8c7 S4).
        val tombstone = dao.findByRdNumberIncludingDeleted(rdNumber)
        if (tombstone != null && tombstone.deletedAt != null) {
            dao.resurrectTombstone(
                rdNumber = rdNumber,
                name = name,
                monthlyAmount = amount,
                source = AccountSource.MANUAL.name,
                updatedAt = now
            )
            dao.findByRdNumber(rdNumber)?.let { out += it }
            continue
        }

        val account = RdAccount(
            rdNumber = rdNumber,
            name = name,
            monthlyAmount = amount,
            source = AccountSource.MANUAL,
            isActive = true,
            cloudId = UUID.randomUUID().toString(),
            syncStatus = SyncStatus.DIRTY,
            updatedAt = now
        )
        runCatching { dao.insert(account) }
            .onFailure {
                if (com.qrscanner.app.BuildConfig.DEBUG) {
                    android.util.Log.w("AddAccountsScreen", "duplicate ${account.rdNumber}", it)
                }
            }
        out += account
    }
    runCatching { app.syncScheduler.enqueuePush() }
        .onFailure { android.util.Log.w("AddAccountsScreen", "push enqueue failed", it) }
    if (out.isNotEmpty()) {
        runCatching {
            val settings = app.database.deviceSettingsDao().get()
            val plural = if (out.size == 1) "account" else "accounts"
            app.database.syncEventDao().insert(
                SyncEvent(
                    occurredAt = now,
                    type = SyncEventType.LOCAL_ACCOUNTS_ADDED,
                    originDeviceCloudId = settings?.deviceCloudId,
                    originDeviceName = settings?.deviceName,
                    originOperatorName = settings?.operatorName,
                    payloadSummary = "added ${out.size} $plural"
                )
            )
        }.onFailure {
            android.util.Log.w("AddAccountsScreen", "local sync_event insert failed", it)
        }
    }
    return out
}

private fun AccountDraft.isEmpty(): Boolean =
    name.isBlank() && rdNumber.isBlank() && denomination.isBlank()

private fun AccountDraft.isPartiallyFilled(): Boolean = !isEmpty()

private fun AccountDraft.isFullyValid(): Boolean {
    val n = name.trim()
    val r = rdNumber.trim()
    val a = denomination.trim()
    if (n.isEmpty() || n.length > 60) return false
    if (!r.matches(RD_NUMBER_REGEX)) return false
    val amount = a.toIntOrNull() ?: return false
    if (amount <= 0) return false
    return dupFlag == DupFlag.None
}

@Composable
private fun AddAccountsHeader(countLabel: String, onNavigateBack: () -> Unit) {
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
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PersonAddAlt1,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Add new accounts",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun ColumnLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        LegendCell("Customer name", weight = 1.4f)
        LegendCell("RD account #", weight = 1.2f)
        LegendCell("Monthly INR", weight = 0.9f)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LegendCell(label: String, weight: Float) {
    Text(
        text = label,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp
        ),
        color = TextSecondary
    )
}

@Composable
private fun SpreadsheetRow(
    draft: AccountDraft,
    onChange: (AccountDraft) -> Unit,
    onRdLookup: (String, (DupFlag) -> Unit) -> Unit,
    focusManager: androidx.compose.ui.focus.FocusManager,
    modifier: Modifier = Modifier
) {
    val nameFr = remember { FocusRequester() }
    val rdFr = remember { FocusRequester() }
    val amtFr = remember { FocusRequester() }

    LaunchedEffect(draft.rdNumber) {
        if (draft.rdNumber.isBlank()) {
            if (draft.dupFlag != DupFlag.None) onChange(draft.copy(dupFlag = DupFlag.None))
            return@LaunchedEffect
        }
        onRdLookup(draft.rdNumber.trim()) { newFlag ->
            if (newFlag != draft.dupFlag) onChange(draft.copy(dupFlag = newFlag))
        }
    }

    val borderColorRd: androidx.compose.ui.graphics.Color = when (draft.dupFlag) {
        is DupFlag.Active -> ErrorRed
        is DupFlag.Inactive -> WarningAmber
        DupFlag.None -> PrimaryOrange
    }

    Surface(
        color = CardBackground,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    placeholder = "Name",
                    focusRequester = nameFr,
                    weight = 1.4f,
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { rdFr.requestFocus() }),
                    isError = draft.name.isNotBlank() && draft.name.trim().length > 60
                )
                CompactField(
                    value = draft.rdNumber,
                    onValueChange = { onChange(draft.copy(rdNumber = it.filter(Char::isDigit))) },
                    placeholder = "1234567890",
                    focusRequester = rdFr,
                    weight = 1.2f,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { amtFr.requestFocus() }),
                    isError = draft.rdNumber.isNotBlank() &&
                        (!draft.rdNumber.matches(RD_NUMBER_REGEX) || draft.dupFlag != DupFlag.None),
                    forcedBorderColor = if (draft.rdNumber.isNotBlank() &&
                        draft.dupFlag != DupFlag.None
                    ) borderColorRd else null
                )
                CompactField(
                    value = draft.denomination,
                    onValueChange = { onChange(draft.copy(denomination = it.filter(Char::isDigit))) },
                    placeholder = "500",
                    focusRequester = amtFr,
                    weight = 0.9f,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    isError = draft.denomination.isNotBlank() &&
                        ((draft.denomination.toIntOrNull() ?: 0) <= 0)
                )
            }

            when (val flag = draft.dupFlag) {
                is DupFlag.Active -> RowHelperText(
                    text = "Already used by ${flag.ownerName}",
                    color = ErrorRed
                )
                is DupFlag.Inactive -> RowHelperText(
                    text = "Inactive account for ${flag.ownerName} — reactivate from Accounts screen",
                    color = WarningAmber
                )
                DupFlag.None -> { /* no helper text */ }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    weight: Float,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    isError: Boolean = false,
    forcedBorderColor: androidx.compose.ui.graphics.Color? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .weight(weight)
            .focusRequester(focusRequester),
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary.copy(alpha = 0.6f))
            )
        },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        isError = isError,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = if (keyboardOptions.keyboardType == KeyboardType.Number) FontFamily.Monospace else FontFamily.Default,
            color = TextPrimary
        ),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = forcedBorderColor ?: PrimaryOrange,
            unfocusedBorderColor = forcedBorderColor ?: SurfaceWhite,
            errorBorderColor = forcedBorderColor ?: ErrorRed,
            focusedContainerColor = SurfaceWhite,
            unfocusedContainerColor = SurfaceWhite,
            errorContainerColor = SurfaceWhite
        )
    )
}

@Composable
private fun RowHelperText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@Composable
private fun SaveFooter(
    saveEnabled: Boolean,
    validRowCount: Int,
    onSave: () -> Unit
) {
    Surface(
        color = SurfaceWhite,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryOrange,
                    disabledContainerColor = PrimaryOrange.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.75f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        validRowCount == 0 -> "Save"
                        else -> "Save $validRowCount account${if (validRowCount == 1) "" else "s"}"
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SaveConfirmDialog(
    count: Int,
    onCancel: () -> Unit,
    onSaveOnly: () -> Unit,
    onSaveAndQr: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("Save $count account${if (count == 1) "" else "s"}?", fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                "You can generate QR codes for these accounts any time later " +
                    "from the Accounts screen — open it from Home and tap the QR icon " +
                    "on any row, or use Bulk QR to select multiple.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        },
        confirmButton = {
            Button(
                onClick = onSaveAndQr,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save & Generate QR", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = TextSecondary)
                }
                TextButton(onClick = onSaveOnly) {
                    Text("Save without QR", color = AccentMint, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = SurfaceWhite
    )
}
