package com.example.ui.recents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.repository.CallLogItem
import com.example.domain.model.CallDirection
import com.example.domain.model.CallSourceType
import com.example.ui.theme.SignalConnected
import com.example.ui.theme.SignalRecording
import com.example.ui.viewmodel.CallHistoryDetail
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
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
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
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                items(RecentsFilter.entries) { filter ->
                    FilterChip(
                        selected = uiState.filter == filter,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter.label) },
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
                        text = "Tap any number to inspect its full call history, or tap the Call button to redial.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        CallLogCard(
                            item = item,
                            onInspectHistory = { onOpenCallHistory(item.remoteHandle) },
                            onCallBack = { onCallBack(item.remoteHandle) },
                            onDelete = { onDeleteItem(item.id) }
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
            onDismiss = onCloseCallHistory
        )
    }
}

@Composable
private fun CallLogCard(
    item: CallLogItem,
    onInspectHistory: () -> Unit,
    onCallBack: () -> Unit,
    onDelete: () -> Unit
) {
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onInspectHistory)
            .testTag("recent_call_card_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(directionTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = directionIcon,
                    contentDescription = item.direction.name,
                    tint = directionTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
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
                            text = item.lineLabel,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (item.recordingId != null) {
                        Icon(
                            imageVector = Icons.Rounded.FiberManualRecord,
                            contentDescription = "Recorded call",
                            tint = SignalRecording,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                    Text(
                        text = formatTimestampAndDuration(item.timestampMs, item.durationSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onCallBack,
                modifier = Modifier.testTag("redial_button_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = "Call back ${item.displayName}",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_recent_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete log entry",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
            Text(
                text = detail.displayName,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = detail.remoteHandle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                        CallDirection.OUTGOING -> "Outgoing call"
                        CallDirection.INCOMING -> "Incoming call"
                        CallDirection.MISSED -> "Missed call"
                    }
                    val dirTint = when (entry.direction) {
                        CallDirection.MISSED -> MaterialTheme.colorScheme.error
                        CallDirection.INCOMING -> SignalConnected
                        CallDirection.OUTGOING -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "$directionLabel • ${entry.lineLabel}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = dirTint
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
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestampAndDuration(timestampMs: Long, durationSec: Int): String {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val timeStr = fmt.format(Date(timestampMs))
    return if (durationSec > 0) {
        val m = durationSec / 60
        val s = durationSec % 60
        "$timeStr • ${m}m ${s}s"
    } else {
        timeStr
    }
}

private fun formatFullDate(timestampMs: Long): String =
    SimpleDateFormat("EEE, MMM d yyyy • HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))

private fun formatDurationDetailed(durationSec: Int): String {
    if (durationSec <= 0) return "0s"
    val m = durationSec / 60
    val s = durationSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
