package com.example.ui.viewmodel

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.preferences.AppSettingsState
import com.example.data.repository.CallLogItem
import com.example.data.repository.ContactItem
import com.example.domain.model.AvailableCallingEnvironment
import com.example.domain.model.CallDirection
import com.example.domain.model.CallSourceType
import com.example.domain.model.OutgoingRoutingMode
import com.example.domain.model.ResolvedOutgoingRoute
import com.example.domain.model.SimSlotInfo
import com.example.domain.model.SipAccountModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DialerSuggestionItem(
    val id: String,
    val displayName: String,
    val number: String,
    val photoUri: String? = null,
    val direction: CallDirection? = null,
    val sourceType: CallSourceType? = null,
    val lineLabel: String = "",
    val timestampMs: Long? = null,
    val isContactMatch: Boolean = false
)

data class DialerUiState(
    val inputNumber: String = "",
    val matchingRecents: List<CallLogItem> = emptyList(),
    val suggestions: List<DialerSuggestionItem> = emptyList(),
    val environment: AvailableCallingEnvironment = AvailableCallingEnvironment(),
    val showRoutePickerSheet: Boolean = false,
    val pendingCallDestination: String? = null,
    val bannerMessage: String? = null
)

class DialerViewModel(
    application: Application,
    private val container: AppContainer
) : AndroidViewModel(application) {

    private val initialSims = container.simSubscriptionDetector.detectActiveSims()
    private val initialIsDefault = container.telecomCallManager.isDefaultDialer()

    private val _inputNumber = MutableStateFlow("")
    private val _simSlots = MutableStateFlow<List<SimSlotInfo>>(initialSims)
    private val _isDefaultDialer = MutableStateFlow(initialIsDefault)
    private val _contacts = MutableStateFlow<List<ContactItem>>(emptyList())
    private val _showRoutePickerSheet = MutableStateFlow(false)
    private val _pendingDestination = MutableStateFlow<String?>(null)
    private val _bannerMessage = MutableStateFlow<String?>(null)

    private var toneGenerator: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_DTMF, 65)
    }.getOrNull()

    val uiState: StateFlow<DialerUiState> = combine(
        _inputNumber,
        container.callLogRepository.recentCallsFlow,
        _simSlots,
        container.sipAccountRepository.accountsFlow,
        container.preferencesRepository.settingsFlow
    ) { input, recents, sims, sipAccounts, settings ->
        val filtered = if (input.isBlank()) {
            recents.take(4)
        } else {
            val q = input.trim().lowercase()
            recents.filter {
                it.remoteHandle.lowercase().contains(q) ||
                    it.displayName.lowercase().contains(q)
            }.take(6)
        }
        val env = AvailableCallingEnvironment(
            simSlots = sims,
            sipAccounts = sipAccounts,
            selectedMode = settings.outgoingRoutingMode,
            isDefaultDialer = _isDefaultDialer.value
        )
        DialerUiState(
            inputNumber = input,
            matchingRecents = filtered,
            environment = env,
            showRoutePickerSheet = _showRoutePickerSheet.value,
            pendingCallDestination = _pendingDestination.value,
            bannerMessage = _bannerMessage.value
        )
    }.combine(_contacts) { state, contacts ->
        val suggestions = buildSuggestions(
            query = state.inputNumber,
            recents = state.matchingRecents,
            contacts = contacts
        )
        state.copy(suggestions = suggestions)
    }.combine(_isDefaultDialer) { state, isDefault ->
        state.copy(environment = state.environment.copy(isDefaultDialer = isDefault))
    }.combine(_pendingDestination) { state, pending ->
        state.copy(pendingCallDestination = pending)
    }.combine(_showRoutePickerSheet) { state, showSheet ->
        state.copy(showRoutePickerSheet = showSheet)
    }.combine(_bannerMessage) { state, banner ->
        state.copy(bannerMessage = banner)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DialerUiState(
            environment = AvailableCallingEnvironment(
                simSlots = initialSims,
                isDefaultDialer = initialIsDefault
            )
        )
    )

    init {
        viewModelScope.launch {
            _contacts.value = container.contactsRepository.loadContacts()
        }
        viewModelScope.launch {
            container.activeCallCoordinator.callErrorEvents.collect { errorMsg ->
                _bannerMessage.value = errorMsg
            }
        }
    }

    fun refreshHardwareEnvironment() {
        _simSlots.value = container.simSubscriptionDetector.detectActiveSims()
        _isDefaultDialer.value = container.telecomCallManager.isDefaultDialer()
        viewModelScope.launch {
            _contacts.value = container.contactsRepository.loadContacts()
        }
    }

    private fun buildSuggestions(
        query: String,
        recents: List<CallLogItem>,
        contacts: List<ContactItem>
    ): List<DialerSuggestionItem> {
        val tailMap = HashMap<String, ContactItem>()
        contacts.forEach { contact ->
            contact.phoneNumbers.forEach { phone ->
                val tail = phone.number.filter(Char::isDigit).let { d ->
                    if (d.length >= 8) d.takeLast(9) else d
                }
                if (tail.isNotEmpty() && !tailMap.containsKey(tail)) {
                    tailMap[tail] = contact
                }
            }
        }

        val seenNumbers = HashSet<String>()
        val results = mutableListOf<DialerSuggestionItem>()

        recents.forEach { log ->
            val digits = log.remoteHandle.filter(Char::isDigit)
            val tail = if (digits.length >= 8) digits.takeLast(9) else digits
            val key = tail.ifBlank { log.remoteHandle.lowercase() }
            if (seenNumbers.add(key)) {
                val matchedContact = if (tail.isNotEmpty()) tailMap[tail] else null
                val resolvedName = matchedContact?.displayName?.ifBlank { null }
                    ?: log.displayName.ifBlank { log.remoteHandle }
                results.add(
                    DialerSuggestionItem(
                        id = "recent_${log.id}",
                        displayName = resolvedName,
                        number = log.remoteHandle,
                        photoUri = matchedContact?.photoUri,
                        direction = log.direction,
                        sourceType = log.sourceType,
                        lineLabel = log.lineLabel,
                        timestampMs = log.timestampMs,
                        isContactMatch = false
                    )
                )
            }
        }

        val q = query.trim().lowercase()
        if (q.isNotBlank()) {
            val qDigits = q.filter(Char::isDigit)
            for (contact in contacts) {
                if (results.size >= 6) break
                val nameMatches = contact.displayName.lowercase().contains(q)
                val matchedPhone = contact.phoneNumbers.firstOrNull { phone ->
                    phone.number.lowercase().contains(q) ||
                        (qDigits.isNotEmpty() && phone.number.filter(Char::isDigit).contains(qDigits))
                } ?: if (nameMatches) contact.phoneNumbers.firstOrNull() else null

                if (matchedPhone != null) {
                    val digits = matchedPhone.number.filter(Char::isDigit)
                    val tail = if (digits.length >= 8) digits.takeLast(9) else digits
                    val key = tail.ifBlank { matchedPhone.number.lowercase() }
                    if (seenNumbers.add(key)) {
                        results.add(
                            DialerSuggestionItem(
                                id = "contact_${contact.id}_$key",
                                displayName = contact.displayName,
                                number = matchedPhone.number,
                                photoUri = contact.photoUri,
                                lineLabel = matchedPhone.typeLabel,
                                isContactMatch = true
                            )
                        )
                    }
                }
            }
        }

        return results.take(if (q.isBlank()) 4 else 6)
    }

    fun appendDigit(digit: Char) {
        playDtmfTone(digit)
        _inputNumber.update { it + digit }
    }

    fun setNumberInput(value: String) {
        _inputNumber.value = value
    }

    fun backspace() {
        _inputNumber.update { if (it.isNotEmpty()) it.dropLast(1) else "" }
    }

    fun clearInput() {
        _inputNumber.value = ""
    }

    fun setRoutingMode(mode: OutgoingRoutingMode) {
        viewModelScope.launch {
            container.preferencesRepository.setOutgoingRoutingMode(mode)
        }
    }

    fun initiateCall(targetOverride: String? = null) {
        val destination = (targetOverride ?: _inputNumber.value).trim()
        if (destination.isBlank()) {
            val lastCalled = uiState.value.matchingRecents.firstOrNull()?.remoteHandle
            if (!lastCalled.isNullOrBlank()) {
                _inputNumber.value = lastCalled
            }
            return
        }

        val env = uiState.value.environment
        val availableRoutes = buildAvailableRoutes(env)
        if (availableRoutes.isEmpty()) {
            _bannerMessage.value = "Grant Phone permission for SIM calling or configure a SIP account."
            return
        }

        // If user entered a SIP URI (contains @), prefer SIP route when available
        if (destination.contains("@")) {
            val sipRoute = availableRoutes.filterIsInstance<ResolvedOutgoingRoute.SipAccount>().firstOrNull()
            if (sipRoute != null) {
                executeCall(destination, sipRoute)
                return
            }
        }

        val effectiveMode = env.effectiveSelectedMode()
        when (effectiveMode) {
            OutgoingRoutingMode.SIM_1 -> {
                val route = availableRoutes.filterIsInstance<ResolvedOutgoingRoute.CellularSim>().firstOrNull()
                if (route != null) executeCall(destination, route)
            }
            OutgoingRoutingMode.SIM_2 -> {
                val route = availableRoutes.filterIsInstance<ResolvedOutgoingRoute.CellularSim>().getOrNull(1)
                    ?: availableRoutes.filterIsInstance<ResolvedOutgoingRoute.CellularSim>().firstOrNull()
                if (route != null) executeCall(destination, route)
            }
            OutgoingRoutingMode.SIP -> {
                val route = availableRoutes.filterIsInstance<ResolvedOutgoingRoute.SipAccount>().firstOrNull()
                if (route != null) executeCall(destination, route)
            }
            OutgoingRoutingMode.ASK_EVERY_TIME -> {
                if (availableRoutes.size == 1) {
                    executeCall(destination, availableRoutes.first())
                } else {
                    _pendingDestination.value = destination
                    _showRoutePickerSheet.value = true
                }
            }
        }
    }

    fun confirmRouteSelection(route: ResolvedOutgoingRoute) {
        val dest = _pendingDestination.value ?: _inputNumber.value
        _showRoutePickerSheet.value = false
        _pendingDestination.value = null
        if (dest.isNotBlank()) {
            executeCall(dest, route)
        }
    }

    fun dismissRoutePicker() {
        _showRoutePickerSheet.value = false
        _pendingDestination.value = null
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    private fun executeCall(destination: String, route: ResolvedOutgoingRoute) {
        container.activeCallCoordinator.placeCall(destination, route)
    }

    fun buildAvailableRoutes(env: AvailableCallingEnvironment): List<ResolvedOutgoingRoute> {
        val routes = mutableListOf<ResolvedOutgoingRoute>()
        env.simSlots.forEach { sim ->
            routes.add(ResolvedOutgoingRoute.CellularSim(sim))
        }
        val defaultSip: SipAccountModel? = env.sipAccounts.firstOrNull { it.isEnabled && it.isDefault }
            ?: env.sipAccounts.firstOrNull { it.isEnabled }
        if (defaultSip != null) {
            routes.add(ResolvedOutgoingRoute.SipAccount(defaultSip))
        }
        return routes
    }

    private fun playDtmfTone(digit: Char) {
        val toneType = when (digit) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        runCatching { toneGenerator?.startTone(toneType, 90) }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}

class OmniCallViewModelFactory(
    private val application: Application,
    private val container: AppContainer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(DialerViewModel::class.java) ->
                DialerViewModel(application, container) as T
            modelClass.isAssignableFrom(RecentsViewModel::class.java) ->
                RecentsViewModel(container) as T
            modelClass.isAssignableFrom(RecordingsViewModel::class.java) ->
                RecordingsViewModel(application, container) as T
            modelClass.isAssignableFrom(ContactsViewModel::class.java) ->
                ContactsViewModel(container) as T
            modelClass.isAssignableFrom(SipSettingsViewModel::class.java) ->
                SipSettingsViewModel(container) as T
            modelClass.isAssignableFrom(BlocklistViewModel::class.java) ->
                BlocklistViewModel(container) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
