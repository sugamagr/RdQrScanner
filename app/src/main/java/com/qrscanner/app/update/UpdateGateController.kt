package com.qrscanner.app.update

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qrscanner.app.ui.update.UpdateGateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the update-gate lifecycle for one MainActivity instance.
 *
 * State machine consumed by MainActivity.setContent { ... }:
 *   launchCheck() -> [checkedResult] becomes UpToDate | Available.
 *   If Available && isForce, MainActivity renders UpdateGateScreen
 *   over the rest of the app (blocking). If Available && !isForce,
 *   MainActivity renders the app + an overlay UpdateBanner that the
 *   operator can dismiss for the current process lifetime via
 *   [dismissBannerForSession]. Cold relaunch re-shows the banner —
 *   there is no per-version persistence layer because at 30
 *   sessions/year scale the operator relaunches often enough that
 *   the banner reappears well before they forget the update exists.
 *
 * Rotation replays the check via [launchCheck] on Activity recreate.
 * The GitHub response is cached in SharedPreferences for 6h inside
 * [UpdateChecker] so back-to-back checks after rotation do not burn
 * the 60/hr rate-limit budget the office WiFi shares behind NAT.
 *
 * Manual re-check: [triggerManualCheck] bypasses the cache and hits
 * the network (used by the bell-menu "Check for updates" item). The
 * banner dismissal state is reset so a manual check surfaces the
 * banner again even inside the same process.
 */
class UpdateGateController {

    var checkedResult by mutableStateOf<UpdateChecker.UpdateResult?>(null)
        private set

    var gateState by mutableStateOf<UpdateGateState>(UpdateGateState.Idle)
        private set

    /**
     * True while a manual "Check for updates" call is in flight so the
     * bell-menu can render a spinner without racing with the automatic
     * on-launch check. Priority-3 coupling: cleared in the finally
     * block of [triggerManualCheck] so a thrown network error does not
     * leave the UI wedged with a spinning icon.
     */
    var manualCheckInFlight by mutableStateOf(false)
        private set

    /**
     * Last outcome of a MANUAL check so the bell-menu can flash a
     * one-shot toast (`"You're up to date"` vs `"Update available"`).
     * Auto-checks on cold launch do NOT touch this field so their
     * outcome cannot be mistaken for a user-triggered result.
     */
    var lastManualOutcome by mutableStateOf<ManualCheckOutcome?>(null)
        private set

    /**
     * Session-scoped dismissal of the non-blocking banner. Reset on
     * every process boot (Compose state, not persisted) — the
     * conservative default so that ignoring the banner once cannot
     * hide it for weeks. Force updates ignore this flag entirely;
     * a `[FORCE]` release keeps the blocking gate regardless.
     */
    var bannerDismissed by mutableStateOf(false)
        private set

    fun launchCheck(context: Context, scope: CoroutineScope) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(context) }
            checkedResult = result
            if (result is UpdateChecker.UpdateResult.Available) {
                gateState = UpdateGateState.Idle
            }
        }
    }

    /**
     * Bell-menu entry point. Bypasses the SharedPreferences cache so
     * the operator can force a re-check after the office admin pushes
     * a fix. Toggles [manualCheckInFlight] for the calling UI, and
     * clears [bannerDismissed] so a newly-discovered update always
     * surfaces even if the operator had dismissed a prior banner.
     */
    fun triggerManualCheck(context: Context, scope: CoroutineScope) {
        if (manualCheckInFlight) return
        manualCheckInFlight = true
        lastManualOutcome = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    UpdateChecker.check(context, forceRefresh = true)
                }
                checkedResult = result
                bannerDismissed = false
                lastManualOutcome = when (result) {
                    is UpdateChecker.UpdateResult.Available -> ManualCheckOutcome.Available
                    is UpdateChecker.UpdateResult.UpToDate -> ManualCheckOutcome.UpToDate
                }
            } finally {
                manualCheckInFlight = false
            }
        }
    }

    fun dismissBannerForSession() {
        bannerDismissed = true
    }

    fun consumeManualOutcome() {
        lastManualOutcome = null
    }

    enum class ManualCheckOutcome { Available, UpToDate }

    /**
     * Primary CTA click handler. Routes state transitions based on
     * current gateState and OS permission state. Called from
     * UpdateGateScreen's onPrimaryAction.
     *
     * Terminal transition is to launch the system installer, which
     * kills our process during the OS install phase. On post-install
     * relaunch, [launchCheck] returns UpToDate and the gate never
     * renders again.
     */
    fun onPrimaryClicked(
        context: Context,
        scope: CoroutineScope,
        openPermissionSettings: () -> Unit
    ) {
        val available = checkedResult as? UpdateChecker.UpdateResult.Available ?: return

        when (gateState) {
            is UpdateGateState.Downloading -> return

            is UpdateGateState.NeedsPermission -> {
                openPermissionSettings()
                gateState = UpdateGateState.Idle
                return
            }

            else -> {
                if (!UpdateChecker.canInstallPackages(context)) {
                    gateState = UpdateGateState.NeedsPermission
                    return
                }

                gateState = UpdateGateState.Downloading(0L, available.apkSizeBytes)
                scope.launch {
                    try {
                        val apk = withContext(Dispatchers.IO) {
                            UpdateChecker.downloadApk(
                                context = context,
                                apkUrl = available.apkUrl,
                                versionName = available.versionName,
                                expectedSizeBytes = available.apkSizeBytes
                            )
                        }
                        gateState = UpdateGateState.ReadyToInstall
                        UpdateChecker.launchInstaller(context, apk)
                    } catch (e: Exception) {
                        Log.w(TAG, "update flow failed", e)
                        gateState = UpdateGateState.Error(
                            e.message ?: "Could not download the update. Check your connection and try again."
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "UpdateGateController"
    }
}
