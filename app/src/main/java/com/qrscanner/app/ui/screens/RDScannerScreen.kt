package com.qrscanner.app.ui.screens

import android.Manifest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import androidx.compose.ui.res.stringResource
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.R
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.data.ScanLot
import com.qrscanner.app.data.ScanSession
import com.qrscanner.app.data.SyncEvent
import com.qrscanner.app.data.SyncEventType
import com.qrscanner.app.util.isValidRdNumber
import com.qrscanner.app.ui.components.ResumeSessionDialog
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SuccessGreen
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.ui.theme.CardBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RDScannerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (Long) -> Unit
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
    
    when {
        cameraPermissionState.status.isGranted -> {
            RDCameraScreen(
                onNavigateBack = onNavigateBack,
                onNavigateToSession = onNavigateToSession
            )
        }
        cameraPermissionState.status.shouldShowRationale -> {
            PermissionScreen(
                title = "Camera Permission Required",
                message = "To scan RD Book QR codes, we need access to your camera.",
                buttonText = "Grant Permission",
                onButtonClick = { cameraPermissionState.launchPermissionRequest() }
            )
        }
        else -> {
            PermissionScreen(
                title = "Camera Access Denied",
                message = "Please enable camera permission in your device settings.",
                buttonText = "Go Back",
                onButtonClick = onNavigateBack
            )
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun RDCameraScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as QRScannerApp
    
    // Session state — hydrated from DB in the init effect below. currentLotId
    // tracks the in-progress LOT row (created on first scan, cleared on finish).
    // Saveable so config change preserves in-flight bookkeeping; otherwise the
    // hydration effect would treat a just-finished LOT as in-progress.
    var currentSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var currentSession by remember { mutableStateOf<ScanSession?>(null) }
    var currentLotNumber by rememberSaveable { mutableIntStateOf(1) }
    var totalLotsInSession by rememberSaveable { mutableIntStateOf(0) }
    var currentLotId by rememberSaveable { mutableStateOf<Long?>(null) }
    val currentLotNumbers = remember { mutableStateListOf<String>() }
    val allSessionNumbers = remember { mutableStateListOf<String>() }
    val lotAmountCache = remember { mutableStateMapOf<String, Int?>() }
    var isHydrated by remember { mutableStateOf(false) }

    // Camera state — flash survives config change so users don't have to retoggle.
    var isFlashOn by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    // Thread-safe scanning flags using atomic references
    val scanningEnabledRef = remember { AtomicBoolean(true) }
    val isScanningRef = remember { AtomicBoolean(true) }
    val pendingValueRef = remember { AtomicReference<String?>(null) }

    // UI state — dialog visibility persists across config change.
    var showEndSessionDialog by rememberSaveable { mutableStateOf(false) }
    var showFinishLotDialog by rememberSaveable { mutableStateOf(false) }
    var lastScanFeedback by remember { mutableStateOf<ScanFeedback?>(null) }

    // Resume flow — surfaced when an active session from a prior launch is found.
    var showResumeDialog by remember { mutableStateOf(false) }
    var resumeSummary by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sessionPendingResume by remember { mutableStateOf<ScanSession?>(null) }

    // LOT review flow state — drives the full-screen review used by
    // BOTH the fresh-scan path (here) and the recorded-session edit
    // path (SessionDetailScreen via LotReviewBuilder + LotReviewPersister).
    // Saveable so config change mid-review keeps the screen up; the
    // row list is re-hydrated from DB on recompose via lotId.
    var showLotReviewScreen by rememberSaveable { mutableStateOf(false) }
    var lotReviewLotNumber by rememberSaveable { mutableIntStateOf(0) }
    var lotReviewLotId by rememberSaveable { mutableStateOf<Long?>(null) }
    var lotReviewLotTimestamp by rememberSaveable { mutableLongStateOf(0L) }
    var lotReviewBaseRows by remember { mutableStateOf<List<com.qrscanner.app.ui.screens.LotReviewRow>>(emptyList()) }
    var lotReviewLoading by remember { mutableStateOf(false) }
    // Operator's per-row month deltas, keyed by RdNumber.id. Saveable so
    // rotation + process death don't silently drop in-flight edits before
    // the operator taps Confirm. Compact format: "id=YYYY-MM,YYYY-MM;..."
    // keeps the Bundle small even for large LOTs (50 rows × ~30 bytes).
    var lotReviewEdits by rememberSaveable(stateSaver = LotReviewEditsSaver) {
        mutableStateOf<Map<Long, List<com.qrscanner.app.util.MonthYear>>>(emptyMap())
    }
    val lotReviewRows = remember(lotReviewBaseRows, lotReviewEdits) {
        lotReviewBaseRows.map { base ->
            val edit = lotReviewEdits[base.rdNumber.id]
            if (edit != null) base.copy(selected = edit) else base
        }
    }
    var pendingPostSave by rememberSaveable(stateSaver = PostSaveSaver) {
        mutableStateOf<PostSave?>(null)
    }
    
    // Trigger for processing scanned value on main thread
    var scanTrigger by remember { mutableStateOf(0) }
    
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }
    val listState = rememberLazyListState()
    
    // Tone generator for beep
    val toneGenerator = remember { 
        try { 
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) 
        } catch (e: Exception) { 
            null 
        } 
    }
    
    suspend fun adoptSession(session: ScanSession) {
        val lots = app.database.scanLotDao().getLotsForSessionSync(session.id)
        val allRows = lots.flatMap { app.database.rdNumberDao().getNumbersForLotSync(it.id) }
        val pinnedLotId = session.activeLotId
        val pinnedLot = pinnedLotId?.let { id -> lots.firstOrNull { it.id == id } }
        val pinnedRows = pinnedLot?.let { app.database.rdNumberDao().getNumbersForLotSync(it.id) } ?: emptyList()

        currentSession = session
        currentSessionId = session.id
        allSessionNumbers.clear()
        allSessionNumbers.addAll(allRows.map { it.number })
        currentLotNumbers.clear()
        currentLotNumbers.addAll(pinnedRows.sortedByDescending { it.position }.map { it.number })

        if (pinnedLot != null) {
            currentLotId = pinnedLot.id
            currentLotNumber = pinnedLot.lotNumber
            totalLotsInSession = lots.count { it.id != pinnedLot.id }
        } else {
            if (pinnedLotId != null) {
                app.database.scanSessionDao().setActiveLotId(session.id, null)
            }
            currentLotId = null
            val highest = lots.maxOfOrNull { it.lotNumber } ?: 0
            currentLotNumber = highest + 1
            totalLotsInSession = lots.size
        }
        isHydrated = true
    }

    suspend fun startFreshSession() {
        val session = ScanSession()
        val sessionId = app.database.scanSessionDao().insert(session)
        currentSession = session.copy(id = sessionId)
        currentSessionId = sessionId
        currentLotNumber = 1
        totalLotsInSession = 0
        currentLotId = null
        currentLotNumbers.clear()
        allSessionNumbers.clear()
        isHydrated = true
    }

    suspend fun rehydrateAfterConfigChange(sessionId: Long) {
        val session = app.database.scanSessionDao().getSessionById(sessionId)
        if (session == null) {
            currentSessionId = null
            currentLotId = null
            currentLotNumber = 1
            totalLotsInSession = 0
            startFreshSession()
            return
        }
        currentSession = session
        val allRows = app.database.rdNumberDao().getAllNumbersInSession(sessionId)
        allSessionNumbers.clear()
        allSessionNumbers.addAll(allRows)
        val lotId = currentLotId
        if (lotId != null) {
            val rows = app.database.rdNumberDao().getNumbersForLotSync(lotId)
            currentLotNumbers.clear()
            currentLotNumbers.addAll(rows.sortedByDescending { it.position }.map { it.number })
        }
        val pendingLotId = lotReviewLotId
        if (pendingLotId != null && showLotReviewScreen) {
            val rehydrated = LotReviewBuilder.build(app, pendingLotId, lotReviewLotTimestamp)
            if (rehydrated.isEmpty()) {
                // Stale review state — the LOT was deleted while the
                // process was dead (FK cascade from a session delete on
                // another device). Tear down to avoid stuck UI: clear
                // every review flag so the scanner returns to a usable
                // state. Don't try to finalize the parent session
                // either — the cascade nuked it too, so there's
                // nothing to wrap up. The next user action either
                // starts a fresh session or pops back home.
                showLotReviewScreen = false
                lotReviewLotId = null
                lotReviewEdits = emptyMap()
                pendingPostSave = null
            } else {
                lotReviewBaseRows = rehydrated
            }
        }
        isHydrated = true
    }

    LaunchedEffect(Unit) {
        if (isHydrated) return@LaunchedEffect
        val savedSessionId = currentSessionId
        if (savedSessionId != null) {
            rehydrateAfterConfigChange(savedSessionId)
            return@LaunchedEffect
        }
        val activeIds = app.database.scanSessionDao().getAllActiveSessionIds()
        if (activeIds.size > 1) {
            activeIds.drop(1).forEach { stale ->
                app.database.rdNumberDao().deleteForSession(stale)
                app.database.scanLotDao().deleteLotsForSession(stale)
                app.database.scanSessionDao().deleteById(stale)
            }
        }
        val active = app.database.scanSessionDao().getActiveSession()
        if (active == null) {
            startFreshSession()
            return@LaunchedEffect
        }
        val lotCount = app.database.scanLotDao().getLotsForSessionSync(active.id).size
        val scanCount = app.database.rdNumberDao().getAllNumbersInSession(active.id).size
        if (lotCount == 0 && scanCount == 0) {
            adoptSession(active)
        } else {
            sessionPendingResume = active
            resumeSummary = lotCount to scanCount
            showResumeDialog = true
        }
    }
    
    // Process scanned values on main thread when triggered
    LaunchedEffect(scanTrigger) {
        if (scanTrigger > 0) {
            val value = pendingValueRef.get()
            if (value != null) {
                val cleanValue = value.trim()
                
                when {
                    !isValidRdNumber(cleanValue) -> {
                        lastScanFeedback = ScanFeedback.Invalid("Not a valid RD number (need 9-15 digits)")
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                            val vibrator = context.getSystemService(Vibrator::class.java)
                            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        } catch (e: Exception) { /* ignore */ }
                    }
                    currentLotNumbers.contains(cleanValue) -> {
                        lastScanFeedback = ScanFeedback.Duplicate("Already in current LOT")
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                            val vibrator = context.getSystemService(Vibrator::class.java)
                            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        } catch (e: Exception) { /* ignore */ }
                    }
                    allSessionNumbers.contains(cleanValue) -> {
                        lastScanFeedback = ScanFeedback.Duplicate("Already scanned in this session")
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
                            val vibrator = context.getSystemService(Vibrator::class.java)
                            vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                        } catch (e: Exception) { /* ignore */ }
                    }
                    else -> {
                        val session = currentSession
                        if (session == null) {
                            pendingValueRef.set(null)
                            isScanningRef.set(true)
                            return@LaunchedEffect
                        }
                        val lotId = currentLotId ?: run {
                            val newId = app.database.scanLotDao().insert(
                                ScanLot(sessionId = session.id, lotNumber = currentLotNumber)
                            )
                            currentLotId = newId
                            app.database.scanSessionDao().setActiveLotId(session.id, newId)
                            newId
                        }
                        val position = app.database.rdNumberDao().getNextPosition(lotId)
                        app.database.rdNumberDao().insert(
                            RdNumber(lotId = lotId, number = cleanValue, position = position)
                        )
                        // Auto-reactivate inactive account profile on scan
                        // (user contract: scanning a marked-inactive RD
                        // flips it back to active + DIRTY for sync — the
                        // paper book is truth — and surfaces a Toast so
                        // the operator knows the system noticed and
                        // healed the state).
                        runCatching {
                            val now = System.currentTimeMillis()
                            val reactivatedRows = app.database.rdAccountDao()
                                .reactivate(cleanValue, now)
                            if (reactivatedRows > 0) {
                                val profile = app.database.rdAccountDao()
                                    .findByRdNumber(cleanValue)
                                val label = profile?.name?.takeIf { it.isNotBlank() }
                                    ?: cleanValue
                                Toast.makeText(
                                    context,
                                    "Account reactivated: $label",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }.onFailure {
                            android.util.Log.w(
                                "RDScannerScreen",
                                "auto-reactivate failed for $cleanValue — local flag may drift",
                                it
                            )
                        }
                        currentLotNumbers.add(0, cleanValue)
                        allSessionNumbers.add(cleanValue)
                        lastScanFeedback = ScanFeedback.Success(cleanValue)
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                        } catch (e: Exception) { /* ignore */ }
                    }
                }

                pendingValueRef.set(null)
                delay(700)
                isScanningRef.set(true)
            }
        }
    }
    
    // Auto-scroll when new item added
    LaunchedEffect(currentLotNumbers.size) {
        if (currentLotNumbers.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // Resolve monthly amount for any newly-scanned RD number so the
    // live-total chip can sum the verified accounts. Keyed on .size
    // not .toList() — .toList() allocates a fresh list every
    // recompose so Compose sees the key as 'changed' every frame and
    // re-launches the effect. Since the LOT mutation flow is append-
    // only (scans add, finishing/rescanning empties), .size is a
    // sufficient + stable proxy for 'list changed in a way that
    // matters'. Removals via undo also bump .size down (see swipe-
    // delete in the Recently Scanned list).
    LaunchedEffect(currentLotNumbers.size) {
        val missing = currentLotNumbers.filter { it !in lotAmountCache }
        for (rdNumber in missing) {
            val amount = runCatching {
                app.database.rdAccountDao().findByRdNumber(rdNumber)?.monthlyAmount
            }.getOrNull()
            lotAmountCache[rdNumber] = amount
        }
    }

    // Derived live total for the chip. monthsPaid is always 1 at scan
    // time (defaulter counts are picked later on LotReviewScreen) so
    // the formula here is just sum(monthlyAmount) for verified rows.
    val liveLotTotal by remember {
        derivedStateOf {
            var verified = 0
            var unverified = 0
            currentLotNumbers.forEach { rdNumber ->
                val amount = lotAmountCache[rdNumber]
                if (amount != null && amount > 0) {
                    verified += amount
                } else {
                    unverified++
                }
            }
            LiveLotTotal(verifiedRupees = verified, unverifiedCount = unverified)
        }
    }
    
    // Clear feedback after delay
    LaunchedEffect(lastScanFeedback) {
        if (lastScanFeedback != null) {
            delay(2000)
            lastScanFeedback = null
        }
    }

    // Pause camera analysis whenever any dialog is open; resume when all closed.
    // Also pause while the session is still hydrating from DB.
    val anyDialogOpen = !isHydrated || showResumeDialog || showEndSessionDialog ||
            showFinishLotDialog || showLotReviewScreen
    LaunchedEffect(anyDialogOpen) {
        if (anyDialogOpen) {
            scanningEnabledRef.set(false)
            isScanningRef.set(false)
        } else {
            scanningEnabledRef.set(true)
            isScanningRef.set(true)
        }
    }
    
    fun undoLastScan() {
        if (currentLotNumbers.isEmpty()) return
        val lotId = currentLotId ?: return
        val sessionId = currentSession?.id ?: return
        scope.launch {
            val lastRow = app.database.rdNumberDao().getMostRecentForLot(lotId)
            if (lastRow != null) {
                app.database.rdNumberDao().deleteById(lastRow.id)
                currentLotNumbers.remove(lastRow.number)
                allSessionNumbers.remove(lastRow.number)
                Toast.makeText(context, "Removed: ${lastRow.number}", Toast.LENGTH_SHORT).show()
                if (currentLotNumbers.isEmpty()) {
                    app.database.scanLotDao().deleteIfEmpty(lotId)
                    app.database.scanSessionDao().setActiveLotId(sessionId, null)
                    currentLotId = null
                }
            }
        }
    }

    suspend fun finalizeSession(session: ScanSession) {
        currentSessionId = null
        currentLotId = null
        if (totalLotsInSession > 0) {
            val displayNumber = app.database.scanSessionDao().getNextDisplayNumber()
            app.database.scanSessionDao().endSession(
                id = session.id,
                endTime = System.currentTimeMillis(),
                totalLots = totalLotsInSession,
                totalRdNumbers = allSessionNumbers.size,
                displayNumber = displayNumber
            )
            // Promote the subtree to DIRTY + enqueue the push worker (Phase 2 T2.4).
            // Wrapped in try/catch because markSessionForSync requires DeviceSettings
            // to be populated — a user who finalized a session before completing
            // first-run setup (edge case after MIGRATION_5_6) would otherwise see
            // finalize fail. Toast + continue: the data is saved, sync just waits.
            try {
                app.syncRepository.markSessionForSync(session.id)
                app.syncScheduler.enqueuePush()
            } catch (e: Exception) {
                // Narrowed from Throwable so Errors (OOM etc.) propagate
                // cleanly instead of being silently swallowed (oracle
                // regression W1).
                android.util.Log.w("RDScannerScreen", "finalize: deferred sync enqueue", e)
            }
            runCatching {
                val stamped = app.database.scanSessionDao().getSessionById(session.id)
                val settings = app.database.deviceSettingsDao().get()
                app.database.syncEventDao().insert(
                    SyncEvent(
                        occurredAt = System.currentTimeMillis(),
                        type = SyncEventType.LOCAL_SESSION_FINALIZED,
                        sessionCloudId = stamped?.cloudId,
                        originDeviceCloudId = settings?.deviceCloudId,
                        originDeviceName = settings?.deviceName,
                        originOperatorName = settings?.operatorName,
                        payloadSummary = "finalized Session #$displayNumber " +
                            "($totalLotsInSession LOT${if (totalLotsInSession == 1) "" else "s"})"
                    )
                )
            }.onFailure {
                android.util.Log.w("RDScannerScreen", "local sync_event insert failed", it)
            }
            Toast.makeText(
                context,
                "Session #$displayNumber saved! $totalLotsInSession LOTs, ${allSessionNumbers.size} RD numbers",
                Toast.LENGTH_LONG
            ).show()
            onNavigateToSession(session.id)
        } else {
            app.database.scanSessionDao().deleteById(session.id)
            Toast.makeText(context, "Empty session discarded", Toast.LENGTH_SHORT).show()
            onNavigateBack()
        }
    }

    fun executePostSave(action: PostSave) {
        scope.launch {
            when (action) {
                PostSave.Continue -> {
                    Toast.makeText(context, "LOT ${currentLotNumber - 1} saved! Ready for next LOT.", Toast.LENGTH_SHORT).show()
                    delay(300)
                    scanningEnabledRef.set(true)
                    isScanningRef.set(true)
                }
                PostSave.EndSession -> {
                    currentSession?.let { finalizeSession(it) }
                }
            }
        }
    }

    fun finishCurrentLot(alsoEndSession: Boolean = false) {
        val lotId = currentLotId
        if (lotId == null || currentLotNumbers.isEmpty()) {
            if (alsoEndSession) {
                scope.launch { currentSession?.let { finalizeSession(it) } }
            } else {
                Toast.makeText(context, "No RD numbers in current LOT", Toast.LENGTH_SHORT).show()
            }
            return
        }

        scanningEnabledRef.set(false)
        isScanningRef.set(false)

        scope.launch {
            val session = currentSession ?: return@launch
            val savedLotNumber = currentLotNumber
            val savedTimestamp = System.currentTimeMillis()

            app.database.scanSessionDao().setActiveLotId(session.id, null)
            totalLotsInSession++
            currentLotNumber++
            currentLotId = null
            currentLotNumbers.clear()

            lotReviewLotId = lotId
            lotReviewLotNumber = savedLotNumber
            lotReviewLotTimestamp = savedTimestamp
            pendingPostSave = if (alsoEndSession) PostSave.EndSession else PostSave.Continue
            lotReviewLoading = true
            lotReviewBaseRows = LotReviewBuilder.build(app, lotId, savedTimestamp)
            lotReviewEdits = emptyMap()
            lotReviewLoading = false
            showLotReviewScreen = true
        }
    }

    fun endSession() {
        scope.launch {
            val session = currentSession ?: return@launch
            val lotId = currentLotId
            if (lotId != null && currentLotNumbers.isNotEmpty()) {
                val savedLotNumber = currentLotNumber
                val savedTimestamp = System.currentTimeMillis()
                app.database.scanSessionDao().setActiveLotId(session.id, null)
                totalLotsInSession++
                currentLotNumber++
                currentLotId = null
                currentLotNumbers.clear()

                lotReviewLotId = lotId
                lotReviewLotNumber = savedLotNumber
                lotReviewLotTimestamp = savedTimestamp
                pendingPostSave = PostSave.EndSession
                lotReviewLoading = true
                lotReviewBaseRows = LotReviewBuilder.build(app, lotId, savedTimestamp)
                lotReviewEdits = emptyMap()
                lotReviewLoading = false
                showLotReviewScreen = true
            } else {
                if (lotId != null) {
                    app.database.scanLotDao().deleteIfEmpty(lotId)
                    app.database.scanSessionDao().setActiveLotId(session.id, null)
                    currentLotId = null
                }
                finalizeSession(session)
            }
        }
    }

    fun discardSession() {
        scope.launch {
            currentSession?.let { session ->
                // FK cascade (v4) handles scan_lots → rd_numbers, but we delete
                // explicitly first for fail-safety against partial cascade.
                app.database.rdNumberDao().deleteForSession(session.id)
                app.database.scanLotDao().deleteLotsForSession(session.id)
                app.database.scanSessionDao().deleteById(session.id)
                Toast.makeText(context, "Session discarded", Toast.LENGTH_SHORT).show()
            }
            currentSessionId = null
            currentLotId = null
            onNavigateBack()
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            barcodeScanner.close()
            try {
                toneGenerator?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        
                        val preview = Preview.Builder()
                            .build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }
                        
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { analysis ->
                                analysis.setAnalyzer(executor) { imageProxy ->
                                    // Use atomic refs for thread-safe reads
                                    if (scanningEnabledRef.get() && isScanningRef.get() && pendingValueRef.get() == null) {
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null) {
                                            val image = InputImage.fromMediaImage(
                                                mediaImage,
                                                imageProxy.imageInfo.rotationDegrees
                                            )
                                            barcodeScanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    barcodes.firstOrNull()?.rawValue?.let { value ->
                                                        // Double-check and atomically set
                                                        if (scanningEnabledRef.get() && 
                                                            isScanningRef.compareAndSet(true, false) && 
                                                            pendingValueRef.compareAndSet(null, value)) {
                                                            // Trigger UI update on main thread
                                                            scanTrigger++
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener { imageProxy.close() }
                                        } else {
                                            imageProxy.close()
                                        }
                                    } else {
                                        imageProxy.close()
                                    }
                                }
                            }
                        
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )
        
        // Modern scanner overlay
        ScannerOverlay()

        // Dim camera when a dialog is open
        if (anyDialogOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
            )
        }

        // Top gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
        )
        
        // Top Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showEndSessionDialog = true },
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "RD Book Scanner",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Text(
                        text = "Session #${currentSession?.id ?: "-"}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    )
                }
                
                IconButton(
                    onClick = {
                        camera?.cameraControl?.enableTorch(!isFlashOn)
                        isFlashOn = !isFlashOn
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isFlashOn) PrimaryOrange else Color.White.copy(alpha = 0.15f),
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash",
                        tint = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Live Stats
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LiveStatItem(
                    value = currentLotNumber.toString(),
                    label = "Current LOT",
                    color = PrimaryOrange
                )
                LiveStatItem(
                    value = currentLotNumbers.size.toString(),
                    label = "In LOT",
                    color = AccentMint
                )
                LiveStatItem(
                    value = totalLotsInSession.toString(),
                    label = "Saved LOTs",
                    color = AccentCoral
                )
                LiveStatItem(
                    value = allSessionNumbers.size.toString(),
                    label = "Total RD",
                    color = WarningAmber
                )
            }

            AnimatedVisibility(
                visible = liveLotTotal.hasContent,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    LiveLotTotalChip(total = liveLotTotal)
                }
            }
        }
        
        // Scan Feedback
        AnimatedVisibility(
            visible = lastScanFeedback != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 100.dp)
        ) {
            lastScanFeedback?.let { feedback ->
                ScanFeedbackCard(feedback)
            }
        }
        
        // Bottom Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            // Recently scanned list
            if (currentLotNumbers.isNotEmpty()) {
                Text(
                    text = "Recently Scanned (LOT $currentLotNumber)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    reverseLayout = false
                ) {
                    items(currentLotNumbers, key = { it }) { number ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = number,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Undo / Finish / End sit in a weight(1f) × 3 row; the
                // middle button text used to wrap mid-word as "Finis-h
                // LOT" on narrow phones. All three labels are now
                // single-word and force maxLines=1 so the row stays
                // crisp regardless of screen width. Disabled alphas on
                // Undo were also raised so the button stays readable
                // against the dimmed camera background.
                Button(
                    onClick = { undoLastScan() },
                    enabled = currentLotNumbers.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.22f),
                        disabledContentColor = Color.White.copy(alpha = 0.78f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Undo", maxLines = 1)
                }

                Button(
                    onClick = { showFinishLotDialog = true },
                    enabled = currentLotNumbers.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentMint,
                        disabledContainerColor = AccentMint.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save", maxLines = 1)
                }

                Button(
                    onClick = { showEndSessionDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCoral
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("End", maxLines = 1)
                }
            }
        }
        
        // Finish LOT Dialog - Custom styled
        if (showFinishLotDialog) {
            Dialog(
                onDismissRequest = { /* require explicit choice */ },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        // Title
                        Text(
                            text = "Save LOT $currentLotNumber",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Content
                        Text(
                            text = "LOT $currentLotNumber has ${currentLotNumbers.size} RD numbers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Buttons
                        Button(
                            onClick = {
                                showFinishLotDialog = false
                                finishCurrentLot(alsoEndSession = false)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentMint),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save LOT & Continue Scanning", fontWeight = FontWeight.SemiBold)
                        }
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Button(
                            onClick = {
                                showFinishLotDialog = false
                                finishCurrentLot(alsoEndSession = true)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save LOT & End Session", fontWeight = FontWeight.SemiBold)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TextButton(
                            onClick = { showFinishLotDialog = false },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Cancel", color = TextSecondary)
                        }
                    }
                }
            }
        }
        
        // End Session Dialog - Custom styled
        if (showEndSessionDialog) {
            Dialog(
                onDismissRequest = { /* require explicit choice */ },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        // Title
                        Text(
                            text = "End Session?",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Summary
                        val hasSavedData = totalLotsInSession > 0
                        val hasUnsavedData = currentLotNumbers.isNotEmpty()
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardBackground, RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            if (hasSavedData || hasUnsavedData) {
                                Row {
                                    Text("Saved LOTs: ", color = TextSecondary)
                                    Text("$totalLotsInSession", fontWeight = FontWeight.Bold, color = AccentMint)
                                }
                                Row {
                                    Text("Total RD Numbers: ", color = TextSecondary)
                                    Text("${allSessionNumbers.size - currentLotNumbers.size}", fontWeight = FontWeight.Bold, color = PrimaryOrange)
                                }
                                if (hasUnsavedData) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier
                                            .background(WarningAmber.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = WarningAmber,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            "Current LOT has ${currentLotNumbers.size} unsaved numbers",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = WarningAmber
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    "No data has been scanned yet.\nThe session will be discarded.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Buttons based on state
                        if (hasUnsavedData) {
                            Button(
                                onClick = {
                                    showEndSessionDialog = false
                                    finishCurrentLot(alsoEndSession = true)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Save Current LOT & End", fontWeight = FontWeight.SemiBold)
                            }
                            
                            Spacer(modifier = Modifier.height(10.dp))
                            
                            Button(
                                onClick = {
                                    showEndSessionDialog = false
                                    endSession()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Discard Current LOT & End", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    showEndSessionDialog = false
                                    endSession()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hasSavedData) PrimaryOrange else AccentCoral
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (hasSavedData) "End Session" else "Discard & Exit",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TextButton(
                            onClick = { showEndSessionDialog = false },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Continue Scanning", color = AccentMint, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (showLotReviewScreen && !lotReviewLoading && lotReviewRows.isNotEmpty()) {
            LotReviewScreen(
                mode = LotReviewMode.FreshScan,
                lotNumber = lotReviewLotNumber,
                rows = lotReviewRows,
                onUpdateRow = { rowId, newSelected ->
                    lotReviewEdits = lotReviewEdits + (rowId to newSelected)
                },
                onConfirm = { edits ->
                    showLotReviewScreen = false
                    lotReviewLotId = null
                    lotReviewEdits = emptyMap()
                    val sessionId = currentSession?.id
                    scope.launch {
                        if (sessionId != null) {
                            // Persister owns updateMonths + markDirty +
                            // lastPaidThrough writeback + LOCAL_DEFAULTER_EDIT
                            // insert + push enqueue + tombstone guard. The
                            // outcome is exhaustively handled below so a
                            // future variant breaks this call site at
                            // compile time.
                            when (val outcome = LotReviewPersister.persist(app, sessionId, edits)) {
                                is LotReviewOutcome.Saved -> {
                                    Toast.makeText(
                                        context,
                                        "Saved ${outcome.editedCount} entr${if (outcome.editedCount == 1) "y" else "ies"}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is LotReviewOutcome.NoChanges -> {
                                    // Operator confirmed without changes;
                                    // skip the toast — silence is the
                                    // appropriate UX for a no-op confirm.
                                }
                                is LotReviewOutcome.SessionTombstoned -> {
                                    // Cannot happen in the fresh-scan
                                    // path (we JUST finalized this
                                    // session locally — no race window
                                    // for a remote tombstone to land
                                    // before save). Logged defensively
                                    // so a future code path that
                                    // shrinks the gap surfaces it.
                                    android.util.Log.w(
                                        "RDScannerScreen",
                                        "fresh-scan persist returned SessionTombstoned — unexpected"
                                    )
                                }
                                is LotReviewOutcome.Error -> {
                                    android.util.Log.e(
                                        "RDScannerScreen",
                                        "LotReview persist failed",
                                        outcome.cause
                                    )
                                    Toast.makeText(
                                        context,
                                        "Save failed — retrying in background",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        pendingPostSave?.let { executePostSave(it) }
                        pendingPostSave = null
                    }
                },
                onDiscard = {
                    showLotReviewScreen = false
                    lotReviewLotId = null
                    lotReviewEdits = emptyMap()
                    pendingPostSave?.let { executePostSave(it) }
                    pendingPostSave = null
                },
                onRescanLot = {
                    val lotId = lotReviewLotId
                    val session = currentSession
                    // Tear down the in-review LOT entirely and return the
                    // operator to the scanner at the SAME LOT number per
                    // locked Q3 ("re-attempt LOT N, not advance to N+1").
                    // Prior LOTs in this session are untouched — only the
                    // rd_numbers + scan_lots rows for THIS lotId go away.
                    showLotReviewScreen = false
                    lotReviewLotId = null
                    lotReviewEdits = emptyMap()
                    lotReviewBaseRows = emptyList()
                    pendingPostSave = null
                    scope.launch {
                        if (lotId != null) {
                            app.database.rdNumberDao().deleteForLot(lotId)
                            // deleteIfEmpty (vs unconditional delete) guards
                            // against the partial-delete race where another
                            // device's realtime insert lands between the
                            // rdNumberDao.deleteForLot call and this one.
                            app.database.scanLotDao().deleteIfEmpty(lotId)
                        }
                        if (session != null) {
                            app.database.scanSessionDao().setActiveLotId(session.id, null)
                        }
                        totalLotsInSession = (totalLotsInSession - 1).coerceAtLeast(0)
                        currentLotNumber = (currentLotNumber - 1).coerceAtLeast(1)
                        currentLotId = null
                        // Capture BEFORE clearing so we can also remove these
                        // numbers from the session-level dedup set. Without
                        // this, the next scan of the same RD fires the
                        // "already scanned in this session" duplicate guard
                        // at line ~388 and the operator cannot rescan at all.
                        val deletedNumbers = currentLotNumbers.toList()
                        currentLotNumbers.clear()
                        allSessionNumbers.removeAll(deletedNumbers.toSet())
                        // Also drop cached monthlyAmount entries for
                        // the discarded LOT so a future rescan re-
                        // resolves from rd_accounts (catches any
                        // amount edits that landed between the
                        // original scan + the rescan).
                        lotAmountCache.keys.removeAll(deletedNumbers.toSet())
                        scanningEnabledRef.set(true)
                        isScanningRef.set(true)
                        Toast.makeText(
                            context,
                            "LOT ${currentLotNumber} ready — rescan now",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        if (showResumeDialog) {
            val (lotCount, scanCount) = resumeSummary ?: (0 to 0)
            ResumeSessionDialog(
                lotCount = lotCount,
                scanCount = scanCount,
                onResume = {
                    val pending = sessionPendingResume
                    showResumeDialog = false
                    sessionPendingResume = null
                    resumeSummary = null
                    if (pending != null) {
                        scope.launch { adoptSession(pending) }
                    } else {
                        scope.launch { startFreshSession() }
                    }
                },
                onDiscard = {
                    val pending = sessionPendingResume
                    showResumeDialog = false
                    sessionPendingResume = null
                    resumeSummary = null
                    scope.launch {
                        if (pending != null) {
                            app.database.rdNumberDao().deleteForSession(pending.id)
                            app.database.scanLotDao().deleteLotsForSession(pending.id)
                            app.database.scanSessionDao().deleteById(pending.id)
                        }
                        currentSessionId = null
                        currentLotId = null
                        startFreshSession()
                    }
                }
            )
        }
    }
}

private sealed class PostSave {
    data object Continue : PostSave()
    data object EndSession : PostSave()
}

private val PostSaveSaver: Saver<PostSave?, String> = Saver(
    save = { value ->
        when (value) {
            PostSave.Continue -> "C"
            PostSave.EndSession -> "E"
            null -> ""
        }
    },
    restore = { token ->
        when (token) {
            "C" -> PostSave.Continue
            "E" -> PostSave.EndSession
            else -> null
        }
    }
)

@Composable
private fun ScannerOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanner")
    val scanLineProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLine"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val cornerLength = 45.dp.toPx()
                val cornerRadius = 14.dp.toPx()
                val strokeWidth = 3.dp.toPx()
                
                val squareSize = minOf(size.width, size.height) * 0.6f
                val left = (size.width - squareSize) / 2
                val top = (size.height - squareSize) / 2 - 50.dp.toPx()
                
                val cornerColor = Color.White
                
                // Draw corners - Top-left
                drawLine(cornerColor, Offset(left, top + cornerLength), Offset(left, top + cornerRadius), strokeWidth, StrokeCap.Round)
                drawArc(cornerColor, 180f, 90f, false, Offset(left, top), Size(cornerRadius * 2, cornerRadius * 2), style = Stroke(strokeWidth, cap = StrokeCap.Round))
                drawLine(cornerColor, Offset(left + cornerRadius, top), Offset(left + cornerLength, top), strokeWidth, StrokeCap.Round)
                
                // Top-right
                drawLine(cornerColor, Offset(left + squareSize - cornerLength, top), Offset(left + squareSize - cornerRadius, top), strokeWidth, StrokeCap.Round)
                drawArc(cornerColor, 270f, 90f, false, Offset(left + squareSize - cornerRadius * 2, top), Size(cornerRadius * 2, cornerRadius * 2), style = Stroke(strokeWidth, cap = StrokeCap.Round))
                drawLine(cornerColor, Offset(left + squareSize, top + cornerRadius), Offset(left + squareSize, top + cornerLength), strokeWidth, StrokeCap.Round)
                
                // Bottom-left
                drawLine(cornerColor, Offset(left, top + squareSize - cornerLength), Offset(left, top + squareSize - cornerRadius), strokeWidth, StrokeCap.Round)
                drawArc(cornerColor, 90f, 90f, false, Offset(left, top + squareSize - cornerRadius * 2), Size(cornerRadius * 2, cornerRadius * 2), style = Stroke(strokeWidth, cap = StrokeCap.Round))
                drawLine(cornerColor, Offset(left + cornerRadius, top + squareSize), Offset(left + cornerLength, top + squareSize), strokeWidth, StrokeCap.Round)
                
                // Bottom-right
                drawLine(cornerColor, Offset(left + squareSize - cornerLength, top + squareSize), Offset(left + squareSize - cornerRadius, top + squareSize), strokeWidth, StrokeCap.Round)
                drawArc(cornerColor, 0f, 90f, false, Offset(left + squareSize - cornerRadius * 2, top + squareSize - cornerRadius * 2), Size(cornerRadius * 2, cornerRadius * 2), style = Stroke(strokeWidth, cap = StrokeCap.Round))
                drawLine(cornerColor, Offset(left + squareSize, top + squareSize - cornerRadius), Offset(left + squareSize, top + squareSize - cornerLength), strokeWidth, StrokeCap.Round)
                
                // Scan line with orange color
                val scanLineY = top + (squareSize * scanLineProgress)
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, PrimaryOrange, PrimaryOrange, Color.Transparent),
                        startX = left,
                        endX = left + squareSize
                    ),
                    start = Offset(left + 15.dp.toPx(), scanLineY),
                    end = Offset(left + squareSize - 15.dp.toPx(), scanLineY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                
                // Glow effect
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, PrimaryOrange.copy(alpha = 0.2f), Color.Transparent),
                        startY = scanLineY - 20.dp.toPx(),
                        endY = scanLineY + 20.dp.toPx()
                    ),
                    topLeft = Offset(left + 15.dp.toPx(), scanLineY - 20.dp.toPx()),
                    size = Size(squareSize - 30.dp.toPx(), 40.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
    )
}

@Composable
private fun LiveStatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = color
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

internal data class LiveLotTotal(
    val verifiedRupees: Int,
    val unverifiedCount: Int
) {
    val isAllVerified: Boolean get() = unverifiedCount == 0
    val isOverLimit: Boolean get() = verifiedRupees > LOT_TOTAL_LIMIT_RUPEES
    val hasContent: Boolean get() = verifiedRupees > 0 || unverifiedCount > 0
}

@Composable
private fun LiveLotTotalChip(total: LiveLotTotal) {
    // Coral when either condition fails — unverified rows OR verified
    // total over cap. QC-H HIGH (Flow 3): operator scanning 5 × ₹5,000
    // verified accounts previously saw green even though verified =
    // ₹25,000 > limit. Now the chip flips to coral as soon as either
    // signal fires.
    val accent = if (total.isAllVerified && !total.isOverLimit) AccentMint else AccentCoral
    // Hoist NumberFormat to a remembered instance (was allocated per
    // recompose — QC-F HIGH). Locale.getDefault() at remember time is
    // fine for this app: locale changes trigger a configuration change
    // which recreates the activity + composition.
    val formatter = remember {
        java.text.NumberFormat.getNumberInstance(java.util.Locale.getDefault())
    }
    val rupeeText = formatter.format(total.verifiedRupees.toLong())
    // Single solid-dark background (Black @ 0.85f) so white text
    // stays legible against any camera content — bright outdoor (white
    // walls, sky), dim indoor, or shifting reflections. The previous
    // layered Black@0.30 + accent@0.22 combo composited to ~0.46
    // luminance over a white camera feed (~1.4:1 white-text contrast
    // = unreadable, WCAG BLOCKER). Solid 0.85 composites to ~0.15
    // luminance worst-case (5.25:1) and ~0.06 best-case (9.5:1) —
    // both pass AA. Accent now lives only in the indicator dot
    // (line below), so the operator still gets color-coded state
    // signaling without relying on the chip background.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(accent, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (total.isAllVerified) {
                stringResource(R.string.scanner_lot_total_verified, rupeeText)
            } else {
                stringResource(
                    R.string.scanner_lot_total_with_unverified,
                    rupeeText,
                    total.unverifiedCount
                )
            },
            style = MaterialTheme.typography.labelLarge.copy(
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

private sealed class ScanFeedback {
    data class Success(val number: String) : ScanFeedback()
    data class Duplicate(val message: String) : ScanFeedback()
    data class Invalid(val message: String) : ScanFeedback()
}

@Composable
private fun ScanFeedbackCard(feedback: ScanFeedback) {
    val bgColor: Color
    val icon: ImageVector
    val title: String
    val subtitle: String
    
    when (feedback) {
        is ScanFeedback.Success -> {
            bgColor = SuccessGreen
            icon = Icons.Default.CheckCircle
            title = "Scanned!"
            subtitle = feedback.number
        }
        is ScanFeedback.Duplicate -> {
            bgColor = WarningAmber
            icon = Icons.Default.Close
            title = "Duplicate"
            subtitle = feedback.message
        }
        is ScanFeedback.Invalid -> {
            bgColor = ErrorRed
            icon = Icons.Default.Close
            title = "Invalid"
            subtitle = feedback.message
        }
    }
    
    Card(
        modifier = Modifier.padding(horizontal = 32.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.9f),
                        fontFamily = if (feedback is ScanFeedback.Success) FontFamily.Monospace else null
                    )
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen(
    title: String,
    message: String,
    buttonText: String,
    onButtonClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(PrimaryOrange.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                    tint = PrimaryOrange
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = TextSecondary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onButtonClick,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(buttonText, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}
