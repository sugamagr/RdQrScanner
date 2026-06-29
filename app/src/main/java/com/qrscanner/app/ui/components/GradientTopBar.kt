package com.qrscanner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.PrimaryOrangeLight

/**
 * Orange-gradient top bar with rounded bottom corners, matching the
 * visual chrome used by SessionHistoryScreen exactly. Callers SHOULD
 * have a `.statusBarsPadding()` on the parent Column (mirrors Session
 * History at line 229) — this component intentionally does NOT
 * statusBarsPadding itself so the parent's gradient background (often
 * GradientPeach -> White -> GradientPeach) renders behind the status
 * bar before this bar's rounded edge appears.
 *
 * Caller composes the bar's row content (back button, title, actions)
 * inside [content]. Keeps each screen free to vary its top-row layout
 * (selection mode title swap, bulk action icons) without forking the
 * component.
 *
 * Contrast tradeoff: white text on raw orange gradient is ~2.0-2.5:1,
 * below WCAG AA 3.0:1 for large bold text. Prior version stacked a
 * Black @ 0.50f scrim to pass AA but that produced the brown look the
 * operator rejected. We match SessionHistoryScreen which uses the raw
 * gradient — single-operator product, brand identity wins over AA on
 * this header surface. Body text on white substrate everywhere else
 * stays AA-compliant.
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
            .displayCutoutPadding()
            .padding(16.dp)
            .padding(bottom = 8.dp)
    ) {
        content()
    }
}
