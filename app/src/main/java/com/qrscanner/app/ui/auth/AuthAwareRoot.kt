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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.ContextCompat
import com.qrscanner.app.QRScannerApp
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
                            signInError = e.toUserMessage()
                        } catch (e: Exception) {
                            signInError = e.message ?: "unknown error"
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
                            val cloudId = UUID.randomUUID().toString()
                            val nowIso = IsoTime.fromEpochMillis(System.currentTimeMillis())
                            app.database.deviceSettingsDao().updateIdentity(
                                deviceCloudId = cloudId,
                                deviceName = deviceName,
                                operatorName = operatorName,
                                ownerId = ownerId
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
                            firstRunError = e.toUserMessage()
                            android.util.Log.w("AuthAwareRoot", "first-run cloud push failed", e)
                        } catch (e: Exception) {
                            firstRunError = e.message ?: "Couldn't save device. Try again."
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
            .background(BackgroundWhite),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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

private fun CloudException.toUserMessage(): String = when (this) {
    is CloudException.Network -> "No network — try again when you're online."
    is CloudException.InvalidCredentials -> "Email or password incorrect."
    is CloudException.AuthExpired -> "Session expired. Sign in again."
    is CloudException.NotConfigured -> "Cloud sync not configured."
    is CloudException.Server -> "Server error ($status). Try again in a moment."
    is CloudException.Conflict -> message ?: "Conflict during sign-in."
    is CloudException.SchemaMissing -> "Cloud database setup pending — see SHIP_READY.md."
    is CloudException.Unknown -> message ?: "Unknown error."
}

private const val SPINNER_TIMEOUT_MS = 600L
