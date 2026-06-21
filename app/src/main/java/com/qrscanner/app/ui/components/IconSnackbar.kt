package com.qrscanner.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.TextPrimary

/**
 * Three semantic snackbar kinds — Success (PrimaryOrange, used for
 * confirm-of-positive-action), Info (TextPrimary surface, used for
 * neutral notices like 'locked, contact owner'), Error (ErrorRed,
 * used for destructive-action-completed feedback). Per oracle
 * bg_437db025 finding: locked-row notice using PrimaryOrange collided
 * semantically with success snackbars (both warm/saturated).
 */
enum class IconSnackbarKind { Success, Info, Error }

/**
 * Shared Snackbar host that supports a leading Material Icon + brand
 * tint, used in place of plain Toast where a visual cue carries
 * meaning (CSV-locked rows, auto-reactivate notice, etc.).
 */
data class IconSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Long,
    val icon: ImageVector,
    val kind: IconSnackbarKind = IconSnackbarKind.Success
) : SnackbarVisuals

@Composable
fun IconSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onAction: () -> Unit = {}
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        val visuals = data.visuals as? IconSnackbarVisuals
        val container = when (visuals?.kind) {
            IconSnackbarKind.Error -> ErrorRed
            IconSnackbarKind.Info -> TextPrimary
            else -> PrimaryOrange
        }
        Snackbar(
            modifier = Modifier.padding(12.dp),
            containerColor = container,
            contentColor = Color.White,
            actionContentColor = Color.White,
            action = visuals?.actionLabel?.let {
                {
                    TextButton(onClick = {
                        data.performAction()
                        onAction()
                    }) {
                        Text(it, color = Color.White)
                    }
                }
            }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (visuals?.icon != null) {
                    Icon(
                        imageVector = visuals.icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(data.visuals.message)
            }
        }
    }
}

suspend fun SnackbarHostState.showIconSnackbar(
    message: String,
    icon: ImageVector,
    kind: IconSnackbarKind = IconSnackbarKind.Success,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Long
): SnackbarResult = showSnackbar(
    IconSnackbarVisuals(
        message = message,
        icon = icon,
        actionLabel = actionLabel,
        kind = kind,
        duration = duration
    )
)
