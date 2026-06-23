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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * the BellIcon tap on HomeScreen.
 *
 * The feed mixes REMOTE_* events (recorded by SyncRepository when a
 * pull merges a change from another device or the portal) with LOCAL_*
 * events (recorded by this device's own sync-bound actions: finalize a
 * session, add accounts, edit defaulter months). Both render uniformly
 * — the actor label resolves to "You" when [SyncEvent.originDeviceCloudId]
 * matches [ownDeviceCloudId], otherwise to the operator/device name.
 *
 * Adjacent events with the same (type, origin, session) inside a 60-
 * second window collapse into one row with a `(×N)` suffix so the feed
 * stays readable when a burst of edits hits in rapid succession.
 *
 * Auto-dismiss on swipe-down + close-button tap both route through the
 * same [onDismiss] so the caller can bump lastBannerSeenAt once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistorySheet(
    events: List<SyncEvent>,
    ownDeviceCloudId: String?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val nowMillis = remember { System.currentTimeMillis() }
    val grouped = remember(events) { groupForDisplay(events) }

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
            HeaderRow(eventCount = grouped.size)
            if (grouped.isEmpty()) {
                EmptyHistory()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 520.dp)
                ) {
                    items(grouped, key = { it.first().id }) { group ->
                        HistoryRow(
                            group = group,
                            nowMillis = nowMillis,
                            ownDeviceCloudId = ownDeviceCloudId
                        )
                    }
                }
            }
        }
    }
}

private const val DEDUPE_WINDOW_MS: Long = 60_000L

/**
 * Collapses adjacent same-bucket events inside [DEDUPE_WINDOW_MS] into
 * one display group. A bucket is (type, originDeviceCloudId, sessionCloudId)
 * — same actor, same kind of action, same target session within a one-
 * minute window. The result keeps newest-first ordering so the head of
 * the bottom sheet matches the head of the underlying event list.
 */
