package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.BlockedNumberItem
import com.example.ui.components.ContactAvatar
import com.example.ui.viewmodel.BlocklistUiState
import com.example.ui.viewmodel.BlocklistViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AddBlockTab(val label: String) {
    MANUAL("Phone / SIP Number"),
    CONTACTS("Pick Contact")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocklistScreen(
    uiState: BlocklistUiState,
    viewModel: BlocklistViewModel,
    onRequestContactsPermission: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var pendingUnblockItem by remember { mutableStateOf<BlockedNumberItem?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }

    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }

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
            if (isSelectionMode) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 10.dp)
                        .testTag("blocklist_selection_bar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { selectedIds = emptySet() },
                            modifier = Modifier.testTag("blocklist_selection_close")
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
                        val allIds = uiState.blockedItems.map { it.id }.toSet()
                        val allSelected = allIds.isNotEmpty() && selectedIds.containsAll(allIds)
                        TextButton(
                            onClick = {
                                selectedIds = if (allSelected) emptySet() else allIds
                            },
                            modifier = Modifier.testTag("blocklist_select_all_button")
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
                                viewModel.unblockSelected(selectedIds)
                                selectedIds = emptySet()
                            },
                            modifier = Modifier.testTag("blocklist_delete_selected_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Unblock selected numbers",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("blocklist_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back to Settings"
                            )
                        }
                        Column {
                            Text(
                                text = "Blocklist",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${uiState.blockedItems.size} blocked number${if (uiState.blockedItems.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            viewModel.refreshContacts()
                            viewModel.setContactSearchQuery("")
                            showAddSheet = true
                        },
                        modifier = Modifier.testTag("blocklist_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }

            if (uiState.blockedItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No blocked numbers",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Incoming SIM and SIP calls from blocked numbers are rejected automatically without ringing.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = {
                            viewModel.refreshContacts()
                            viewModel.setContactSearchQuery("")
                            showAddSheet = true
                        },
                        modifier = Modifier.testTag("blocklist_empty_add_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Block a Number")
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.blockedItems, key = { it.id }) { item ->
                        val isSelected = item.id in selectedIds
                        val toggleSelection = {
                            selectedIds = if (isSelected) selectedIds - item.id else selectedIds + item.id
                        }

                        if (isSelectionMode) {
                            BlockedNumberRow(
                                item = item,
                                isSelected = isSelected,
                                isSelectionMode = true,
                                onClick = toggleSelection,
                                onLongClick = toggleSelection,
                                onUnblockClick = { }
                            )
                        } else {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { targetValue ->
                                    if (targetValue == SwipeToDismissBoxValue.EndToStart) {
                                        pendingUnblockItem = item
                                    }
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
                                                contentDescription = "Swipe to unblock",
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            ) {
                                BlockedNumberRow(
                                    item = item,
                                    isSelected = false,
                                    isSelectionMode = false,
                                    onClick = { },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedIds = setOf(item.id)
                                    },
                                    onUnblockClick = { pendingUnblockItem = item }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingUnblockItem?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingUnblockItem = null },
            title = { Text("Unblock Number?") },
            text = {
                Text("Remove ${target.displayName} (${target.rawNumber}) from your blocklist? Future calls from this number will ring normally.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.unblockItem(target)
                        pendingUnblockItem = null
                    },
                    modifier = Modifier.testTag("confirm_unblock_button")
                ) {
                    Text("Unblock", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnblockItem = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddSheet) {
        AddToBlocklistSheet(
            uiState = uiState,
            onSearchChange = viewModel::setContactSearchQuery,
            onRequestContactsPermission = onRequestContactsPermission,
            onBlockManual = { number, label ->
                viewModel.blockManualNumber(number, label)
                showAddSheet = false
            },
            onBlockContact = { number, name ->
                viewModel.blockContactNumber(number, name)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockedNumberRow(
    item: BlockedNumberItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onUnblockClick: () -> Unit
) {
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("blocked_item_${item.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Block,
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
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
                if (item.displayName != item.rawNumber) {
                    Text(
                        text = item.rawNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "Blocked ${formatBlockedDate(item.createdAtMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isSelectionMode) {
                IconButton(
                    onClick = onUnblockClick,
                    modifier = Modifier.testTag("unblock_item_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Unblock ${item.displayName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToBlocklistSheet(
    uiState: BlocklistUiState,
    onSearchChange: (String) -> Unit,
    onRequestContactsPermission: () -> Unit,
    onBlockManual: (number: String, label: String) -> Unit,
    onBlockContact: (number: String, displayName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(AddBlockTab.MANUAL) }
    var manualNumber by remember { mutableStateOf("") }
    var manualLabel by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Block a Number",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeTab == AddBlockTab.MANUAL,
                    onClick = { activeTab = AddBlockTab.MANUAL },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Dialpad,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(AddBlockTab.MANUAL.label) },
                    modifier = Modifier.testTag("blocklist_tab_manual")
                )
                FilterChip(
                    selected = activeTab == AddBlockTab.CONTACTS,
                    onClick = { activeTab = AddBlockTab.CONTACTS },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Contacts,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(AddBlockTab.CONTACTS.label) },
                    modifier = Modifier.testTag("blocklist_tab_contacts")
                )
            }

            if (activeTab == AddBlockTab.MANUAL) {
                OutlinedTextField(
                    value = manualNumber,
                    onValueChange = { manualNumber = it },
                    label = { Text("Phone or SIP Number *") },
                    placeholder = { Text("+88017... or user@sip.domain") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("blocklist_manual_number_input")
                )
                OutlinedTextField(
                    value = manualLabel,
                    onValueChange = { manualLabel = it },
                    label = { Text("Name / Note (Optional)") },
                    placeholder = { Text("Spam caller") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("blocklist_manual_label_input")
                )
                Button(
                    onClick = { onBlockManual(manualNumber, manualLabel) },
                    enabled = manualNumber.trim().isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("blocklist_confirm_manual_button")
                ) {
                    Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Block Number")
                }
            } else {
                if (!uiState.hasContactsPermission) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Contacts Permission Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Allow OmniCall to read your Contacts so you can pick someone directly to block.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onRequestContactsPermission,
                                modifier = Modifier.testTag("blocklist_grant_contacts_button")
                            ) {
                                Text("Allow Contacts")
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = uiState.contactSearchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search contacts...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("blocklist_contact_search_input")
                    )

                    if (uiState.contacts.isEmpty()) {
                        Text(
                            text = "No matching unblocked contacts found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(uiState.contacts, key = { it.id }) { contact ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    onClick = { onBlockContact(contact.primaryNumber, contact.displayName) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("blocklist_pick_contact_${contact.id}")
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
                                            size = 40.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contact.displayName,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = contact.primaryNumber,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Rounded.Block,
                                            contentDescription = "Block ${contact.displayName}",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
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
}

private fun formatBlockedDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ms))
