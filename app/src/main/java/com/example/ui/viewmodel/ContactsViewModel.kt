package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.repository.ContactItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactsUiState(
    val hasPermission: Boolean = false,
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val favoriteContacts: List<ContactItem> = emptyList(),
    val allContacts: List<ContactItem> = emptyList(),
    val selectedContact: ContactItem? = null
)

class ContactsViewModel(private val container: AppContainer) : ViewModel() {

    private val _hasPermission = MutableStateFlow(container.contactsRepository.hasContactsPermission())
    private val _isLoading = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _contacts = MutableStateFlow<List<ContactItem>>(emptyList())
    private val _selectedContact = MutableStateFlow<ContactItem?>(null)

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
            selectedContact = selected
        )
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
}
