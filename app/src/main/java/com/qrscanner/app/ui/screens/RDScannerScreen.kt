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
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.data.RdNumber
import com.qrscanner.app.data.ScanLot
import com.qrscanner.app.data.ScanSession
import com.qrscanner.app.data.isValidRdNumber
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SuccessGreen
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
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
    
    // Session state
    var currentSession by remember { mutableStateOf<ScanSession?>(null) }
    var currentLotNumber by remember { mutableIntStateOf(1) }
    var totalLotsInSession by remember { mutableIntStateOf(0) }
    val currentLotNumbers = remember { mutableStateListOf<String>() }
    val allSessionNumbers = remember { mutableStateListOf<String>() }
    
    // Camera state
    var isFlashOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    
    // Thread-safe scanning flags using atomic references
    val scanningEnabledRef = remember { AtomicBoolean(true) }
    val isScanningRef = remember { AtomicBoolean(true) }
    val pendingValueRef = remember { AtomicReference<String?>(null) }
    
    // UI state
    var showEndSessionDialog by remember { mutableStateOf(false) }
    var showFinishLotDialog by remember { mutableStateOf(false) }
    var lastScanFeedback by remember { mutableStateOf<ScanFeedback?>(null) }
    
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
    
    // Initialize session
    LaunchedEffect(Unit) {
        val session = ScanSession()
        val sessionId = app.database.scanSessionDao().insert(session)
        currentSession = session.copy(id = sessionId)
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
                        currentLotNumbers.add(0, cleanValue)
                        allSessionNumbers.add(cleanValue)
                        lastScanFeedback = ScanFeedback.Success(cleanValue)
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                        } catch (e: Exception) { /* ignore */ }
                    }
                }
                
                // Clear pending value
                pendingValueRef.set(null)
                
                // Resume scanning after short delay
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
    
    // Clear feedback after delay
    LaunchedEffect(lastScanFeedback) {
        if (lastScanFeedback != null) {
            delay(2000)
            lastScanFeedback = null
        }
    }
    
    fun undoLastScan() {
        if (currentLotNumbers.isNotEmpty()) {
            val removed = currentLotNumbers.removeAt(0)
            allSessionNumbers.remove(removed)
            Toast.makeText(context, "Removed: $removed", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun finishCurrentLot(alsoEndSession: Boolean = false) {
        if (currentLotNumbers.isEmpty()) {
            if (alsoEndSession) {
                // End session even with empty current lot
                scope.launch {
                    currentSession?.let { session ->
                        if (totalLotsInSession > 0) {
                            val displayNumber = app.database.scanSessionDao().getNextDisplayNumber()
                            app.database.scanSessionDao().endSession(
                                id = session.id,
                                endTime = System.currentTimeMillis(),
                                totalLots = totalLotsInSession,
                                totalRdNumbers = allSessionNumbers.size,
                                displayNumber = displayNumber
                            )
                            Toast.makeText(context, "Session #$displayNumber saved!", Toast.LENGTH_LONG).show()
                            onNavigateToSession(session.id)
                        } else {
                            // Delete empty session
                            app.database.scanSessionDao().deleteById(session.id)
                            Toast.makeText(context, "Session discarded (no data)", Toast.LENGTH_SHORT).show()
                            onNavigateBack()
                        }
                    }
                }
            } else {
                Toast.makeText(context, "No RD numbers in current LOT", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        // Pause scanning while saving
        scanningEnabledRef.set(false)
        isScanningRef.set(false)
        
        scope.launch {
            currentSession?.let { session ->
                val lot = ScanLot(
                    sessionId = session.id,
                    lotNumber = currentLotNumber
                )
                val lotId = app.database.scanLotDao().insert(lot)
                val rdNumberEntities = currentLotNumbers.reversed().mapIndexed { index, number ->
                    RdNumber(lotId = lotId, number = number, position = index)
                }
                app.database.rdNumberDao().insertAll(rdNumberEntities)

                totalLotsInSession++
                currentLotNumber++
                currentLotNumbers.clear()
                
                if (alsoEndSession) {
                    // End the session with display number
                    val displayNumber = app.database.scanSessionDao().getNextDisplayNumber()
                    app.database.scanSessionDao().endSession(
                        id = session.id,
                        endTime = System.currentTimeMillis(),
                        totalLots = totalLotsInSession,
                        totalRdNumbers = allSessionNumbers.size,
                        displayNumber = displayNumber
                    )
                    Toast.makeText(
                        context,
                        "Session #$displayNumber saved! $totalLotsInSession LOTs, ${allSessionNumbers.size} RD numbers",
                        Toast.LENGTH_LONG
                    ).show()
                    onNavigateToSession(session.id)
                } else {
                    Toast.makeText(context, "LOT ${currentLotNumber - 1} saved! Ready for next LOT.", Toast.LENGTH_SHORT).show()
                    // Re-enable scanning for next lot
                    delay(300)
                    scanningEnabledRef.set(true)
                    isScanningRef.set(true)
                }
            }
        }
    }
    
    fun endSession() {
        scope.launch {
            // Save current lot if not empty
            if (currentLotNumbers.isNotEmpty()) {
                currentSession?.let { session ->
                    val lot = ScanLot(
                        sessionId = session.id,
                        lotNumber = currentLotNumber
                    )
                    val lotId = app.database.scanLotDao().insert(lot)
                    val rdNumberEntities = currentLotNumbers.reversed().mapIndexed { index, number ->
                        RdNumber(lotId = lotId, number = number, position = index)
                    }
                    app.database.rdNumberDao().insertAll(rdNumberEntities)
                    totalLotsInSession++
                }
            }
            
            // End or delete session based on whether it has data
            currentSession?.let { session ->
                if (totalLotsInSession > 0) {
                    val displayNumber = app.database.scanSessionDao().getNextDisplayNumber()
                    app.database.scanSessionDao().endSession(
                        id = session.id,
                        endTime = System.currentTimeMillis(),
                        totalLots = totalLotsInSession,
                        totalRdNumbers = allSessionNumbers.size,
                        displayNumber = displayNumber
                    )
                    Toast.makeText(
                        context,
                        "Session #$displayNumber saved! $totalLotsInSession LOTs, ${allSessionNumbers.size} RD numbers",
                        Toast.LENGTH_LONG
                    ).show()
                    onNavigateToSession(session.id)
                } else {
                    // Delete empty session
                    app.database.scanSessionDao().deleteById(session.id)
                    Toast.makeText(context, "Empty session discarded", Toast.LENGTH_SHORT).show()
                    onNavigateBack()
                }
            }
        }
    }
    
    fun discardSession() {
        scope.launch {
            currentSession?.let { session ->
                app.database.scanLotDao().deleteLotsForSession(session.id)
                app.database.scanSessionDao().deleteById(session.id)
                Toast.makeText(context, "Session discarded", Toast.LENGTH_SHORT).show()
            }
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
    
    // Use rememberUpdatedState to get always-current scanTrigger setter
    val currentScanTrigger by rememberUpdatedState(scanTrigger)
    
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
                // Undo Button
                Button(
                    onClick = { undoLastScan() },
                    enabled = currentLotNumbers.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.05f),
                        disabledContentColor = Color.White.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Undo")
                }
                
                // Finish LOT Button
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
                    Text("Finish LOT")
                }
                
                // End Session Button
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
                    Text("End")
                }
            }
        }
        
        // Finish LOT Dialog - Custom styled
        if (showFinishLotDialog) {
            Dialog(onDismissRequest = { showFinishLotDialog = false }) {
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
                            text = "Finish LOT $currentLotNumber",
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
            Dialog(onDismissRequest = { showEndSessionDialog = false }) {
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
                                .background(Color(0xFFF5F5F5), RoundedCornerShape(12.dp))
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
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            "⚠️ Current LOT has ${currentLotNumbers.size} unsaved numbers",
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
    }
}

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
