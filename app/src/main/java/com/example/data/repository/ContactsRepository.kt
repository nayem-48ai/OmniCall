package com.example.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactPhoneNumber(
    val number: String,
    val typeLabel: String
)

data class ContactItem(
    val id: Long,
    val lookupKey: String,
    val displayName: String,
    val isFavorite: Boolean,
    val phoneNumbers: List<ContactPhoneNumber>,
    val photoUri: String?
) {
    val primaryNumber: String get() = phoneNumbers.firstOrNull()?.number.orEmpty()
}

/**
 * Queries Android's [ContactsContract] safely for favorite contacts, all contacts,
 * and caller name lookup when [Manifest.permission.READ_CONTACTS] is granted.
 */
class ContactsRepository(private val context: Context) {

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun loadContacts(): List<ContactItem> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission()) return@withContext emptyList()

        val contactMap = linkedMapOf<Long, MutableContactBuilder>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.STARRED,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idxId = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val idxLookup = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
                val idxName = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val idxNumber = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val idxType = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val idxLabel = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                val idxStarred = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.STARRED)
                val idxPhoto = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idxId)
                    val lookupKey = cursor.getString(idxLookup).orEmpty()
                    val name = cursor.getString(idxName).orEmpty().ifBlank { "Unknown" }
                    val rawNumber = cursor.getString(idxNumber).orEmpty().trim()
                    if (rawNumber.isBlank()) continue
                    val typeInt = cursor.getInt(idxType)
                    val customLabel = cursor.getString(idxLabel)
                    val typeLabel = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources,
                        typeInt,
                        customLabel
                    ).toString()
                    val starred = cursor.getInt(idxStarred) == 1
                    val photo = cursor.getString(idxPhoto)

                    val builder = contactMap.getOrPut(contactId) {
                        MutableContactBuilder(contactId, lookupKey, name, starred, photo)
                    }
                    val normalizedNew = normalizeDigits(rawNumber)
                    if (builder.numbers.none { normalizeDigits(it.number) == normalizedNew }) {
                        builder.numbers.add(ContactPhoneNumber(rawNumber, typeLabel))
                    }
                }
            }
        }

        contactMap.values.map {
            ContactItem(
                id = it.id,
                lookupKey = it.lookupKey,
                displayName = it.displayName,
                isFavorite = it.isFavorite,
                phoneNumbers = it.numbers.toList(),
                photoUri = it.photoUri
            )
        }
    }

    suspend fun lookupDisplayName(phoneNumber: String): String? = withContext(Dispatchers.IO) {
        if (!hasContactsPermission() || phoneNumber.isBlank()) return@withContext null
        runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun normalizeDigits(number: String): String =
        number.filter { it.isDigit() || it == '+' }

    private class MutableContactBuilder(
        val id: Long,
        val lookupKey: String,
        val displayName: String,
        val isFavorite: Boolean,
        val photoUri: String?,
        val numbers: MutableList<ContactPhoneNumber> = mutableListOf()
    )
}
