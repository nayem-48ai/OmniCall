package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.repository.ContactItem
import com.example.data.repository.VcfContactsCodec
import com.example.data.repository.VcfParsedContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VcfTransferUiState(
    val showTransferMenu: Boolean = false,
    val showExportSheet: Boolean = false,
    val exportSelectedIds: Set<Long> = emptySet(),
    val showImportSheet: Boolean = false,
    val importFileName: String = "",
    val parsedImportContacts: List<VcfParsedContact> = emptyList(),
    val importSelectedIds: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val statusMessage: String? = null
)

data class ContactsUiState(
    val hasPermission: Boolean = false,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val favoriteContacts: List<ContactItem> = emptyList(),
    val allContacts: List<ContactItem> = emptyList(),
    val unfilteredContacts: List<ContactItem> = emptyList(),
    val selectedContact: ContactItem? = null,
    val transfer: VcfTransferUiState = VcfTransferUiState()
)

class ContactsViewModel(private val container: AppContainer) : ViewModel() {

    private val _hasPermission = MutableStateFlow(container.contactsRepository.hasContactsPermission())
    private val _isLoading = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _contacts = MutableStateFlow<List<ContactItem>>(emptyList())
    private val _selectedContact = MutableStateFlow<ContactItem?>(null)
    private val _transfer = MutableStateFlow(VcfTransferUiState())

    init {
        refreshContacts()
    }

    val uiState: StateFlow<ContactsUiState> = combine(
        _hasPermission,
        _isLoading,
        _searchQuery,
        _contacts,
        _selectedContact
    ) { hasPerm, loading, query, contacts, selected ->
        val q = query.trim().lowercase()
        val filtered = if (q.isBlank()) {
            contacts
        } else {
            val digitsQuery = q.filter { it.isDigit() }
            contacts.filter { contact ->
                contact.displayName.lowercase().contains(q) ||
                    contact.phoneNumbers.any { phone ->
                        phone.number.lowercase().contains(q) ||
                            (digitsQuery.isNotEmpty() && phone.number.filter { it.isDigit() }.contains(digitsQuery))
                    }
            }
        }
        ContactsUiState(
            hasPermission = hasPerm,
            isLoading = loading,
            searchQuery = query,
            favoriteContacts = filtered.filter { it.isFavorite },
            allContacts = filtered,
            unfilteredContacts = contacts,
            selectedContact = selected
        )
    }.combine(_transfer) { state, transfer ->
        state.copy(transfer = transfer)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ContactsUiState(hasPermission = _hasPermission.value)
    )

    fun refreshContacts() {
        val permitted = container.contactsRepository.hasContactsPermission()
        _hasPermission.value = permitted
        if (!permitted) {
            _contacts.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _contacts.value = container.contactsRepository.loadContacts()
            _isLoading.value = false
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openContactDetail(contact: ContactItem) {
        _selectedContact.value = contact
    }

    fun closeContactDetail() {
        _selectedContact.value = null
    }

    fun openTransferMenu() {
        _transfer.update { it.copy(showTransferMenu = true) }
    }

    fun closeTransferMenu() {
        _transfer.update { it.copy(showTransferMenu = false) }
    }

    fun openExportSheet() {
        val allIds = _contacts.value.map { it.id }.toSet()
        _transfer.update {
            it.copy(
                showTransferMenu = false,
                showExportSheet = true,
                exportSelectedIds = allIds
            )
        }
    }

    fun closeExportSheet() {
        _transfer.update { it.copy(showExportSheet = false) }
    }

    fun toggleExportSelection(contactId: Long) {
        _transfer.update { current ->
            val updated = current.exportSelectedIds.toMutableSet()
            if (!updated.add(contactId)) updated.remove(contactId)
            current.copy(exportSelectedIds = updated)
        }
    }

    fun setAllExportSelected(selectAll: Boolean) {
        _transfer.update { current ->
            val updated = if (selectAll) _contacts.value.map { it.id }.toSet() else emptySet()
            current.copy(exportSelectedIds = updated)
        }
    }

    fun buildSelectedExportVcf(): String {
        val selectedIds = _transfer.value.exportSelectedIds
        val selectedContacts = _contacts.value.filter { it.id in selectedIds }
        return VcfContactsCodec.generateVcf(selectedContacts)
    }

    fun markExportCompleted(count: Int, success: Boolean) {
        _transfer.update {
            it.copy(
                showExportSheet = false,
                isBusy = false,
                statusMessage = if (success) {
                    "Exported $count contact${if (count == 1) "" else "s"} to .vcf"
                } else {
                    "Could not write .vcf export file"
                }
            )
        }
    }

    fun loadParsedVcfForImport(fileName: String, vcfRawText: String) {
        val parsed = VcfContactsCodec.parseVcf(vcfRawText)
        if (parsed.isEmpty()) {
            _transfer.update {
                it.copy(
                    showTransferMenu = false,
                    showImportSheet = false,
                    statusMessage = "No valid vCard contacts found in $fileName"
                )
            }
            return
        }
        _transfer.update {
            it.copy(
                showTransferMenu = false,
                showImportSheet = true,
                importFileName = fileName,
                parsedImportContacts = parsed,
                importSelectedIds = parsed.map { item -> item.id }.toSet()
            )
        }
    }

    fun closeImportSheet() {
        _transfer.update {
            it.copy(
                showImportSheet = false,
                parsedImportContacts = emptyList(),
                importSelectedIds = emptySet(),
                importFileName = ""
            )
        }
    }

    fun toggleImportSelection(vcfContactId: String) {
        _transfer.update { current ->
            val updated = current.importSelectedIds.toMutableSet()
            if (!updated.add(vcfContactId)) updated.remove(vcfContactId)
            current.copy(importSelectedIds = updated)
        }
    }

    fun setAllImportSelected(selectAll: Boolean) {
        _transfer.update { current ->
            val updated = if (selectAll) {
                current.parsedImportContacts.map { it.id }.toSet()
            } else {
                emptySet()
            }
            current.copy(importSelectedIds = updated)
        }
    }

    fun hasWriteContactsPermission(): Boolean = container.contactsRepository.hasWriteContactsPermission()

    fun importSelectedVcfContacts(onComplete: () -> Unit = {}) {
        val state = _transfer.value
        val toImport = state.parsedImportContacts.filter { it.id in state.importSelectedIds }
        if (toImport.isEmpty()) return

        viewModelScope.launch {
            _transfer.update { it.copy(isBusy = true) }
            val count = container.contactsRepository.importVcfContacts(toImport)
            if (count > 0) {
                refreshContacts()
                onComplete()
            }
            _transfer.update {
                it.copy(
                    isBusy = false,
                    showImportSheet = false,
                    parsedImportContacts = emptyList(),
                    importSelectedIds = emptySet(),
                    statusMessage = if (count > 0) {
                        "Imported $count contact${if (count == 1) "" else "s"} from ${state.importFileName}"
                    } else {
                        "Failed to import contacts. Check Contacts permission."
                    }
                )
            }
        }
    }

    fun showTransferError(message: String) {
        _transfer.update { it.copy(isBusy = false, statusMessage = message) }
    }

    fun clearTransferStatus() {
        _transfer.update { it.copy(statusMessage = null) }
    }
}
