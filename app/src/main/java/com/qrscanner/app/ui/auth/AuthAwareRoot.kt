package com.qrscanner.app.ui.auth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.qrscanner.app.ui.theme.GradientPeach
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeLight
import com.qrscanner.app.ui.theme.TextSecondary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import androidx.core.content.ContextCompat
import com.qrscanner.app.QRScannerApp
import com.qrscanner.app.R
import com.qrscanner.app.cloud.CloudException
import com.qrscanner.app.cloud.CloudSessionStatus
import com.qrscanner.app.cloud.dto.DeviceDto
import com.qrscanner.app.cloud.mappers.IsoTime
import com.qrscanner.app.data.DeviceSettings
import com.qrscanner.app.navigation.QRScannerNavigation
import com.qrscanner.app.ui.theme.BackgroundWhite
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Root composable installed by [com.qrscanner.app.MainActivity].
 *
 * Selects between five surfaces based on auth + first-run state:
 *
 *  1. Initializing — auth state hasn't resolved yet (Supabase SDK is
 *     loading the stored session from EncryptedSharedPreferences).
 *     Renders a centered spinner. Avoids a visible "flash" of the
 *     SignInScreen for users who are already authenticated.
 *
 *  2. SignInScreen — `CloudSessionStatus.NotAuthenticated`.
 *
 *  3. FirstRunSetupScreen — authenticated but
 *     [DeviceSettings.deviceCloudId] is null. Captures device name +
 *     operator, upserts the cloud `devices` row, persists the local
 *     identity.
 *
 *  4. NotificationPermissionScreen — first-run is done but we haven't
 *     asked for POST_NOTIFICATIONS yet (and we're on API 33+ and don't
 *     already have it). One-shot screen; the answer is remembered via
 *     a saveable so a config-change mid-prompt doesn't re-show it.
 *
 *  5. The existing [QRScannerNavigation] — normal app.
 *
 * Transitions use a soft 220ms fade so the user never sees a "blink" on
 * state changes; the spinner stage is the only one with a deliberate
 * delay (max 600ms then we time out and assume NotAuthenticated to
 * avoid spinning forever on broken networks).
 */
