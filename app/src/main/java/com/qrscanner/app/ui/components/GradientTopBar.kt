package com.qrscanner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.R
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

/**
 * Canonical back-chip + title/subtitle row for header-style screens.
 *
 * Design-system invariant locked here so AddAccountsScreen,
 * AccountHistoryScreen, and any future header-style screen render
 * IDENTICAL chrome — same 44dp back chip (White @ 0.20f circle),
 * same 12dp spacer, same titleMedium 16sp Bold title, same
 * bodySmall 14sp @ 0.85f alpha subtitle.
 *
 * Do NOT inline this layout in individual screens. Every previous
 * copy diverged on subtitle typography (labelSmall 11sp vs bodySmall
 * 14sp vs hardcoded 12sp) or dropped the back chip entirely, and
 * the three "history / accounts / add" screens ended up visibly
 * different at the header level despite being reachable one tap
 * apart. This helper is the single source of truth.
 *
 * Screens with extra right-side controls (bulk-select toolbar,
 * QR pill, filter icon) must instead compose their own Row inside
 * [GradientTopBar] but MUST keep the back-chip + typography ratio
 * to stay visually kin with this helper.
 */
@Composable
fun GradientTopBarHeaderRow(
    title: String,
    subtitle: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
                .size(44.dp)
                .background(Color.White.copy(alpha = 0.20f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.content_desc_back),
                tint = Color.White
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                maxLines = 1
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1
                )
            }
        }
    }
}
