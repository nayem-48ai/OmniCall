package com.example.ui.recents

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.widget.Toast
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.ContactItem
import com.example.domain.model.CallDirection
import com.example.domain.model.CallSourceType
import com.example.ui.components.ContactAvatar
import com.example.ui.theme.SignalConnected
import com.example.ui.theme.SignalRecording
import com.example.ui.theme.SignalWarning
import com.example.ui.viewmodel.CallHistoryDetail
import com.example.ui.viewmodel.EnrichedCallLogItem
import com.example.ui.viewmodel.GroupedCallLogItem
import com.example.ui.viewmodel.RecentsDateBucket
import com.example.ui.viewmodel.RecentsFilter
import com.example.ui.viewmodel.RecentsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentsScreen(
    uiState: RecentsUiState,
    onSearchChange: (String) -> Unit,
    onSelectFilter: (RecentsFilter) -> Unit,
    onOpenCallHistory: (String) -> Unit,
    onCloseCallHistory: () -> Unit,
    onSelectContact: (ContactItem) -> Unit,
    onCloseContactDetail: () -> Unit,
    onCallBack: (String) -> Unit,
    onEditInKeypad: (String) -> Unit,
    onDeleteItem: (Long) -> Unit,
    onDeleteSelected: (Set<Long>) -> Unit,
    onToggleBlockNumber: (handle: String, displayName: String, isBlocked: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasSearchQuery = uiState.searchQuery.isNotBlank()
    val haptics = LocalHapticFeedback.current

    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var pendingSwipeDeleteGroup by remember { mutableStateOf<GroupedCallLogItem?>(null) }

    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
        ) {
            if (isSelectionMode) {
                // Contextual Multi-Select Bar (Google Phone style)
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 10.dp)
                        .testTag("recents_selection_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { selectedIds = emptySet() },
                            modifier = Modifier.testTag("recents_selection_close")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancel selection"
                            )
                        }
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        val allVisibleIds = uiState.groupedItems.flatMap { it.groupedIds }.toSet()
                        val allSelected = allVisibleIds.isNotEmpty() && selectedIds.containsAll(allVisibleIds)
                        TextButton(
                            onClick = {
                                selectedIds = if (allSelected) emptySet() else allVisibleIds
                            },
                            modifier = Modifier.testTag("recents_select_all_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SelectAll,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (allSelected) "Deselect" else "Select All")
                        }
                        IconButton(
                            onClick = {
                                onDeleteSelected(selectedIds)
                                selectedIds = emptySet()
                            },
                            modifier = Modifier.testTag("recents_delete_selected_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete selected call logs",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                // Top Pill Search Bar (replaces the "Recents" title and searches both Recents + Contacts)
                RecentsPillSearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = onSearchChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 10.dp)
                )
            }

            AnimatedVisibility(visible = !isSelectionMode) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
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
            }

            if (uiState.groupedItems.isEmpty() && (!hasSearchQuery || uiState.matchingContacts.isEmpty())) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (hasSearchQuery) Icons.Rounded.Search else Icons.Rounded.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (hasSearchQuery) "No matching calls or contacts" else "No recent calls yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (hasSearchQuery) {
                            "Try a different name or phone number."
                        } else {
                            "Tap an entry for history, press & hold to multi-select, or swipe left to delete."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val groupedByDate = remember(uiState.groupedItems) {
                    val order = listOf(RecentsDateBucket.TODAY, RecentsDateBucket.YESTERDAY, RecentsDateBucket.OLDER)
                    val map = uiState.groupedItems.groupBy { it.dateBucket }
                    order.mapNotNull { bucket -> map[bucket]?.let { bucket to it } }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (hasSearchQuery && uiState.groupedItems.isNotEmpty() && !isSelectionMode) {
                        item {
                            Text(
                                text = "Recent Calls (${uiState.items.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }

                    groupedByDate.forEach { (bucket, sectionEntries) ->
                        item(key = "header_${bucket.name}") {
                            Text(
                                text = bucket.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 6.dp, bottom = 2.dp, start = 4.dp)
                                    .testTag("recents_date_header_${bucket.name.lowercase()}")
                            )
                        }

                        items(sectionEntries, key = { it.groupKey }) { group ->
                            val entry = group.latest
                            val groupIdsSet = group.groupedIds.toSet()
                            val isItemSelected = group.groupedIds.all { it in selectedIds }
                            val toggleItemSelection = {
                                selectedIds = if (isItemSelected) {
                                    selectedIds - groupIdsSet
                                } else {
                                    selectedIds + groupIdsSet
                                }
                            }

                            if (isSelectionMode) {
                                CallLogCard(
                                    entry = entry,
                                    callCount = group.callCount,
                                    isSelected = isItemSelected,
                                    isSelectionMode = true,
                                    onClick = toggleItemSelection,
                                    onLongClick = toggleItemSelection,
                                    onCallBack = { },
                                    onEditInKeypad = { },
                                    onToggleBlock = { },
                                    onDelete = { }
                                )
                            } else {
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { targetValue ->
                                        if (targetValue == SwipeToDismissBoxValue.EndToStart) {
                                            pendingSwipeDeleteGroup = group
                                        }
                                        // Always snap back visually; actual deletion happens only on AlertDialog confirmation
                                        false
                                    }
                                )
                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = false,
                                    enableDismissFromEndToStart = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp)),
                                    backgroundContent = {
                                        val isSwipingLeft =
                                            dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    if (isSwipingLeft) {
                                                        MaterialTheme.colorScheme.errorContainer
                                                    } else {
                                                        androidx.compose.ui.graphics.Color.Transparent
                                                    }
                                                )
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            if (isSwipingLeft) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DeleteOutline,
                                                    contentDescription = "Swipe to delete",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    CallLogCard(
                                        entry = entry,
                                        callCount = group.callCount,
                                        isSelected = false,
                                        isSelectionMode = false,
                                        onClick = { onOpenCallHistory(entry.log.remoteHandle) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selectedIds = groupIdsSet
                                        },
                                        onCallBack = { onCallBack(entry.log.remoteHandle) },
                                        onEditInKeypad = { onEditInKeypad(entry.log.remoteHandle) },
                                        onToggleBlock = {
                                            onToggleBlockNumber(
                                                entry.log.remoteHandle,
                                                entry.displayName,
                                                entry.isBlocked
                                            )
                                        },
                                        onDelete = { onDeleteSelected(groupIdsSet) }
                                    )
                                }
                            }
                        }
                    }

                    if (hasSearchQuery && uiState.matchingContacts.isNotEmpty() && !isSelectionMode) {
                        item {
                            Text(
                                text = "Contacts (${uiState.matchingContacts.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(uiState.matchingContacts, key = { "contact_${it.id}" }) { contact ->
                            MatchingContactCard(
                                contact = contact,
                                onOpenDetail = { onSelectContact(contact) },
                                onCallPrimary = {
                                    if (contact.primaryNumber.isNotBlank()) {
                                        onCallBack(contact.primaryNumber)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    pendingSwipeDeleteGroup?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwipeDeleteGroup = null },
            title = { Text("Delete History?") },
            text = {
                if (target.callCount > 1) {
                    Text("Do you want to delete these ${target.callCount} call logs with ${target.latest.displayName}?")
                } else {
                    Text("Do you want to delete this call log with ${target.latest.displayName}?")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSelected(target.groupedIds.toSet())
                        pendingSwipeDeleteGroup = null
                    },
                    modifier = Modifier.testTag("confirm_swipe_delete_recent")
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwipeDeleteGroup = null }) {
                    Text("Cancel")
                }
            }
        )
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
            onToggleBlock = {
                onToggleBlockNumber(history.remoteHandle, history.displayName, history.isBlocked)
            },
            onDeleteEntry = onDeleteItem,
            onDismiss = onCloseCallHistory
        )
    }

    uiState.selectedContact?.let { contact ->
        RecentSearchContactDetailSheet(
            contact = contact,
            onCallNumber = { number ->
                onCloseContactDetail()
                onCallBack(number)
            },
            onEditInKeypad = { number ->
                onCloseContactDetail()
                onEditInKeypad(number)
            },
            onDismiss = onCloseContactDetail
        )
    }
}

