package com.qrscanner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeLight

/**
 * Orange-gradient top bar with rounded bottom corners, matching the
 * visual chrome used by SessionHistoryScreen. The bar extends into the
 * system status bar area (via embedded statusBarsPadding) so the
 * gradient draws under the status bar's translucent overlay; callers
 * should NOT add their own statusBarsPadding to the parent.
 *
 * Caller composes the bar's row content (back button, title, actions)
 * inside [content]. Keeps each screen free to vary its top-row layout
 * (e.g. selection mode swaps title for "N selected" + cancel + bulk
 * action) without forking this component.
 *
 * Contrast: white text on raw PrimaryOrange (#FF9F43) gradient stops
 * is 2.04-2.46:1 — fails WCAG AA. A Black @ 0.50f scrim above the
 * gradient brings the effective substrate luminance down to ≤0.26
 * across both stops, giving white-text contrast ≥3.2:1 (passes AA
 * for large text at 14sp+ bold, which titleMedium-Bold qualifies for).
 * The orange identity stays recognizable — the scrim darkens but
 * doesn't desaturate.
 *
 * displayCutoutPadding handles notched/punch-hole devices in
 * landscape orientation so content never renders under the cutout.
 */
@Composable
fun GradientTopBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .background(
                Brush.linearGradient(listOf(PrimaryOrange, PrimaryOrangeLight))
            )
            .background(Color.Black.copy(alpha = 0.50f))
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        content()
    }
}