private fun groupForDisplay(events: List<SyncEvent>): List<List<SyncEvent>> {
    if (events.isEmpty()) return emptyList()
    val ordered = events.sortedByDescending { it.occurredAt }
    val groups = mutableListOf<MutableList<SyncEvent>>()
    for (event in ordered) {
        val tail = groups.lastOrNull()?.first()
        val sameBucket = tail != null &&
            tail.type == event.type &&
            tail.originDeviceCloudId == event.originDeviceCloudId &&
            tail.sessionCloudId == event.sessionCloudId &&
            (tail.occurredAt - event.occurredAt) <= DEDUPE_WINDOW_MS
        if (sameBucket) {
            groups.last().add(event)
        } else {
            groups += mutableListOf(event)
        }
    }
    return groups
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
private fun HistoryRow(
    group: List<SyncEvent>,
    nowMillis: Long,
    ownDeviceCloudId: String?
) {
    val event = group.first()
    // LOCAL_* rows are inserted only by this device at the moment of the
    // action, so they are always "yours" by construction — independent
    // of whether ownDeviceCloudId / event.originDeviceCloudId are still
    // null (fresh install pre-sign-in, race between insert and DeviceSettings
    // hydration, or a future code path that forgets to denormalize the
    // device id). Falling back to the cloudId match for REMOTE_* /
    // PORTAL_* rows where the type alone can't tell us.
    val isLocalAction = event.type == SyncEventType.LOCAL_SESSION_FINALIZED ||
        event.type == SyncEventType.LOCAL_ACCOUNTS_ADDED ||
        event.type == SyncEventType.LOCAL_DEFAULTER_EDIT
    val isOwn = isLocalAction ||
        (event.originDeviceCloudId != null &&
            event.originDeviceCloudId == ownDeviceCloudId)
    val actorLabel = resolveActorLabel(event, isOwn)
    val actorTint = actorTintFor(event)
    val actorIcon = actorIconFor(event, isOwn)
    val actionText = describeAction(event, group.size)
    val timestampLabel = formatRelativeTime(event.occurredAt, nowMillis)

    // 3dp leading accent strip on own-action rows so the operator can
    // scan-read "mine vs theirs" at a glance without parsing each actor
    // label. Strip is painted via drawBehind (over the SurfaceWhite
    // background, under the row content) and the leading padding is
    // ALWAYS 15dp regardless of isOwn so tile widths stay layout-stable
    // — only the strip appearance differs between own and remote rows.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceWhite)
            .then(
                if (isOwn) {
                    Modifier.drawBehind {
                        drawRect(
                            color = PrimaryOrange,
                            topLeft = Offset.Zero,
                            size = Size(3.dp.toPx(), size.height)
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(start = 15.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timestampLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextSecondary,
                        fontFamily = FontFamily.SansSerif
                    ),
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = actionText,
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun resolveActorLabel(event: SyncEvent, isOwn: Boolean): String = when {
    isOwn -> stringResource(R.string.sync_history_row_actor_you)
    event.originDeviceCloudId == null ->
        stringResource(R.string.sync_history_row_actor_portal)
    !event.originOperatorName.isNullOrBlank() -> event.originOperatorName
    !event.originDeviceName.isNullOrBlank() -> event.originDeviceName
    else -> stringResource(R.string.sync_history_row_actor_other_phone)
}

/**
 * Renders the action body. Prefers the pre-rendered [SyncEvent.payloadSummary]
 * because SyncRepository writes it at insert time with full context
 * ("finalized Session #7 (12 LOTs)", "added 3 accounts") — far more
 * useful than rebuilding from the UUID tail at render time. Falls back
 * to a generic verb when payloadSummary is blank (older rows from a
 * pre-payload build), and appends `(×N)` when the dedupe collapsed
 * multiple rapid-fire events into this group.
 */
@Composable
private fun describeAction(event: SyncEvent, repeatCount: Int): String {
    val base = event.payloadSummary.ifBlank {
        when (event.type) {
            SyncEventType.REMOTE_SESSION_FINALIZED,
            SyncEventType.LOCAL_SESSION_FINALIZED ->
                stringResource(R.string.sync_history_fallback_finalized)
            SyncEventType.REMOTE_DEFAULTER_EDIT,
            SyncEventType.PORTAL_DEFAULTER_EDIT,
            SyncEventType.LOCAL_DEFAULTER_EDIT ->
                stringResource(R.string.sync_history_fallback_defaulter_edit)
            SyncEventType.REMOTE_SESSION_DELETED ->
                stringResource(R.string.sync_history_fallback_deleted)
            SyncEventType.LOCAL_ACCOUNTS_ADDED ->
                stringResource(R.string.sync_history_fallback_accounts_added)
        }
    }
    return if (repeatCount > 1) {
        stringResource(R.string.sync_history_row_repeat_suffix, base, repeatCount)
    } else {
        base
    }
}

private fun actorIconFor(event: SyncEvent, isOwn: Boolean): ImageVector = when {
    isOwn -> Icons.Default.Person
    event.originDeviceCloudId == null -> Icons.Default.Computer
    !event.originOperatorName.isNullOrBlank() -> Icons.Default.Person
    else -> Icons.Default.Smartphone
}

private fun actorTintFor(event: SyncEvent): Color = when (event.type) {
    SyncEventType.REMOTE_SESSION_FINALIZED,
    SyncEventType.LOCAL_SESSION_FINALIZED -> AccentMint
    SyncEventType.REMOTE_DEFAULTER_EDIT,
    SyncEventType.PORTAL_DEFAULTER_EDIT,
    SyncEventType.LOCAL_DEFAULTER_EDIT -> WarningAmber
    SyncEventType.REMOTE_SESSION_DELETED -> ErrorRed
    SyncEventType.LOCAL_ACCOUNTS_ADDED -> PrimaryOrange
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