@Composable
private fun RecentsPillSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        modifier = modifier.height(50.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search recents or contacts",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recents_search_input")
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchingContactCard(
    contact: ContactItem,
    onOpenDetail: () -> Unit,
    onCallPrimary: () -> Unit
) {
    val defaultPhone = contact.phoneNumbers.firstOrNull { it.isDefault } ?: contact.phoneNumbers.firstOrNull()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenDetail)
            .testTag("recents_contact_match_${contact.id}"),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(
                displayName = contact.displayName,
                photoUri = contact.photoUri,
                size = 46.dp,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (contact.isFavorite) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = "Favorite contact",
                            tint = SignalWarning,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = defaultPhone?.number.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = onCallPrimary,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .size(44.dp)
                    .testTag("recents_contact_call_${contact.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Call,
                    contentDescription = "Call ${contact.displayName}",
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallLogCard(
    entry: EnrichedCallLogItem,
    callCount: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCallBack: () -> Unit,
    onEditInKeypad: () -> Unit,
    onToggleBlock: () -> Unit,
    onDelete: () -> Unit
) {
    val item = entry.log
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

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

    val cardColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(18.dp)
                    )
                } else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("recent_call_card_${item.id}"),
        shape = RoundedCornerShape(18.dp),
        color = cardColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                } else {
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        val formattedTitle = if (callCount > 1) {
                            "${entry.displayName} ($callCount)"
                        } else {
                            entry.displayName
                        }
                        Text(
                            text = formattedTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                entry.isBlocked -> MaterialTheme.colorScheme.error
                                item.direction == CallDirection.MISSED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (entry.isBlocked) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Text(
                                    text = "Blocked",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
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

            if (!isSelectionMode) {
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
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("recent_more_${item.id}")
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
                            text = { Text("Copy to clipboard") },
                            onClick = {
                                showMenu = false
                                clipboardManager.setText(AnnotatedString(item.remoteHandle))
                                Toast.makeText(context, "Copied ${item.remoteHandle}", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("copy_recent_${item.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Edit in keypad") },
                            onClick = {
                                showMenu = false
                                onEditInKeypad()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (entry.isBlocked) "Unblock" else "Block",
                                    color = if (entry.isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Block,
                                    contentDescription = null,
                                    tint = if (entry.isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onToggleBlock()
                            },
                            modifier = Modifier.testTag("block_recent_${item.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Delete History", color = MaterialTheme.colorScheme.error) },
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallHistoryBottomSheet(
    detail: CallHistoryDetail,
    onCallBack: () -> Unit,
    onEditInKeypad: () -> Unit,
    onToggleBlock: () -> Unit,
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
                TextButton(
                    onClick = onToggleBlock,
                    modifier = Modifier.testTag("history_detail_block_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Block,
                        contentDescription = null,
                        tint = if (detail.isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (detail.isBlocked) "Unblock" else "Block",
                        color = if (detail.isBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentSearchContactDetailSheet(
    contact: ContactItem,
    onCallNumber: (String) -> Unit,
    onEditInKeypad: (String) -> Unit,
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
                    displayName = contact.displayName,
                    photoUri = contact.photoUri,
                    size = 54.dp,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (contact.isFavorite) {
                        Text(
                            text = "Favorite Contact",
                            style = MaterialTheme.typography.labelSmall,
                            color = SignalWarning
                        )
                    }
                }
            }

            contact.phoneNumbers.forEach { phone ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCallNumber(phone.number) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = phone.number,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (phone.isDefault) "${phone.typeLabel} • Default" else phone.typeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onEditInKeypad(phone.number) }) {
                            Icon(
                                imageVector = Icons.Rounded.Dialpad,
                                contentDescription = "Edit in keypad",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onCallNumber(phone.number) }) {
                            Icon(
                                imageVector = Icons.Rounded.Call,
                                contentDescription = "Call ${phone.number}",
                                tint = MaterialTheme.colorScheme.primary
                            )
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
