package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.repository.ContactItem
import com.example.domain.model.BlockedNumberItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BlocklistUiState(
    val blockedItems: List<BlockedNumberItem> = emptyList(),
    val contacts: List<ContactItem> = emptyList(),
    val contactSearchQuery: String = "",
    val hasContactsPermission: Boolean = false
)

class BlocklistViewModel(private val container: AppContainer) : ViewModel() {

    private val _allContacts = MutableStateFlow<List<ContactItem>>(emptyList())
    private val _contactSearchQuery = MutableStateFlow("")
    private val _hasContactsPermission = MutableStateFlow(container.contactsRepository.hasContactsPermission())

    init {
        refreshContacts()
    }

    val uiState: StateFlow<BlocklistUiState> = combine(
        container.blocklistRepository.blockedFlow,
        _allContacts,
        _contactSearchQuery,
        _hasContactsPermission
    ) { blocked, contacts, query, hasPerm ->
        val blockedKeys = blocked.map { it.normalizedKey }.toSet()
        val q = query.trim().lowercase()
        val digitsQ = q.filter { it.isDigit() }
        val filteredContacts = contacts.filter { c ->
            val primaryKey = com.example.data.repository.BlocklistRepository.normalizeKey(c.primaryNumber)
            val matchesQuery = q.isBlank() ||
                c.displayName.lowercase().contains(q) ||
                c.phoneNumbers.any { p ->
                    p.number.lowercase().contains(q) ||
                        (digitsQ.isNotEmpty() && p.number.filter { it.isDigit() }.contains(digitsQ))
                }
            matchesQuery && primaryKey !in blockedKeys
        }
        BlocklistUiState(
            blockedItems = blocked,
            contacts = filteredContacts,
            contactSearchQuery = query,
            hasContactsPermission = hasPerm
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BlocklistUiState(hasContactsPermission = _hasContactsPermission.value)
    )

    fun refreshContacts() {
        val perm = container.contactsRepository.hasContactsPermission()
        _hasContactsPermission.value = perm
        if (!perm) return
        viewModelScope.launch {
            _allContacts.value = container.contactsRepository.loadContacts()
        }
    }

    fun setContactSearchQuery(query: String) {
        _contactSearchQuery.value = query
    }

    fun blockManualNumber(number: String, label: String = "") {
        val trimmed = number.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val resolvedName = label.trim().ifBlank {
                container.contactsRepository.lookupDisplayName(trimmed) ?: trimmed
            }
            container.blocklistRepository.blockNumber(trimmed, resolvedName)
        }
    }

    fun blockContactNumber(number: String, displayName: String) {
        if (number.isBlank()) return
        viewModelScope.launch {
            container.blocklistRepository.blockNumber(number, displayName)
        }
    }

    fun unblockItem(item: BlockedNumberItem) {
        viewModelScope.launch {
            container.blocklistRepository.unblockItem(item)
        }
    }

    fun unblockSelected(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val targets = uiState.value.blockedItems.filter { it.id in ids }
        viewModelScope.launch {
            container.blocklistRepository.unblockItems(targets)
        }
    }
}
