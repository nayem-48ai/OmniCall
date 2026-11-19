package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.repository.BlocklistRepository
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

import java.util.Calendar

enum class RecentsFilter(val label: String) {
    ALL("All"),
    MISSED("Missed"),
    SIP("SIP VoIP"),
    SIM("Cellular SIM")
}

enum class RecentsDateBucket(val title: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    OLDER("Older")
}

data class EnrichedCallLogItem(
    val log: CallLogItem,
    val displayName: String,
    val photoUri: String?,
    val isBlocked: Boolean = false
)

data class GroupedCallLogItem(
    val groupKey: String,
    val latest: EnrichedCallLogItem,
    val groupedIds: List<Long>,
    val callCount: Int,
    val dateBucket: RecentsDateBucket,
    val allEntries: List<EnrichedCallLogItem>
)

data class CallHistoryDetail(
    val remoteHandle: String,
    val displayName: String,
    val photoUri: String?,
    val isBlocked: Boolean = false,
    val entries: List<CallLogItem>
)

data class RecentsUiState(
    val filter: RecentsFilter = RecentsFilter.ALL,
    val searchQuery: String = "",
    val items: List<EnrichedCallLogItem> = emptyList(),
    val groupedItems: List<GroupedCallLogItem> = emptyList(),
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
                    val key = BlocklistRepository.normalizeKey(phone.number)
                    if (key.isNotEmpty()) {
                        val existing = map[key]
                        if (existing == null ||
                            (existing.photoUri.isNullOrBlank() && !contact.photoUri.isNullOrBlank())
                        ) {
                            map[key] = contact
                        }
                    }
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
            val match = contactMap[BlocklistRepository.normalizeKey(log.remoteHandle)]
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
            val targetKey = BlocklistRepository.normalizeKey(handle)
            val matching = logs.filter { BlocklistRepository.normalizeKey(it.remoteHandle) == targetKey }
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
            groupedItems = groupConsecutiveRecents(filteredItems),
            selectedHistory = detail
        )
    }.combine(container.blocklistRepository.blockedFlow) { state, blockedList ->
        val blockedKeys = blockedList.map { it.normalizedKey }.toSet()
        val enrichedWithBlock = state.items.map { item ->
            val key = BlocklistRepository.normalizeKey(item.log.remoteHandle)
            item.copy(isBlocked = key in blockedKeys)
        }
        state.copy(
            items = enrichedWithBlock,
            groupedItems = groupConsecutiveRecents(enrichedWithBlock),
            selectedHistory = state.selectedHistory?.let { detail ->
                val key = BlocklistRepository.normalizeKey(detail.remoteHandle)
                detail.copy(isBlocked = key in blockedKeys)
            }
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

    fun deleteEntries(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.callLogRepository.deleteCalls(ids.toList())
        }
    }

    fun toggleBlockNumber(handle: String, displayName: String, currentlyBlocked: Boolean) {
        viewModelScope.launch {
            if (currentlyBlocked) {
                container.blocklistRepository.unblockNumber(handle)
            } else {
                container.blocklistRepository.blockNumber(handle, displayName)
            }
        }
    }

    companion object {
        fun classifyDateBucket(
            timestampMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): RecentsDateBucket {
            val cal = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfTodayMs = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val startOfYesterdayMs = cal.timeInMillis

            return when {
                timestampMs >= startOfTodayMs -> RecentsDateBucket.TODAY
                timestampMs >= startOfYesterdayMs -> RecentsDateBucket.YESTERDAY
                else -> RecentsDateBucket.OLDER
            }
        }

        fun groupConsecutiveRecents(
            items: List<EnrichedCallLogItem>,
            nowMs: Long = System.currentTimeMillis()
        ): List<GroupedCallLogItem> {
            if (items.isEmpty()) return emptyList()
            val result = mutableListOf<GroupedCallLogItem>()
            var currentGroup = mutableListOf<EnrichedCallLogItem>()
            var currentKey = ""
            var currentBucket: RecentsDateBucket? = null

            fun flushGroup() {
                if (currentGroup.isEmpty()) return
                val first = currentGroup.first()
                val bucket = currentBucket ?: RecentsDateBucket.TODAY
                result.add(
                    GroupedCallLogItem(
                        groupKey = "${bucket.name}_${first.log.id}",
                        latest = first,
                        groupedIds = currentGroup.map { it.log.id },
                        callCount = currentGroup.size,
                        dateBucket = bucket,
                        allEntries = currentGroup.toList()
                    )
                )
            }

            for (item in items) {
                val key = BlocklistRepository.normalizeKey(item.log.remoteHandle)
                    .ifBlank { item.log.remoteHandle.lowercase() }
                val bucket = classifyDateBucket(item.log.timestampMs, nowMs)

                if (currentGroup.isNotEmpty() && key == currentKey && bucket == currentBucket) {
                    currentGroup.add(item)
                } else {
                    flushGroup()
                    currentGroup = mutableListOf(item)
                    currentKey = key
                    currentBucket = bucket
                }
            }
            flushGroup()
            return result
        }
    }
}