@Composable
fun AuthAwareRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as QRScannerApp
    val scope = rememberCoroutineScope()

    if (!app.isCloudConfigured) {
        NotConfiguredScreen()
        return
    }

    val sessionStatus by app.cloudClient.sessionStatus.collectAsStateWithLifecycle(
        initialValue = CloudSessionStatus.Initializing
    )
    val deviceSettings by app.database.deviceSettingsDao().observe()
        .collectAsStateWithLifecycle(initialValue = null)

    // Loading flags use plain `remember` (NOT `rememberSaveable`) so a config
    // change cancels both the coroutine AND its visible spinner. Otherwise the
    // saveable restores `true`, the coroutine is gone, and the UI hangs forever
    // on a dead spinner (oracle round 6 BLOCKER #4).
    var signInLoading by remember { mutableStateOf(false) }
    var signInError by rememberSaveable { mutableStateOf<String?>(null) }
    var firstRunSaving by remember { mutableStateOf(false) }
    var firstRunError by rememberSaveable { mutableStateOf<String?>(null) }
    var notifPromptAnswered by rememberSaveable { mutableStateOf(false) }
    var initializingTimedOut by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sessionStatus) {
        if (sessionStatus is CloudSessionStatus.Initializing) {
            kotlinx.coroutines.delay(SPINNER_TIMEOUT_MS)
            if (sessionStatus is CloudSessionStatus.Initializing) {
                initializingTimedOut = true
            }
        }
    }

    val stage = remember(
        sessionStatus,
        deviceSettings,
        notifPromptAnswered,
        initializingTimedOut
    ) {
        resolveStage(
            sessionStatus = sessionStatus,
            deviceSettings = deviceSettings,
            notifPromptAnswered = notifPromptAnswered,
            initializingTimedOut = initializingTimedOut,
            hasNotificationPermission = hasPostNotificationsPermission(context)
        )
    }

    AnimatedContent(
        targetState = stage,
        transitionSpec = {
            fadeIn(tween(220)) togetherWith fadeOut(tween(220))
        },
        label = "authStage"
    ) { current ->
        when (current) {
            AuthStage.Initializing -> InitializingScreen()
            AuthStage.SignIn -> SignInScreen(
                isLoading = signInLoading,
                errorMessage = signInError,
                onSignIn = { email, password ->
                    signInError = null
                    signInLoading = true
                    scope.launch {
                        try {
                            app.cloudClient.signIn(email, password)
                            // Auto-push after sign-in success so any pre-existing
                            // DIRTY sessions (e.g. historical from a v5→v6
                            // upgrade, or left over from a prior sign-out) flow
                            // to cloud without waiting for the next finalize
                            // (oracle adversarial #6).
                            runCatching { app.syncScheduler.enqueuePush() }
                        } catch (e: CloudException) {
                            signInError = e.toUserMessage(context)
                        } catch (e: Exception) {
                            signInError = e.message ?: context.getString(R.string.auth_error_unknown)
                        } finally {
                            signInLoading = false
                        }
                    }
                }
            )
            AuthStage.FirstRunSetup -> FirstRunSetupScreen(
                isSaving = firstRunSaving,
                errorMessage = firstRunError,
                defaultDeviceName = Build.MODEL ?: "",
                onContinue = { deviceName, operatorName ->
                    val ownerId = (sessionStatus as? CloudSessionStatus.Authenticated)
                        ?.session?.ownerId ?: return@FirstRunSetupScreen
                    firstRunError = null
                    firstRunSaving = true
                    scope.launch {
                        try {
                            // Local-first write order: persist the cloudId + identity
                            // BEFORE pushing to cloud. If the cloud push fails the
                            // user can retry with the same cloudId; if instead we
                            // pushed first and then crashed, retry would generate a
                            // NEW UUID and produce an orphan cloud row that nothing
                            // ever cleans up (oracle round 6 WARNING #8).
                            //
                            // Before generating a fresh UUID, ask the cloud if a
                            // row already exists for (owner_id, device_model,
                            // device_name). If yes, reuse its id so a re-install
                            // on this same handset folds back into the original
                            // device row instead of leaving a phantom. The lookup
                            // is best-effort: a network failure falls through to
                            // a fresh UUID rather than blocking first-run setup,
                            // because the operator has no recourse to "fix" the
                            // network from this screen and a duplicate row is
                            // recoverable but a stuck first-run is not.
                            // R6 oracle bg_0fe42fcd R6-01.
                            val existingCloudId = runCatching {
                                app.cloudClient.findExistingDevice(
                                    ownerId = ownerId,
                                    deviceModel = Build.MODEL,
                                    deviceName = deviceName
                                )?.id
                            }.getOrNull()
                            val cloudId = existingCloudId ?: UUID.randomUUID().toString()
                            val nowIso = IsoTime.fromEpochMillis(System.currentTimeMillis())
                            // Q3=B pre-release uses fallbackToDestructiveMigration which
                            // skips MIGRATION_5_6's device_settings row seed on fresh
                            // installs. updateIdentity()'s UPDATE WHERE id=1 would
                            // silently affect 0 rows, leaving the user stuck on
                            // FirstRunSetupScreen forever. Use upsert (REPLACE) so
                            // the seed-vs-no-seed paths converge.
                            val existing = app.database.deviceSettingsDao().get()
                            app.database.deviceSettingsDao().upsert(
                                (existing ?: DeviceSettings()).copy(
                                    deviceCloudId = cloudId,
                                    deviceName = deviceName,
                                    operatorName = operatorName,
                                    ownerId = ownerId
                                )
                            )
                            app.cloudClient.upsertDevice(
                                DeviceDto(
                                    id = cloudId,
                                    ownerId = ownerId,
                                    deviceName = deviceName,
                                    deviceModel = Build.MODEL,
                                    firstSeenAt = nowIso,
                                    lastSeenAt = nowIso,
                                    appVersion = appVersion(context),
                                    createdAt = nowIso,
                                    updatedAt = nowIso
                                )
                            )
                            // Auto-push after first-run setup so historical DIRTY
                            // sessions (v5→v6 migration backfill) flow to cloud
                            // immediately instead of waiting for the next finalize
                            // (oracle regression W5 + spec §17 contract).
                            runCatching { app.syncScheduler.enqueuePush() }
                        } catch (e: CloudException) {
                            firstRunError = e.toUserMessage(context)
                            android.util.Log.w("AuthAwareRoot", "first-run cloud push failed", e)
                        } catch (e: Exception) {
                            firstRunError = e.message ?: context.getString(R.string.firstrun_save_failed)
                            android.util.Log.w("AuthAwareRoot", "first-run unexpected failure", e)
                        } finally {
                            firstRunSaving = false
                        }
                    }
                }
            )
            AuthStage.NotificationPermission -> NotificationPermissionScreen(
                onPermissionResult = { _ ->
                    notifPromptAnswered = true
                }
            )
            AuthStage.MainApp -> QRScannerNavigation()
        }
    }
}

