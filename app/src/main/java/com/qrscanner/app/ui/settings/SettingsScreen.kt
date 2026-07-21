package com.qrscanner.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.R
import com.qrscanner.app.ui.components.GradientTopBar
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.GradientPeach
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SuccessGreen
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary

/**
 * Settings screen — accessible from the gear icon on Home.
 *
 * Three cards top to bottom:
 *  1. Identity (signed-in email, sign-out)
 *  2. Phone & operator (device name + switch operator action)
 *  3. Sync (open diagnostics)
 *
 * Sync diagnostics is a Phase 5 deliverable; the row routes to a stub
 * placeholder in Wave 3 so the navigation contract is locked even before
 * the screen ships.
 */
/**
 * @param installedVersionName currently-installed app versionName (e.g. "2.0.5") — shown in the About card.
 * @param updateStatus one of "up_to_date" / "available:<name>" / "checking" / "unknown".
 *   The Settings card renders a green tick, an orange "Update available" pill,
 *   a spinner, or nothing respectively. Passing an unknown token defaults to nothing.
 *   Priority-3 SEMANTIC: this is a stringly-typed status because the caller
 *   (Navigation.kt) already collapses UpdateResult + manualCheckInFlight into
 *   the display shape, keeping the composable free of update-domain types.
 * @param onCheckForUpdates fires [UpdateGateController.triggerManualCheck].
 */
@Composable
fun SettingsScreen(
    signedInEmail: String,
    deviceName: String,
    operatorName: String,
    installedVersionName: String,
    updateStatus: String,
    onCheckForUpdates: () -> Unit,
    onBack: () -> Unit,
    onSwitchOperator: () -> Unit,
    onSignOut: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(GradientPeach, Color.White, GradientPeach))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            GradientTopBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_title),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
            SettingsCard {
                IdentityRow(email = signedInEmail)
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentCoral.copy(alpha = 0.12f),
                        contentColor = AccentCoral
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_sign_out),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsCard {
                LabeledValue(
                    icon = Icons.Default.Smartphone,
                    label = stringResource(R.string.settings_device_name),
                    value = deviceName
                )
                Spacer(modifier = Modifier.height(12.dp))
                LabeledValue(
                    icon = Icons.Default.PersonOutline,
                    label = stringResource(R.string.settings_operator_name),
                    value = operatorName
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSwitchOperator,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryOrange.copy(alpha = 0.12f),
                        contentColor = PrimaryOrange
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_switch_operator),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsCard {
                LabeledValue(
                    icon = Icons.Default.SyncAlt,
                    label = stringResource(R.string.settings_sync_status),
                    value = stringResource(R.string.settings_diagnostics)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryOrange,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_diagnostics),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsCard {
                AboutRow(
                    installedVersionName = installedVersionName,
                    updateStatus = updateStatus
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onCheckForUpdates,
                    enabled = updateStatus != "checking",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryOrange.copy(alpha = 0.12f),
                        contentColor = PrimaryOrange,
                        disabledContainerColor = PrimaryOrange.copy(alpha = 0.06f),
                        disabledContentColor = PrimaryOrange.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (updateStatus == "checking") {
                        CircularProgressIndicator(
                            color = PrimaryOrange,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.settings_checking_updates),
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_check_updates),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun AboutRow(installedVersionName: String, updateStatus: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(PrimaryOrange.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = PrimaryOrange,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_app_version_label),
                style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
            )
            Text(
                text = "v$installedVersionName",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            )
        }
        when {
            updateStatus == "up_to_date" -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
            }
            updateStatus.startsWith("available:") -> {
                Box(
                    modifier = Modifier
                        .background(PrimaryOrange, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_update_available_pill),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun IdentityRow(email: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(PrimaryOrange.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PersonOutline,
                contentDescription = null,
                tint = PrimaryOrange,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.settings_signed_in_as, email),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            )
        }
    }
}

@Composable
private fun LabeledValue(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(PrimaryOrange.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PrimaryOrange,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            )
        }
    }
}
