package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.repository.CallLogItem
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

data class CallHistoryDetail(
    val remoteHandle: String,
    val displayName: String,
    val entries: List<CallLogItem>
)

data class RecentsUiState(
    val filter: RecentsFilter = RecentsFilter.ALL,
    val items: List<CallLogItem> = emptyList(),
    val selectedHistory: CallHistoryDetail? = null
)

class RecentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _filter = MutableStateFlow(RecentsFilter.ALL)
    private val _selectedHandle = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RecentsUiState> = combine(
        _filter,
        container.callLogRepository.recentCallsFlow,
        _selectedHandle
    ) { filter, logs, selectedHandle ->
        val filtered = when (filter) {
            RecentsFilter.ALL -> logs
            RecentsFilter.MISSED -> logs.filter { it.direction == CallDirection.MISSED }
            RecentsFilter.SIP -> logs.filter { it.sourceType == CallSourceType.SIP }
            RecentsFilter.SIM -> logs.filter { it.sourceType != CallSourceType.SIP }
        }
        val detail = selectedHandle?.let { handle ->
            val targetKey = normalizeForMatch(handle)
            val matching = logs.filter { normalizeForMatch(it.remoteHandle) == targetKey }
            val bestName = matching.firstOrNull { it.displayName.isNotBlank() }?.displayName ?: handle
            CallHistoryDetail(
                remoteHandle = handle,
                displayName = bestName,
                entries = matching
            )
        }
        RecentsUiState(
            filter = filter,
            items = filtered,
            selectedHistory = detail
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RecentsUiState()
    )

    fun setFilter(filter: RecentsFilter) {
        _filter.value = filter
    }

    fun openCallHistoryForNumber(remoteHandle: String) {
        _selectedHandle.value = remoteHandle
    }

    fun closeCallHistory() {
        _selectedHandle.value = null
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
