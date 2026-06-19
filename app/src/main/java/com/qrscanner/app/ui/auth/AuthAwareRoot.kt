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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

    val sessionStatus by app.cloudClient.sessionStatus.collectAsState(
        initial = CloudSessionStatus.Initializing
    )
    val deviceSettings by app.database.deviceSettingsDao().observe()
        .collectAsState(initial = null)

    var signInLoading by rememberSaveable { mutableStateOf(false) }
    var signInError by rememberSaveable { mutableStateOf<String?>(null) }
    var firstRunSaving by rememberSaveable { mutableStateOf(false) }
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
                        } catch (e: CloudException) {
                            signInError = e.toUserMessage()
                        } catch (e: Throwable) {
                            signInError = e.message ?: "unknown error"
                        } finally {
                            signInLoading = false
                        }
                    }
                }
            )
            AuthStage.FirstRunSetup -> FirstRunSetupScreen(
                isSaving = firstRunSaving,
                defaultDeviceName = Build.MODEL ?: "",
                onContinue = { deviceName, operatorName ->
                    val ownerId = (sessionStatus as? CloudSessionStatus.Authenticated)
                        ?.session?.ownerId ?: return@FirstRunSetupScreen
                    firstRunSaving = true
                    scope.launch {
                        try {
                            val cloudId = UUID.randomUUID().toString()
                            val nowIso = IsoTime.fromEpochMillis(System.currentTimeMillis())
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
                            app.database.deviceSettingsDao().updateIdentity(
                                deviceCloudId = cloudId,
                                deviceName = deviceName,
                                operatorName = operatorName,
                                ownerId = ownerId
                            )
                        } catch (e: CloudException) {
                            // Network errors during first-run setup are recoverable —
                            // we keep the user on this screen so they can retry.
                            // Future: surface inline. For now log + leave isSaving false.
                            android.util.Log.w("AuthAwareRoot", "first-run setup failed", e)
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
    is CloudException.AuthExpired -> "Email or password incorrect."
    is CloudException.Server -> "Server error ($status). Try again in a moment."
    is CloudException.Conflict -> message ?: "Conflict during sign-in."
    is CloudException.Unknown -> message ?: "Unknown error."
}

private const val SPINNER_TIMEOUT_MS = 600L
