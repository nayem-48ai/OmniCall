package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.repository.CallLogItem
import com.example.data.repository.ContactItem
import com.example.domain.model.CallDirection
import com.example.domain.model.CallSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RecentsFilter(val label: String) {
    ALL("All"),
    MISSED("Missed"),
    SIP("SIP VoIP"),
    SIM("Cellular SIM")
}

data class EnrichedCallLogItem(
    val log: CallLogItem,
    val displayName: String,
    val photoUri: String?
)

data class CallHistoryDetail(
    val remoteHandle: String,
    val displayName: String,
    val photoUri: String?,
    val entries: List<CallLogItem>
)

data class RecentsUiState(
    val filter: RecentsFilter = RecentsFilter.ALL,
    val searchQuery: String = "",
    val items: List<EnrichedCallLogItem> = emptyList(),
    val matchingContacts: List<ContactItem> = emptyList(),
    val selectedHistory: CallHistoryDetail? = null,
    val selectedContact: ContactItem? = null
)

class RecentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _filter = MutableStateFlow(RecentsFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _selectedHandle = MutableStateFlow<String?>(null)
    private val _selectedContact = MutableStateFlow<ContactItem?>(null)
    private val _allContacts = MutableStateFlow<List<ContactItem>>(emptyList())
    private val _contactsLookup = MutableStateFlow<Map<String, ContactItem>>(emptyMap())

    init {
        refreshContactsIndex()
    }

    fun refreshContactsIndex() {
        if (!container.contactsRepository.hasContactsPermission()) return
        viewModelScope.launch {
            val contacts = container.contactsRepository.loadContacts()
            val map = hashMapOf<String, ContactItem>()
            contacts.forEach { contact ->
                contact.phoneNumbers.forEach { phone ->
                    val key = normalizeForMatch(phone.number)
                    if (key.isNotEmpty()) map[key] = contact
                }
            }
            _allContacts.value = contacts
            _contactsLookup.value = map
        }
    }

    val uiState: StateFlow<RecentsUiState> = combine(
        _filter,
        _searchQuery,
        container.callLogRepository.recentCallsFlow,
        _selectedHandle,
        _contactsLookup
    ) { filter, query, logs, selectedHandle, contactMap ->
        val baseFiltered = when (filter) {
            RecentsFilter.ALL -> logs
            RecentsFilter.MISSED -> logs.filter { it.direction == CallDirection.MISSED }
            RecentsFilter.SIP -> logs.filter { it.sourceType == CallSourceType.SIP }
            RecentsFilter.SIM -> logs.filter { it.sourceType != CallSourceType.SIP }
        }
        val enrichedAll = baseFiltered.map { log ->
            val match = contactMap[normalizeForMatch(log.remoteHandle)]
            EnrichedCallLogItem(
                log = log,
                displayName = match?.displayName ?: log.displayName.ifBlank { log.remoteHandle },
                photoUri = match?.photoUri
            )
        }

        val q = query.trim().lowercase()
        val digitsQuery = q.filter { it.isDigit() }
        val filteredItems = if (q.isBlank()) {
            enrichedAll
        } else {
            enrichedAll.filter { entry ->
                entry.displayName.lowercase().contains(q) ||
                    entry.log.remoteHandle.lowercase().contains(q) ||
                    (digitsQuery.isNotEmpty() && entry.log.remoteHandle.filter { it.isDigit() }.contains(digitsQuery))
            }
        }

        val detail = selectedHandle?.let { handle ->
            val targetKey = normalizeForMatch(handle)
            val matching = logs.filter { normalizeForMatch(it.remoteHandle) == targetKey }
            val matchedContact = contactMap[targetKey]
            val bestName = matchedContact?.displayName
                ?: matching.firstOrNull { it.displayName.isNotBlank() }?.displayName
                ?: handle
            CallHistoryDetail(
                remoteHandle = handle,
                displayName = bestName,
                photoUri = matchedContact?.photoUri,
                entries = matching
            )
        }

        RecentsUiState(
            filter = filter,
            searchQuery = query,
            items = filteredItems,
            selectedHistory = detail
        )
    }.combine(_allContacts) { state, allContacts ->
        val q = state.searchQuery.trim().lowercase()
        val matchingContacts = if (q.isBlank()) {
            emptyList()
        } else {
            val digitsQuery = q.filter { it.isDigit() }
            allContacts.filter { contact ->
                contact.displayName.lowercase().contains(q) ||
                    contact.phoneNumbers.any { phone ->
                        phone.number.lowercase().contains(q) ||
                            (digitsQuery.isNotEmpty() && phone.number.filter { it.isDigit() }.contains(digitsQuery))
                    }
            }
        }
        state.copy(matchingContacts = matchingContacts)
    }.combine(_selectedContact) { state, selectedContact ->
        state.copy(selectedContact = selectedContact)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RecentsUiState()
    )

    fun setFilter(filter: RecentsFilter) {
        _filter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openCallHistoryForNumber(remoteHandle: String) {
        _selectedHandle.value = remoteHandle
    }

    fun closeCallHistory() {
        _selectedHandle.value = null
    }

    fun openContactDetail(contact: ContactItem) {
        _selectedContact.value = contact
    }

    fun closeContactDetail() {
        _selectedContact.value = null
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            container.callLogRepository.deleteCall(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            container.callLogRepository.clearAll()
        }
    }

    private fun normalizeForMatch(handle: String): String {
        val trimmed = handle.trim().lowercase()
        if (trimmed.contains("@")) return trimmed
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length > 8) digits.takeLast(9) else digits.ifEmpty { trimmed }
    }
}
