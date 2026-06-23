package com.qrscanner.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qrscanner.app.R
import com.qrscanner.app.ui.theme.AccentCoral
import com.qrscanner.app.ui.theme.TextSecondary

/**
 * Bell icon button used on HomeScreen next to the SyncStatusPill.
 *
 * Renders a 44dp WCAG-compliant round white chip with the Material
 * bell glyph. When [unreadCount] > 0 a coral badge with the count
 * (capped at 9+) overlays the top-right corner with a soft scale-in
 * animation. Press feedback mirrors SyncStatusPill (94% spring) so
 * the two controls feel like a single hardware row when adjacent.
 *
 * The bell is always visible per product decision — the badge is the
 * unread indicator. Tap surfaces the SyncHistorySheet.
 */
@Composable
fun BellIcon(
    unreadCount: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "bellScale"
    )

    val a11yDescription = if (unreadCount > 0) {
        stringResource(R.string.bell_a11y_description_unread, unreadCount)
    } else {
        stringResource(R.string.bell_a11y_description)
    }

    Box(
        modifier = modifier
            .scale(pressScale)
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
            .background(
                color = Color.White.copy(alpha = 0.92f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap
            )
            .semantics {
                role = Role.Button
                contentDescription = a11yDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Notifications,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(22.dp)
        )
        AnimatedVisibility(
            visible = unreadCount > 0,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp, end = 4.dp)
        ) {
            BellBadge(count = unreadCount)
        }
    }
}

@Composable
private fun BellBadge(count: Int) {
    val text = if (count > 9) "9+" else count.toString()
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
            .background(AccentCoral, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                lineHeight = 10.sp
            )
        )
    }
}
