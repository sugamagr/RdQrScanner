package com.qrscanner.app.ui.screens

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
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.cloud.CloudSessionStatus
import com.qrscanner.app.data.sync.SyncPillState
import com.qrscanner.app.data.sync.SyncSummary
import com.qrscanner.app.ui.components.SyncStatusPill
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
    onNavigateToGenerator: () -> Unit,
    onNavigateToHowItWorks: () -> Unit,
    onNavigateToAppInfo: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val completedSessions by app.database.scanSessionDao().getCompletedSessions().collectAsState(initial = emptyList())

    // Sync pill state: derive NOT_SIGNED_IN from CloudClient.sessionStatus
    // because SyncRepository.summaryFlow only emits states reachable during a
    // push (it never transitions to NOT_SIGNED_IN itself).
    val pendingFromDb by app.database.scanSessionDao().observePendingCount()
        .collectAsState(initial = 0)
    val sessionStatus by app.cloudClient.sessionStatus.collectAsState(
        initial = CloudSessionStatus.Initializing
    )
    val repoSummary by app.syncRepository.summaryFlow.collectAsState(
        initial = SyncSummary(
            state = SyncPillState.INITIALIZING,
            pendingCount = pendingFromDb,
            lastSuccessfulPushAt = null,
            lastSuccessfulPullAt = null,
            lastErrorMessage = null
        )
    )
    val displayedSummary = remember(repoSummary, pendingFromDb, sessionStatus) {
        when (sessionStatus) {
            is CloudSessionStatus.NotAuthenticated,
            is CloudSessionStatus.RefreshFailure -> repoSummary.copy(state = SyncPillState.NOT_SIGNED_IN)
            is CloudSessionStatus.Initializing -> repoSummary.copy(state = SyncPillState.INITIALIZING)
            is CloudSessionStatus.Authenticated -> {
                val pillState = when {
                    repoSummary.state == SyncPillState.SYNCING -> SyncPillState.SYNCING
                    repoSummary.state == SyncPillState.ERROR -> SyncPillState.ERROR
                    pendingFromDb > 0 -> SyncPillState.PENDING
                    else -> SyncPillState.SYNCED
                }
                repoSummary.copy(state = pillState, pendingCount = pendingFromDb)
            }
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
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFF8F0),
                        Color(0xFFFFFFFF),
                        Color(0xFFFFF0E5)
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

            SyncStatusPill(
                summary = displayedSummary,
                onTap = { /* T2.10: tap target wired in Phase 5 diagnostics screen */ },
                modifier = Modifier
                    .align(Alignment.End)
            )

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
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp
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
            
            Spacer(modifier = Modifier.height(14.dp))
            
            // Secondary Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SecondaryActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.PictureAsPdf,
                    title = "Generate PDF",
                    subtitle = "Create QR codes",
                    accentColor = AccentMint,
                    onClick = onNavigateToGenerator
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
                    icon = Icons.Default.Help,
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
            .background(Color.Gray.copy(alpha = 0.15f))
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
            
            Spacer(modifier = Modifier.height(10.dp))
            
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
            
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
