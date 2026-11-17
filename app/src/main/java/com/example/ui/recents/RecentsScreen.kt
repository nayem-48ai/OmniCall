package com.example.ui.recents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.CallDirection
import com.example.domain.model.CallSourceType
import com.example.ui.components.ContactAvatar
import com.example.ui.theme.SignalConnected
import com.example.ui.theme.SignalRecording
import com.example.ui.viewmodel.CallHistoryDetail
import com.example.ui.viewmodel.EnrichedCallLogItem
import com.example.ui.viewmodel.RecentsFilter
import com.example.ui.viewmodel.RecentsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecentsScreen(
    uiState: RecentsUiState,
    onSelectFilter: (RecentsFilter) -> Unit,
    onOpenCallHistory: (String) -> Unit,
    onCloseCallHistory: () -> Unit,
    onCallBack: (String) -> Unit,
    onEditInKeypad: (String) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recents",
                    style = MaterialTheme.typography.headlineMedium
                )
                if (uiState.items.isNotEmpty()) {
                    TextButton(
                        onClick = onClearAll,
                        modifier = Modifier.testTag("recents_clear_all_button")
                    ) {
                        Text("Clear all")
                    }
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(bottom = 10.dp)
            ) {
                items(RecentsFilter.entries) { filter ->
                    FilterChip(
                        selected = uiState.filter == filter,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.label, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag("recents_filter_${filter.name.lowercase()}")
                    )
                }
            }

            if (uiState.items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No recent calls yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap any entry to inspect call history, or tap Call to redial.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.items, key = { it.log.id }) { entry ->
                        CallLogCard(
                            entry = entry,
                            onInspectHistory = { onOpenCallHistory(entry.log.remoteHandle) },
                            onCallBack = { onCallBack(entry.log.remoteHandle) },
                            onEditInKeypad = { onEditInKeypad(entry.log.remoteHandle) },
                            onDelete = { onDeleteItem(entry.log.id) }
                        )
                    }
                }
            }
        }
    }

    uiState.selectedHistory?.let { history ->
        CallHistoryBottomSheet(
            detail = history,
            onCallBack = {
                onCloseCallHistory()
                onCallBack(history.remoteHandle)
            },
            onEditInKeypad = {
                onCloseCallHistory()
                onEditInKeypad(history.remoteHandle)
            },
            onDeleteEntry = onDeleteItem,
            onDismiss = onCloseCallHistory
        )
    }
}

@Composable
private fun CallLogCard(
    entry: EnrichedCallLogItem,
    onInspectHistory: () -> Unit,
    onCallBack: () -> Unit,
    onEditInKeypad: () -> Unit,
    onDelete: () -> Unit
) {
    val item = entry.log
    var showMenu by remember { mutableStateOf(false) }

    val directionIcon = when (item.direction) {
        CallDirection.OUTGOING -> Icons.AutoMirrored.Rounded.CallMade
        CallDirection.INCOMING -> Icons.AutoMirrored.Rounded.CallReceived
        CallDirection.MISSED -> Icons.AutoMirrored.Rounded.CallMissed
    }
    val directionTint = when (item.direction) {
        CallDirection.MISSED -> MaterialTheme.colorScheme.error
        CallDirection.INCOMING -> SignalConnected
        CallDirection.OUTGOING -> MaterialTheme.colorScheme.primary
    }
    val lineShort = when {
        item.sourceType == CallSourceType.SIP -> "SIP"
        item.lineLabel.contains("SIM 2") -> "SIM 2"
        else -> "SIM 1"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onInspectHistory)
            .testTag("recent_call_card_${item.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                ContactAvatar(
                    displayName = entry.displayName,
                    photoUri = entry.photoUri,
                    size = 46.dp,
                    fontSize = 18.sp
                )
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = directionIcon,
                        contentDescription = item.direction.name,
                        tint = directionTint,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.direction == CallDirection.MISSED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = formatCompactTime(item.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.sourceType == CallSourceType.SIP) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Text(
                            text = lineShort,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (item.sourceType == CallSourceType.SIP) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    val subText = buildString {
                        if (entry.displayName != item.remoteHandle) {
                            append(item.remoteHandle)
                        } else {
                            append(item.lineLabel.substringAfter("• ", item.lineLabel))
                        }
                        if (item.durationSec > 0) {
                            append(" • ")
                            append(formatDurationDetailed(item.durationSec))
                        }
                    }
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (item.recordingId != null) {
                        Icon(
                            imageVector = Icons.Rounded.FiberManualRecord,
                            contentDescription = "Recorded call",
                            tint = SignalRecording,
                            modifier = Modifier.size(9.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            FilledTonalIconButton(
                onClick = onCallBack,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .size(44.dp)
                    .testTag("redial_button_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = "Call back ${entry.displayName}",
                    modifier = Modifier.size(20.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Call history") },
                        onClick = {
                            showMenu = false
                            onInspectHistory()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit in keypad") },
                        onClick = {
                            showMenu = false
                            onEditInKeypad()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete entry", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("delete_recent_${item.id}")
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallHistoryBottomSheet(
    detail: CallHistoryDetail,
    onCallBack: () -> Unit,
    onEditInKeypad: () -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactAvatar(
                    displayName = detail.displayName,
                    photoUri = detail.photoUri,
                    size = 54.dp,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = detail.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = detail.remoteHandle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCallBack,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("history_detail_call_button")
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Call Now")
                }
                OutlinedButton(
                    onClick = onEditInKeypad,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("history_detail_keypad_button")
                ) {
                    Icon(Icons.Rounded.Dialpad, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Edit Number")
                }
            }

            Text(
                text = "Call History (${detail.entries.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(detail.entries, key = { it.id }) { entry ->
                    val directionLabel = when (entry.direction) {
                        CallDirection.OUTGOING -> "Outgoing"
                        CallDirection.INCOMING -> "Incoming"
                        CallDirection.MISSED -> "Missed"
                    }
                    val dirTint = when (entry.direction) {
                        CallDirection.MISSED -> MaterialTheme.colorScheme.error
                        CallDirection.INCOMING -> SignalConnected
                        CallDirection.OUTGOING -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "$directionLabel • ${entry.lineLabel}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = dirTint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatFullDate(entry.timestampMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (entry.recordingId != null) {
                                    Icon(
                                        imageVector = Icons.Rounded.FiberManualRecord,
                                        contentDescription = "Recorded",
                                        tint = SignalRecording,
                                        modifier = Modifier.size(10.dp)
                                    )
                                }
                                Text(
                                    text = formatDurationDetailed(entry.durationSec),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                IconButton(
                                    onClick = { onDeleteEntry(entry.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete log entry",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatCompactTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffMs = (now - timestampMs).coerceAtLeast(0L)
    return if (diffMs < 86_400_000L) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    } else {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}

private fun formatFullDate(timestampMs: Long): String =
    SimpleDateFormat("EEE, MMM d • HH:mm", Locale.getDefault()).format(Date(timestampMs))

private fun formatDurationDetailed(durationSec: Int): String {
    if (durationSec <= 0) return "0s"
    val m = durationSec / 60
    val s = durationSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
