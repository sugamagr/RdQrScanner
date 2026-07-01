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
 *   check() -> [checkedResult] becomes UpToDate | Available.
 *   If Available, MainActivity renders UpdateGateScreen instead of
 *   AuthAwareRoot until the OS installer relaunches this process on
 *   the new versionCode.
 *
 * Explicitly single-shot: check() runs once per Activity creation.
 * Rotation replays the check (cheap; GitHub caches releases/latest
 * aggressively at their CDN so a 200 arrives in <100 ms warm).
 */
class UpdateGateController {

    var checkedResult by mutableStateOf<UpdateChecker.UpdateResult?>(null)
        private set

    var gateState by mutableStateOf<UpdateGateState>(UpdateGateState.Idle)
        private set

    fun launchCheck(scope: CoroutineScope) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check() }
            checkedResult = result
            if (result is UpdateChecker.UpdateResult.Available) {
                gateState = UpdateGateState.Idle
            }
        }
    }

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
                                versionName = available.versionName
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