@Composable
private fun InitializingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(GradientPeach, Color.White, GradientPeach))
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(PrimaryOrange, PrimaryOrangeLight))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QrCode2,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            CircularProgressIndicator(
                color = PrimaryOrange,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Getting things ready…",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
            )
        }
    }
}

/**
 * Shown when SUPABASE_URL / SUPABASE_ANON_KEY are absent from BuildConfig
 * (typically a fresh git clone without local.properties). Renders a clear
 * developer-targeted message instead of crashing on the SDK's bare
 * malformed-URL exception (oracle round 6 BLOCKER #3).
 */
@Composable
private fun NotConfiguredScreen() {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = BackgroundWhite
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Text(
                text = "Cloud sync not configured",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.material3.Text(
                text = "Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties and rebuild.",
                style = MaterialTheme.typography.bodyMedium,
                color = com.qrscanner.app.ui.theme.TextSecondary
            )
        }
    }
}

private enum class AuthStage {
    Initializing,
    SignIn,
    FirstRunSetup,
    NotificationPermission,
    MainApp
}

private fun resolveStage(
    sessionStatus: CloudSessionStatus,
    deviceSettings: DeviceSettings?,
    notifPromptAnswered: Boolean,
    initializingTimedOut: Boolean,
    hasNotificationPermission: Boolean
): AuthStage {
    return when (sessionStatus) {
        is CloudSessionStatus.Initializing -> {
            if (initializingTimedOut) AuthStage.SignIn else AuthStage.Initializing
        }
        is CloudSessionStatus.NotAuthenticated -> AuthStage.SignIn
        is CloudSessionStatus.RefreshFailure -> AuthStage.SignIn
        is CloudSessionStatus.Authenticated -> {
            val needsFirstRun = deviceSettings?.deviceCloudId.isNullOrBlank() ||
                deviceSettings?.deviceName.isNullOrBlank() ||
                deviceSettings.operatorName.isNullOrBlank()
            when {
                needsFirstRun -> AuthStage.FirstRunSetup
                !notifPromptAnswered &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasNotificationPermission -> AuthStage.NotificationPermission
                else -> AuthStage.MainApp
            }
        }
    }
}

private fun hasPostNotificationsPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun appVersion(context: android.content.Context): String? = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (e: Throwable) {
    null
}

// All branches resolve via string resources so Hindi locale (values-hi/strings.xml)
// renders correctly. C5-P5 oracle finding: strings existed in resources but were
// shadowed by hardcoded English here, breaking i18n.
private fun CloudException.toUserMessage(context: Context): String = when (this) {
    is CloudException.Network -> context.getString(R.string.signin_error_no_network)
    is CloudException.InvalidCredentials -> context.getString(R.string.auth_error_invalid_credentials)
    is CloudException.AuthExpired -> context.getString(R.string.auth_error_session_expired)
    is CloudException.NotConfigured -> context.getString(R.string.auth_error_not_configured)
    is CloudException.Server -> context.getString(R.string.auth_error_server, status)
    is CloudException.Conflict -> message ?: context.getString(R.string.auth_error_conflict)
    is CloudException.SchemaMissing -> context.getString(R.string.auth_error_schema_missing)
    is CloudException.Unknown -> message ?: context.getString(R.string.auth_error_unknown)
}

private const val SPINNER_TIMEOUT_MS = 600L
