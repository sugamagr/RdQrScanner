package com.qrscanner.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.data.sync.SyncPillState
import com.qrscanner.app.data.sync.SyncSummary
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber

/**
 * Compact sync indicator anchored to the top-right of [HomeScreen].
 *
 * Three-segment shape: leading colored dot + state label + optional
 * trailing count. The state colors follow the established semantic
 * palette: AccentMint (synced), WarningAmber (pending), PrimaryOrange
 * (syncing in flight), ErrorRed (error / not signed in).
 *
 * Tapping the pill routes per [SyncPillState]:
 *  - NOT_SIGNED_IN → sign-in flow
 *  - ERROR → sync diagnostics
 *  - everything else → settings landing
 *
 * Spec §15.5 (visual contract), §18.5 (observability).
 */
@Composable
fun SyncStatusPill(
    summary: SyncSummary,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pillScale"
    )

    val dotColor by animateColorAsState(
        targetValue = dotColorFor(summary.state),
        animationSpec = tween(220),
        label = "pillDot"
    )

    val label = labelFor(summary)

    Box(
        modifier = modifier
            .scale(pressScale)
            .background(
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            AnimatedContent(
                targetState = label,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "pillLabel"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                )
            }
        }
    }
}

private fun dotColorFor(state: SyncPillState): Color = when (state) {
    SyncPillState.NOT_SIGNED_IN -> ErrorRed
    SyncPillState.INITIALIZING -> TextSecondary.copy(alpha = 0.6f)
    SyncPillState.SYNCED -> AccentMint
    SyncPillState.PENDING -> WarningAmber
    SyncPillState.SYNCING -> PrimaryOrange
    SyncPillState.ERROR -> ErrorRed
}

private fun labelFor(summary: SyncSummary): String = when (summary.state) {
    SyncPillState.NOT_SIGNED_IN -> "Tap to sign in"
    SyncPillState.INITIALIZING -> "Connecting…"
    SyncPillState.SYNCED -> "All synced"
    SyncPillState.PENDING -> "${summary.pendingCount} pending"
    SyncPillState.SYNCING -> "Syncing…"
    SyncPillState.ERROR -> "Sync error · tap"
}
