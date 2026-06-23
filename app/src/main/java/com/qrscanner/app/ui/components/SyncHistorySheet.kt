package com.qrscanner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.R
import com.qrscanner.app.data.SyncEvent
import com.qrscanner.app.data.SyncEventType
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber

/**
 * Modal bottom sheet showing the last ~100 sync events. Surfaces from
 * the BellIcon tap on HomeScreen. Each row prefers the operator name
 * (denormalized into SyncEvent.originOperatorName at record time) and
 * falls back to the device name, matching the in-app banner's actor
 * resolution.
 *
 * Auto-dismiss on swipe-down + close-button tap both route through the
 * same [onDismiss] so the caller can bump lastBannerSeenAt once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistorySheet(
    events: List<SyncEvent>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nowMillis = remember { System.currentTimeMillis() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceWhite,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextSecondary.copy(alpha = 0.3f))
            )
        }
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HeaderRow(eventCount = events.size)
            if (events.isEmpty()) {
                EmptyHistory()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 520.dp)
                ) {
                    items(events, key = { it.id }) { event ->
                        HistoryRow(event = event, nowMillis = nowMillis)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(eventCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PrimaryOrange.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = PrimaryOrange,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sync_history_title),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )
            Text(
                text = if (eventCount == 0) {
                    stringResource(R.string.sync_history_subtitle_empty)
                } else {
                    stringResource(R.string.sync_history_subtitle, eventCount)
                },
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 36.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(PrimaryOrange.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.EventNote,
                contentDescription = null,
                tint = PrimaryOrange.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.sync_history_empty_title),
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sync_history_empty_body),
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun HistoryRow(event: SyncEvent, nowMillis: Long) {
    val actorLabel = resolveActorLabel(event)
    val actorTint = actorTintFor(event)
    val actorIcon = actorIconFor(event)
    val actionText = actionTextFor(event)
    val timestampLabel = formatRelativeTime(event.occurredAt, nowMillis)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(actorTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = actorIcon,
                contentDescription = null,
                tint = actorTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = actorLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    ),
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timestampLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextSecondary,
                        fontFamily = FontFamily.SansSerif
                    )
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = actionText,
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
    }
}

@Composable
private fun resolveActorLabel(event: SyncEvent): String = when {
    event.originDeviceCloudId == null ->
        stringResource(R.string.sync_history_row_actor_portal)
    !event.originOperatorName.isNullOrBlank() -> event.originOperatorName
    !event.originDeviceName.isNullOrBlank() -> event.originDeviceName
    else -> stringResource(R.string.sync_history_row_actor_other_phone)
}

@Composable
private fun actionTextFor(event: SyncEvent): String {
    val sessionTail = event.sessionCloudId
        ?.takeLast(6)
        ?.uppercase()
        ?: "—"
    return when (event.type) {
        SyncEventType.REMOTE_SESSION_FINALIZED ->
            stringResource(R.string.sync_history_row_session_finalized, sessionTail)
        SyncEventType.REMOTE_DEFAULTER_EDIT,
        SyncEventType.PORTAL_DEFAULTER_EDIT ->
            stringResource(R.string.sync_history_row_defaulter_edit, sessionTail)
        SyncEventType.REMOTE_SESSION_DELETED ->
            stringResource(R.string.sync_history_row_session_deleted, sessionTail)
    }
}

private fun actorIconFor(event: SyncEvent): ImageVector = when {
    event.originDeviceCloudId == null -> Icons.Default.Computer
    !event.originOperatorName.isNullOrBlank() -> Icons.Default.Person
    else -> Icons.Default.Smartphone
}

private fun actorTintFor(event: SyncEvent): Color = when (event.type) {
    SyncEventType.REMOTE_SESSION_FINALIZED -> AccentMint
    SyncEventType.REMOTE_DEFAULTER_EDIT, SyncEventType.PORTAL_DEFAULTER_EDIT -> WarningAmber
    SyncEventType.REMOTE_SESSION_DELETED -> ErrorRed
}

@Composable
private fun formatRelativeTime(timestamp: Long, now: Long): String {
    val deltaMs = (now - timestamp).coerceAtLeast(0L)
    val minutes = deltaMs / 60_000
    val hours = deltaMs / 3_600_000
    val days = deltaMs / 86_400_000
    return when {
        minutes < 2 -> stringResource(R.string.sync_history_relative_just_now)
        minutes < 60 -> stringResource(R.string.sync_history_relative_minutes, minutes.toInt())
        hours < 24 -> stringResource(R.string.sync_history_relative_hours, hours.toInt())
        days == 1L -> stringResource(R.string.sync_history_relative_yesterday)
        else -> stringResource(R.string.sync_history_relative_days, days.toInt())
    }
}
