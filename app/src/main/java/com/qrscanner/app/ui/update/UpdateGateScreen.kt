package com.qrscanner.app.ui.update

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qrscanner.app.BuildConfig
import com.qrscanner.app.ui.theme.GradientPeach
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeDark
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.TextTertiary
import com.qrscanner.app.ui.theme.WarningAmber

/**
 * Force-update gate rendered above every other screen while the
 * MainActivity update-check flow says a newer release exists. Back
 * gesture is intentionally swallowed via [BackHandler]. Only two
 * exits: (a) operator downloads the update and the OS installer
 * relaunches the app on the new versionCode, (b) operator kills the
 * process from the task switcher (their choice — nothing stops that
 * short of an on-device MDM which is out of scope). The re-launched
 * process re-runs the check and gate-loops until the OS install
 * lands.
 *
 * Four visible states drive the CTA:
 *   Idle         -> "Download & install" button.
 *   NeedsPerm    -> "Grant permission" button deep-linking to
 *                   Settings > Install unknown apps for this app.
 *   Downloading  -> LinearProgressIndicator + "Please wait" text.
 *   ReadyToInstall -> "Install now" button (caller launches system
 *                     installer). Rarely visible because we auto-
 *                     launch the installer once the download lands.
 */
sealed class UpdateGateState {
    object Idle : UpdateGateState()
    object NeedsPermission : UpdateGateState()
    data class Downloading(val progressBytes: Long, val totalBytes: Long) : UpdateGateState()
    object ReadyToInstall : UpdateGateState()
    data class Error(val message: String) : UpdateGateState()
}

@Composable
fun UpdateGateScreen(
    newVersionName: String,
    newVersionCode: Int,
    apkSizeBytes: Long,
    changelog: String,
    state: UpdateGateState,
    onPrimaryAction: () -> Unit
) {
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    val currentVersion = "v${BuildConfig.VERSION_NAME}"
    val nextVersion = "v$newVersionName"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(GradientPeach, SurfaceWhite, GradientPeach))
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(
                        color = PrimaryOrange.copy(alpha = 0.15f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = PrimaryOrangeDark,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Update required",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "A newer version is available. Please install it to continue.",
                color = TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    VersionRow(label = "You have", value = currentVersion, accent = TextTertiary)
                    Spacer(modifier = Modifier.height(10.dp))
                    VersionRow(label = "Latest", value = nextVersion, accent = PrimaryOrangeDark)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CloudDownload,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Download size: ${Formatter.formatShortFileSize(context, apkSizeBytes)}",
                            color = TextTertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            if (changelog.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "What's new",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = changelog.trim(),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            when (state) {
                is UpdateGateState.Idle -> {
                    PrimaryCta(
                        label = "Download & install",
                        icon = Icons.Filled.CloudDownload,
                        onClick = onPrimaryAction
                    )
                }
                is UpdateGateState.NeedsPermission -> {
                    PermissionBanner()
                    Spacer(modifier = Modifier.height(12.dp))
                    PrimaryCta(
                        label = "Grant permission",
                        icon = Icons.Filled.Settings,
                        onClick = onPrimaryAction
                    )
                }
                is UpdateGateState.Downloading -> {
                    DownloadingRow(state.progressBytes, state.totalBytes)
                }
                is UpdateGateState.ReadyToInstall -> {
                    PrimaryCta(
                        label = "Install now",
                        icon = Icons.Filled.SystemUpdate,
                        onClick = onPrimaryAction
                    )
                }
                is UpdateGateState.Error -> {
                    ErrorBanner(state.message)
                    Spacer(modifier = Modifier.height(12.dp))
                    PrimaryCta(
                        label = "Try again",
                        icon = Icons.Filled.CloudDownload,
                        onClick = onPrimaryAction
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "The app cannot be used on this version.",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label.uppercase(),
            color = TextTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PrimaryCta(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryOrange,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DownloadingRow(progress: Long, total: Long) {
    val fraction = if (total > 0) (progress.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = PrimaryOrangeDark,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Downloading update...",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (total > 0) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = PrimaryOrange,
                trackColor = PrimaryOrange.copy(alpha = 0.15f)
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = PrimaryOrange,
                trackColor = PrimaryOrange.copy(alpha = 0.15f)
            )
        }
    }
}

@Composable
private fun PermissionBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "One-time permission needed",
                color = WarningAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Android needs you to allow this app to install updates. Tap the button, toggle 'Allow from this source' on, then come back.",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Update failed",
                color = WarningAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
