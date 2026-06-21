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

/**
 * Shared Snackbar host that supports a leading Material Icon + brand
 * tint, used in place of plain Toast where a visual cue carries
 * meaning (CSV-locked rows, auto-reactivate notice, etc.).
 *
 * Toast doesn't support icons; this is the replacement contract
 * we agreed to under the no-emoji constraint.
 */
data class IconSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Long,
    val icon: ImageVector,
    val isError: Boolean = false
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
        Snackbar(
            modifier = Modifier.padding(12.dp),
            containerColor = if (visuals?.isError == true) ErrorRed else PrimaryOrange,
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
    actionLabel: String? = null,
    isError: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Long
): SnackbarResult = showSnackbar(
    IconSnackbarVisuals(
        message = message,
        icon = icon,
        actionLabel = actionLabel,
        isError = isError,
        duration = duration
    )
)
