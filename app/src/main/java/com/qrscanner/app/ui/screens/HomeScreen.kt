package com.qrscanner.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.cloud.CloudSessionStatus
import com.qrscanner.app.data.sync.SyncPillState
import com.qrscanner.app.data.sync.SyncSummary
import com.qrscanner.app.ui.components.BellIcon
import com.qrscanner.app.ui.components.RecentChangesBanner
import com.qrscanner.app.ui.components.SyncHistorySheet
import com.qrscanner.app.ui.components.SyncStatusPill
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeLight
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onNavigateToScanner: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToAddAccounts: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToHowItWorks: () -> Unit,
    onNavigateToAppInfo: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val completedSessions by app.database.scanSessionDao().getCompletedSessions().collectAsStateWithLifecycle(initialValue = emptyList())

    // Pill state: SyncRepository.summaryFlow is now the single source of truth
    // for (state, pendingCount). It combines live Room count + lifecycle state
    // server-side (see SyncRepository.summaryFlow KDoc). HomeScreen only adds
    // the auth-overlay (NOT_SIGNED_IN/INITIALIZING) which depends on
    // CloudClient.sessionStatus the repository can't see.
    val sessionStatus by app.cloudClient.sessionStatus.collectAsStateWithLifecycle(
        initialValue = CloudSessionStatus.Initializing
    )
    val repoSummary by app.syncRepository.summaryFlow.collectAsStateWithLifecycle(
        initialValue = SyncSummary(
            state = SyncPillState.INITIALIZING,
            pendingCount = 0,
            lastSuccessfulPushAt = null,
            lastSuccessfulPullAt = null,
            lastErrorMessage = null
        )
    )
    val displayedSummary = remember(repoSummary, sessionStatus) {
        when (sessionStatus) {
            is CloudSessionStatus.NotAuthenticated,
            is CloudSessionStatus.RefreshFailure -> repoSummary.copy(state = SyncPillState.NOT_SIGNED_IN)
            is CloudSessionStatus.Initializing -> repoSummary.copy(state = SyncPillState.INITIALIZING)
            is CloudSessionStatus.Authenticated -> repoSummary
        }
    }
    
    val todayFormatter = remember { SimpleDateFormat("yyyyMMdd", Locale.getDefault()) }
    val displayDateFormatter = remember { SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()) }
    
    val today = todayFormatter.format(Date())
    val todaySessions = completedSessions.filter { 
        todayFormatter.format(Date(it.startTime)) == today 
    }
    val todayLots = todaySessions.sumOf { it.totalLots }
    val todayRdNumbers = todaySessions.sumOf { it.totalRdNumbers }
    
    // Soft peach-to-white-to-peach gradient under everything. Tokens
    // derived from the brand palette so a future palette swap doesn't
    // leave stale hex literals behind. AccentGold alpha 0.06 ~ FFF8F0;
    // PrimaryOrangeLight alpha 0.12 ~ FFF0E5. Surface center pin
    // ensures the cards in the middle of the scroll always have a
    // clean white anchor regardless of scroll position.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        com.qrscanner.app.ui.theme.AccentGold.copy(alpha = 0.06f),
                        com.qrscanner.app.ui.theme.SurfaceWhite,
                        com.qrscanner.app.ui.theme.PrimaryOrangeLight.copy(alpha = 0.12f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            val scope = rememberCoroutineScope()
            val deviceSettings by app.database.deviceSettingsDao().observe()
                .collectAsStateWithLifecycle(initialValue = null)
            val ownDeviceCloudId = deviceSettings?.deviceCloudId
            // Clock-skew guard: if device clock was ahead and NTP
            // corrected backward, lastBannerSeenAt could be in the
            // future. observeEventsSince(future) would return zero
            // even when real events exist, stranding the bell badge
            // at 0. Clamp to current wall-clock so the watermark is
            // always reachable by events.
            val storedBannerSeenAt = deviceSettings?.lastBannerSeenAt ?: 0L
            val bannerSeenAt = remember(storedBannerSeenAt) {
                storedBannerSeenAt.coerceAtMost(System.currentTimeMillis())
            }
            val rawRecentEvents by app.database.syncEventDao()
                .observeEventsSince(since = bannerSeenAt, limit = 20)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            // Banner + badge unread count exclude LOCAL_* events: the user
            // doesn't need a notification telling them what they just did
            // (the action's own UI already confirmed it). LOCAL_* still
            // appears in the full bell history so the operator can scroll
            // back through their own timeline.
            val recentEvents = remember(rawRecentEvents) {
                rawRecentEvents.filterNot { event ->
                    event.type == com.qrscanner.app.data.SyncEventType.LOCAL_SESSION_FINALIZED ||
                        event.type == com.qrscanner.app.data.SyncEventType.LOCAL_ACCOUNTS_ADDED ||
                        event.type == com.qrscanner.app.data.SyncEventType.LOCAL_DEFAULTER_EDIT
                }
            }
            val allRecentEvents by app.database.syncEventDao()
                .observeRecentEvents(limit = 100)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            var showHistorySheet by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SyncStatusPill(
                    summary = displayedSummary,
                    onTap = {
                        val s = displayedSummary.state
                        if (s == SyncPillState.SCHEMA_MISSING ||
                            s == SyncPillState.ERROR ||
                            s == SyncPillState.PENDING) {
                            scope.launch {
                                try { app.syncScheduler.enqueuePush() } catch (_: Throwable) {}
                                try { app.syncScheduler.enqueuePull() } catch (_: Throwable) {}
                                Toast.makeText(context, "Retrying…", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
                BellIcon(
                    unreadCount = recentEvents.size,
                    onTap = { showHistorySheet = true }
                )
                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            val showBanner = displayedSummary.state != SyncPillState.NOT_SIGNED_IN &&
                recentEvents.isNotEmpty()
            if (showBanner) {
                Spacer(modifier = Modifier.height(8.dp))
                RecentChangesBanner(
                    events = recentEvents,
                    onDismiss = {
                        scope.launch {
                            app.database.deviceSettingsDao()
                                .updateLastBannerSeenAt(System.currentTimeMillis())
                        }
                    },
                    onOpenHistory = {
                        scope.launch {
                            app.database.deviceSettingsDao()
                                .updateLastBannerSeenAt(System.currentTimeMillis())
                        }
                        onNavigateToHistory()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (showHistorySheet) {
                SyncHistorySheet(
                    events = allRecentEvents,
                    ownDeviceCloudId = ownDeviceCloudId,
                    onDismiss = {
                        showHistorySheet = false
                        scope.launch {
                            app.database.deviceSettingsDao()
                                .updateLastBannerSeenAt(System.currentTimeMillis())
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // App Logo
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .shadow(16.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(PrimaryOrange, PrimaryOrangeLight)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(45.dp),
                    tint = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "RD Book Scanner",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
            
            Text(
                text = "Scan & Manage RD Account QR Codes",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Today's Stats Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Today's Progress",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = TextSecondary
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatItem(
                            value = todaySessions.size.toString(),
                            label = "Sessions",
                            color = PrimaryOrange
                        )
                        StatDivider()
                        StatItem(
                            value = todayLots.toString(),
                            label = "LOTs",
                            color = AccentMint
                        )
                        StatDivider()
                        StatItem(
                            value = todayRdNumbers.toString(),
                            label = "RD Numbers",
                            color = AccentCoral
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Main Action - Scan RD Books
            MainActionCard(
                icon = Icons.Default.QrCodeScanner,
                title = "Scan RD Books",
                subtitle = "Start scanning RD account QR codes",
                gradientColors = listOf(PrimaryOrange, PrimaryOrangeLight),
                onClick = onNavigateToScanner
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Each card has a distinct accent color so the three roles
            // read at a glance — Add (orange) / Accounts (mint) /
            // Sessions (coral). Don't add a 4th card here without
            // dropping to a 2-row 2x2 grid; mixing 3 + 1 looks broken.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SecondaryActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.PersonAddAlt1,
                    title = "Add",
                    subtitle = "New account",
                    accentColor = PrimaryOrange,
                    onClick = onNavigateToAddAccounts
                )

                SecondaryActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.People,
                    title = "Accounts",
                    subtitle = "Browse & QR",
                    accentColor = AccentMint,
                    onClick = onNavigateToAccounts
                )

                SecondaryActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.History,
                    title = "Sessions",
                    subtitle = "View history",
                    accentColor = AccentCoral,
                    onClick = onNavigateToHistory
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Info Links Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextLinkButton(
                    icon = Icons.AutoMirrored.Filled.Help,
                    text = "How It Works",
                    color = WarningAmber,
                    onClick = onNavigateToHowItWorks
                )
                
                Text(
                    text = "  •  ",
                    color = TextSecondary.copy(alpha = 0.3f)
                )
                
                TextLinkButton(
                    icon = Icons.Default.Info,
                    text = "App Info",
                    color = TextSecondary,
                    onClick = onNavigateToAppInfo
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = color
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(40.dp)
            .background(com.qrscanner.app.ui.theme.TextTertiary.copy(alpha = 0.4f))
    )
}

@Composable
private fun MainActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(gradientColors))
                .padding(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = Color.White
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryActionCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scale"
    )
    
    Card(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = accentColor
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1
            )

            // minLines=2 locks every tile to the same visual height
            // regardless of whether its subtitle wraps. Without this,
            // "New account" wraps to two lines on narrow phones while
            // "Browse & QR" and "View history" don't, leaving the Add
            // tile visibly taller than its siblings.
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                minLines = 2,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun TextLinkButton(
    icon: ImageVector,
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // Tertiary-action motion: a 16dp text link wants crisp acknowledgement,
    // not the playful spring used on primary cards. StiffnessHigh + no
    // bounce keeps the press feel precise so the smallest tap targets
    // don't out-animate the loudest ones (inverted motion hierarchy is
    // the cardinal sin of taste — primary actions should be the most
    // expressive, not tertiary links).
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "linkScale"
    )

    Row(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .heightIn(min = 44.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = color
        )
    }
}
