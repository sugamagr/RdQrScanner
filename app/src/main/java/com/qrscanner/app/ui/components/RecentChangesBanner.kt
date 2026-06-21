package com.qrscanner.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.R
import com.qrscanner.app.data.SyncEvent
import com.qrscanner.app.data.SyncEventType
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.TextPrimary

/**
 * Compact "what changed while you were away" banner anchored under the
 * sync pill on Home. Renders the recent SyncEvent log per spec §15.5.1:
 * minimal, dismissible, aggregates same-origin same-type events within
 * a 60-second window into a single line.
 *
 * Hides itself entirely when the event list is empty so it never
 * occupies layout space on the no-changes path. AnimatedVisibility
 * gives the appear/dismiss a soft expand/collapse so it doesn't
 * surprise the user mid-scroll.
 */
@Composable
fun RecentChangesBanner(
    events: List<SyncEvent>,
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lines = remember(events, context) { aggregate(events, context) }
    AnimatedVisibility(
        visible = lines.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            PrimaryOrange.copy(alpha = 0.10f),
                            AccentMint.copy(alpha = 0.10f)
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
                .clickable(onClick = onOpenHistory)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = "Recent changes: ${lines.joinToString("; ")}" }
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(PrimaryOrange.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = PrimaryOrange,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val headerText = if (lines.size == 1) {
                        stringResource(R.string.banner_title_one)
                    } else {
                        stringResource(R.string.banner_title_many, lines.size)
                    }
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    for ((index, line) in lines.withIndex()) {
                        if (index > 0) Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                val dismissLabel = stringResource(R.string.banner_dismiss)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = dismissLabel },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Spec §15.5.1 aggregation: same (type, origin) within 60s collapse
 * into one line. Caps output at 3 lines so the banner stays small even
 * after a long catch-up pull.
 */
// All user-facing strings resolved via context.getString so the Hindi
// locale renders correctly. payloadSummary itself remains as written
// at SyncRepository merge time (currently English) — that pre-rendered
// fragment carries displayNumber + count and is out of scope for
// render-time i18n. TODO(post-v1): replace payloadSummary with a
// structured payload + render-time format string for full locale-switch
// safety (requires a Room migration on sync_events).
private fun aggregate(events: List<SyncEvent>, context: Context): List<String> {
    if (events.isEmpty()) return emptyList()
    val ordered = events.sortedByDescending { it.occurredAt }
    val groups = mutableListOf<MutableList<SyncEvent>>()
    for (event in ordered) {
        val tail = groups.lastOrNull()
        val sameBucket = tail != null &&
            tail.first().type == event.type &&
            tail.first().originDeviceCloudId == event.originDeviceCloudId &&
            (tail.first().occurredAt - event.occurredAt) <= AGGREGATION_WINDOW_MS
        if (sameBucket) {
            tail!!.add(event)
        } else {
            groups += mutableListOf(event)
        }
    }
    return groups.take(MAX_LINES).map { describe(it, context) }
}

private fun describe(group: List<SyncEvent>, context: Context): String {
    val first = group.first()
    val origin = first.originLabel(context)
    val n = group.size
    return when (first.type) {
        SyncEventType.REMOTE_SESSION_FINALIZED ->
            if (n == 1) context.getString(R.string.banner_line_session_finalized_one, origin, first.payloadSummary)
            else context.getString(R.string.banner_line_session_finalized_many, origin, n)
        SyncEventType.REMOTE_DEFAULTER_EDIT,
        SyncEventType.PORTAL_DEFAULTER_EDIT ->
            if (n == 1) context.getString(R.string.banner_line_defaulter_edit_one, origin, first.payloadSummary)
            else context.getString(R.string.banner_line_defaulter_edit_many, origin, n)
        SyncEventType.REMOTE_SESSION_DELETED ->
            if (n == 1) context.getString(R.string.banner_line_session_deleted_one, origin, first.payloadSummary)
            else context.getString(R.string.banner_line_session_deleted_many, origin, n)
    }
}

private fun SyncEvent.originLabel(context: Context): String = when {
    originDeviceCloudId == null -> context.getString(R.string.banner_origin_portal)
    !originOperatorName.isNullOrBlank() -> originOperatorName
    !originDeviceName.isNullOrBlank() -> originDeviceName
    else -> context.getString(R.string.banner_origin_another_phone)
}

private const val AGGREGATION_WINDOW_MS: Long = 60_000L
private const val MAX_LINES: Int = 3
