package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.preferences.AppSettingsState
import com.example.domain.model.AvailableCallingEnvironment
import com.example.domain.model.OutgoingRoutingMode
import com.example.domain.model.RecordingFormat
import com.example.domain.model.RecordingType
import com.example.domain.model.SimSlotInfo
import com.example.domain.model.SipAccountModel
import com.example.domain.model.SipBalancePreset
import com.example.domain.model.SipRegistrationStatus
import com.example.domain.model.SipTransport
import com.example.sip.AutoConfigProgress
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SipFormState(
    val editingAccountId: Long = 0L,
    val displayName: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val optionalPort: String = "",
    val isDiscovering: Boolean = false,
    val discoveryStatusText: String = "",
    val discoveryStep: Int = 0,
    val discoveryTotalSteps: Int = 5,
    val discoveryLogs: List<String> = emptyList(),
    val discoveredTransport: SipTransport? = null,
    val discoveredPort: Int? = null,
    val errorMessage: String? = null,
    val savedSuccess: Boolean = false
)

data class SipSettingsUiState(
    val accounts: List<SipAccountModel> = emptyList(),
    val simSlots: List<SimSlotInfo> = emptyList(),
    val isDefaultDialer: Boolean = false,
    val recordingsCount: Int = 0,
    val settings: AppSettingsState = AppSettingsState(),
    val environment: AvailableCallingEnvironment = AvailableCallingEnvironment(),
    val showAddAccountSheet: Boolean = false,
    val form: SipFormState = SipFormState(),
    val managedAccount: SipAccountModel? = null,
    val isCheckingBalance: Boolean = false
)

class SipSettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val _simSlots = MutableStateFlow<List<SimSlotInfo>>(emptyList())
    private val _isDefaultDialer = MutableStateFlow(false)
    private val _showSheet = MutableStateFlow(false)
    private val _form = MutableStateFlow(SipFormState())
    private val _managedAccountId = MutableStateFlow<Long?>(null)
    private val _isCheckingBalance = MutableStateFlow(false)

    private var discoveryJob: Job? = null

    init {
        refreshEnvironment()
    }

    val uiState: StateFlow<SipSettingsUiState> = combine(
        container.sipAccountRepository.accountsFlow,
        _simSlots,
        container.preferencesRepository.settingsFlow,
        _showSheet,
        _form
    ) { accounts, sims, settings, showSheet, form ->
        val env = AvailableCallingEnvironment(
            simSlots = sims,
            sipAccounts = accounts,
            selectedMode = settings.outgoingRoutingMode,
            isDefaultDialer = _isDefaultDialer.value
        )
        SipSettingsUiState(
            accounts = accounts,
            simSlots = sims,
            isDefaultDialer = _isDefaultDialer.value,
            settings = settings,
            environment = env,
            showAddAccountSheet = showSheet,
            form = form
        )
    }.combine(_isDefaultDialer) { state, isDefault ->
        state.copy(
            isDefaultDialer = isDefault,
            environment = state.environment.copy(isDefaultDialer = isDefault)
        )
    }.combine(container.recordingRepository.recordingsFlow) { state, recordings ->
        state.copy(recordingsCount = recordings.size)
    }.combine(_managedAccountId) { state, managedId ->
        state.copy(
            managedAccount = managedId?.let { id -> state.accounts.firstOrNull { it.id == id } }
        )
    }.combine(_isCheckingBalance) { state, checking ->
        state.copy(isCheckingBalance = checking)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SipSettingsUiState()
    )

    fun refreshEnvironment() {
        _simSlots.value = container.simSubscriptionDetector.detectActiveSims()
        _isDefaultDialer.value = container.telecomCallManager.isDefaultDialer()
    }

    fun openAddAccountSheet() {
        _form.value = SipFormState()
        _showSheet.value = true
    }

    fun closeAccountSheet() {
        discoveryJob?.cancel()
        _showSheet.value = false
    }

    fun updateForm(
        displayName: String? = null,
        domain: String? = null,
        username: String? = null,
        password: String? = null,
        optionalPort: String? = null
    ) {
        _form.update { current ->
            current.copy(
                displayName = displayName ?: current.displayName,
                domain = domain ?: current.domain,
                username = username ?: current.username,
                password = password ?: current.password,
                optionalPort = optionalPort ?: current.optionalPort,
                errorMessage = null
            )
        }
    }

    /**
     * Triggers Smart SIP Auto-Configuration:
     * Probes DNS SRV -> UDP -> TCP -> TLS and verifies REGISTER Digest Auth automatically,
     * locking in optimal transport & port without requiring manual protocol selection.
     */
    fun runSmartAutoConfigureAndSave() {
        val f = _form.value
        val host = f.domain.trim()
        val user = f.username.trim()
        val pwd = f.password
        val portOverride = f.optionalPort.trim().toIntOrNull()

        if (host.isBlank() || user.isBlank() || pwd.isBlank()) {
            _form.update { it.copy(errorMessage = "Please enter Host/Domain, Username, and Password.") }
            return
        }

        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _form.update {
                it.copy(
                    isDiscovering = true,
                    errorMessage = null,
                    discoveryLogs = emptyList(),
                    discoveryStatusText = "Starting smart transport discovery...",
                    discoveryStep = 0
                )
            }

            container.sipAutoConfigEngine.autoConfigure(host, user, pwd, portOverride)
                .collect { progress ->
                    when (progress) {
                        is AutoConfigProgress.Step -> {
                            _form.update {
                                it.copy(
                                    discoveryStatusText = progress.message,
                                    discoveryStep = progress.stepIndex,
                                    discoveryTotalSteps = progress.totalSteps
                                )
                            }
                        }
                        is AutoConfigProgress.Success -> {
                            val label = f.displayName.trim().ifBlank { "$user@$host" }
                            val model = SipAccountModel(
                                id = f.editingAccountId,
                                displayName = label,
                                domain = progress.resolvedHost,
                                username = user,
                                port = progress.port,
                                transport = progress.transport,
                                autoDiscovered = true,
                                isEnabled = true,
                                isDefault = true,
                                status = SipRegistrationStatus.REGISTERED,
                                lastRegisteredAtMs = System.currentTimeMillis()
                            )
                            val savedId = container.sipAccountRepository.saveAccountWithPassword(model, pwd)
                            val savedModel = model.copy(id = savedId)
                            container.sipClientEngine.startAccountRegistration(savedModel)

                            _form.update {
                                it.copy(
                                    isDiscovering = false,
                                    discoveredTransport = progress.transport,
                                    discoveredPort = progress.port,
                                    discoveryLogs = progress.diagnosticLog,
                                    discoveryStatusText = "Locked ${progress.transport.label}:${progress.port} (${progress.latencyMs}ms)",
                                    savedSuccess = true
                                )
                            }
                            _showSheet.value = false
                        }
                        is AutoConfigProgress.Failure -> {
                            _form.update {
                                it.copy(
                                    isDiscovering = false,
                                    discoveryLogs = progress.diagnosticLog,
                                    errorMessage = progress.reason
                                )
                            }
                        }
                    }
                }
        }
    }

    /**
     * Allows saving credentials even when offline/behind VPN so user can re-register later.
     */
    fun saveOfflineWithDefaults() {
        val f = _form.value
        val host = f.domain.trim()
        val user = f.username.trim()
        val pwd = f.password
        if (host.isBlank() || user.isBlank() || pwd.isBlank()) return

        discoveryJob?.cancel()
        viewModelScope.launch {
            val port = f.optionalPort.trim().toIntOrNull() ?: 5060
            val model = SipAccountModel(
                id = f.editingAccountId,
                displayName = f.displayName.trim().ifBlank { "$user@$host" },
                domain = host,
                username = user,
                port = port,
                transport = SipTransport.UDP,
                autoDiscovered = false,
                isEnabled = true,
                isDefault = true,
                status = SipRegistrationStatus.UNREGISTERED,
                lastError = "Saved without live probe"
            )
            val id = container.sipAccountRepository.saveAccountWithPassword(model, pwd)
            container.sipClientEngine.startAccountRegistration(model.copy(id = id))
            _showSheet.value = false
        }
    }

    fun reRegisterAccount(account: SipAccountModel) {
        container.sipClientEngine.startAccountRegistration(account)
    }

    fun setDefaultSipAccount(accountId: Long) {
        viewModelScope.launch {
            container.sipAccountRepository.setDefaultAccount(accountId)
            val acc = container.sipAccountRepository.getDefaultActiveAccount()
            if (acc != null) container.sipClientEngine.startAccountRegistration(acc)
        }
    }

    fun toggleAccountEnabled(account: SipAccountModel, enabled: Boolean) {
        viewModelScope.launch {
            container.sipAccountRepository.setEnabled(account, enabled)
            if (enabled) {
                container.sipClientEngine.startAccountRegistration(account.copy(isEnabled = true))
            } else {
                container.sipClientEngine.stopTransport()
                container.sipAccountRepository.updateRegistrationStatus(
                    account.id,
                    SipRegistrationStatus.UNREGISTERED
                )
            }
        }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch {
            container.sipAccountRepository.deleteAccount(accountId)
        }
    }

    fun openManageAccount(account: SipAccountModel) {
        _managedAccountId.value = account.id
    }

    fun closeManageAccount() {
        _managedAccountId.value = null
    }

    fun saveAccountBalanceSettings(
        accountId: Long,
        preset: SipBalancePreset,
        customUrl: String,
        showInKeypad: Boolean,
        checkNow: Boolean
    ) {
        viewModelScope.launch {
            container.sipAccountRepository.updateBalanceConfig(
                accountId = accountId,
                preset = preset,
                customUrl = customUrl,
                showInKeypad = showInKeypad
            )
            if (checkNow && preset != SipBalancePreset.NONE) {
                val updated = container.sipAccountRepository.getById(accountId) ?: return@launch
                _isCheckingBalance.value = true
                container.sipBalanceFetcher.fetchBalance(updated)
                _isCheckingBalance.value = false
            }
        }
    }

    fun checkAccountBalance(account: SipAccountModel) {
        viewModelScope.launch {
            _isCheckingBalance.value = true
            container.sipBalanceFetcher.fetchBalance(account)
            _isCheckingBalance.value = false
        }
    }

    fun setOutgoingRoutingMode(mode: OutgoingRoutingMode) {
        viewModelScope.launch {
            container.preferencesRepository.setOutgoingRoutingMode(mode)
        }
    }

    fun setRecordingFormat(format: RecordingFormat) {
        viewModelScope.launch {
            container.preferencesRepository.setRecordingFormat(format)
        }
    }

    fun setRecordingType(type: RecordingType) {
        viewModelScope.launch {
            container.preferencesRepository.setRecordingType(type)
        }
    }

    fun setAudioProcessing(
        wideband: Boolean? = null,
        aec: Boolean? = null,
        ns: Boolean? = null,
        agc: Boolean? = null
    ) {
        viewModelScope.launch {
            container.preferencesRepository.setAudioProcessing(wideband, aec, ns, agc)
        }
    }

    fun setThemePreference(pref: ThemePreference) {
        viewModelScope.launch {
            container.preferencesRepository.setThemePreference(pref)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setDynamicColor(enabled)
        }
    }
}
