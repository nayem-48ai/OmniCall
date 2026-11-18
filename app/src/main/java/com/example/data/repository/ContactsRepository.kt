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
    val typeLabel: String,
    val isDefault: Boolean = false
)

data class ContactItem(
    val id: Long,
    val lookupKey: String,
    val displayName: String,
    val isFavorite: Boolean,
    val phoneNumbers: List<ContactPhoneNumber>,
    val photoUri: String?
) {
    val primaryNumber: String
        get() = phoneNumbers.firstOrNull { it.isDefault }?.number
            ?: phoneNumbers.firstOrNull()?.number.orEmpty()
}

data class ContactLookupResult(
    val displayName: String,
    val photoUri: String?
)

/**
 * Queries Android's [ContactsContract] safely for favorite contacts, all contacts,
 * default phone numbers (IS_SUPER_PRIMARY / IS_PRIMARY), contact photos (PHOTO_URI),
 * and fast caller name/photo lookup when [Manifest.permission.READ_CONTACTS] is granted.
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
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
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
                val idxPhotoUri = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val idxThumbUri = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val idxSuperPrimary = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY)
                val idxPrimary = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)

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
                    val photoFull = if (idxPhotoUri >= 0) cursor.getString(idxPhotoUri) else null
                    val photoThumb = if (idxThumbUri >= 0) cursor.getString(idxThumbUri) else null
                    val photo = photoFull?.takeIf { it.isNotBlank() } ?: photoThumb?.takeIf { it.isNotBlank() }
                    val isDefaultNumber =
                        (idxSuperPrimary >= 0 && cursor.getInt(idxSuperPrimary) == 1) ||
                            (idxPrimary >= 0 && cursor.getInt(idxPrimary) == 1)

                    val builder = contactMap.getOrPut(contactId) {
                        MutableContactBuilder(contactId, lookupKey, name, starred, photo)
                    }
                    if (builder.photoUri == null && photo != null) {
                        builder.photoUri = photo
                    }

                    val normalizedNew = normalizeDigits(rawNumber)
                    val existingIdx = builder.numbers.indexOfFirst { normalizeDigits(it.number) == normalizedNew }
                    if (existingIdx == -1) {
                        builder.numbers.add(ContactPhoneNumber(rawNumber, typeLabel, isDefaultNumber))
                    } else if (isDefaultNumber && !builder.numbers[existingIdx].isDefault) {
                        builder.numbers[existingIdx] = builder.numbers[existingIdx].copy(isDefault = true)
                    }
                }
            }
        }

        contactMap.values.map { builder ->
            // Place default (IS_SUPER_PRIMARY / IS_PRIMARY) phone number first
            val sortedNumbers = builder.numbers.sortedByDescending { it.isDefault }
            ContactItem(
                id = builder.id,
                lookupKey = builder.lookupKey,
                displayName = builder.displayName,
                isFavorite = builder.isFavorite,
                phoneNumbers = sortedNumbers,
                photoUri = builder.photoUri
            )
        }
    }

    suspend fun lookupContactInfo(phoneNumber: String): ContactLookupResult? = withContext(Dispatchers.IO) {
        if (!hasContactsPermission() || phoneNumber.isBlank()) return@withContext null
        val cleaned = phoneNumber.trim().removePrefix("sip:").substringBefore("@").trim()
        if (cleaned.isBlank()) return@withContext null

        // 1. Primary lookup via Android PhoneLookup (scan ALL rows so duplicate photo-less contacts
        // like "Ayesha" do not shadow another matching contact like "Ajrin Kona" that has a photo).
        val primaryHit = runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(cleaned)
            )
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI,
                    ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                    ContactsContract.PhoneLookup.CONTACT_ID
                ),
                null,
                null,
                null
            )?.use { cursor ->
                var fallbackName: String? = null
                var photoHitName: String? = null
                var bestPhotoUri: String? = null
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    if (name.isBlank()) continue
                    if (fallbackName == null) fallbackName = name
                    val fullPhoto = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    val thumbPhoto = cursor.getString(2)?.takeIf { it.isNotBlank() }
                    val contactId = if (!cursor.isNull(3)) cursor.getLong(3) else -1L
                    val resolvedPhoto = resolveReadablePhotoUri(fullPhoto, thumbPhoto, contactId)
                    if (!resolvedPhoto.isNullOrBlank()) {
                        photoHitName = name
                        bestPhotoUri = resolvedPhoto
                        break
                    }
                }
                val chosenName = photoHitName ?: fallbackName
                chosenName?.let { ContactLookupResult(displayName = it, photoUri = bestPhotoUri) }
            }
        }.getOrNull()
        if (primaryHit != null && !primaryHit.photoUri.isNullOrBlank()) {
            return@withContext primaryHit
        }

        // 2. Fallback: normalized trailing-digits lookup for local vs +country formatted numbers,
        // also continuing across rows until a saved photo is found if available.
        val queryDigits = cleaned.filter { it.isDigit() }
        val targetTail = if (queryDigits.length >= 8) queryDigits.takeLast(9) else queryDigits
        if (targetTail.isEmpty()) return@withContext primaryHit

        val secondaryHit = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                ),
                null,
                null,
                null
            )?.use { cursor ->
                var fallbackName: String? = null
                var photoHitName: String? = null
                var bestPhotoUri: String? = null
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    if (name.isBlank()) continue
                    val rawNum = cursor.getString(1).orEmpty()
                    val rowDigits = rawNum.filter { it.isDigit() }
                    val rowTail = if (rowDigits.length >= 8) rowDigits.takeLast(9) else rowDigits
                    val matches = rowTail == targetTail ||
                        (rowDigits.isNotEmpty() && rowDigits == queryDigits)
                    if (matches) {
                        if (fallbackName == null) fallbackName = name
                        val fullPhoto = cursor.getString(2)?.takeIf { it.isNotBlank() }
                        val thumbPhoto = cursor.getString(3)?.takeIf { it.isNotBlank() }
                        val contactId = if (!cursor.isNull(4)) cursor.getLong(4) else -1L
                        val resolvedPhoto = resolveReadablePhotoUri(fullPhoto, thumbPhoto, contactId)
                        if (!resolvedPhoto.isNullOrBlank()) {
                            photoHitName = name
                            bestPhotoUri = resolvedPhoto
                            break
                        }
                    }
                }
                val chosenName = photoHitName ?: fallbackName
                chosenName?.let { ContactLookupResult(displayName = it, photoUri = bestPhotoUri) }
            }
        }.getOrNull()

        return@withContext when {
            secondaryHit != null && !secondaryHit.photoUri.isNullOrBlank() -> secondaryHit
            primaryHit != null -> primaryHit
            else -> secondaryHit
        }
    }

    private fun resolveReadablePhotoUri(
        fullPhoto: String?,
        thumbPhoto: String?,
        contactId: Long
    ): String? {
        val candidates = buildList {
            if (!fullPhoto.isNullOrBlank()) add(fullPhoto)
            if (!thumbPhoto.isNullOrBlank() && thumbPhoto != fullPhoto) add(thumbPhoto)
            if (contactId > 0L) {
                val contactPhotoUri = android.content.ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI,
                    contactId
                ).buildUpon()
                    .appendPath(ContactsContract.Contacts.Photo.CONTENT_DIRECTORY)
                    .build()
                    .toString()
                add(contactPhotoUri)
            }
        }
        for (candidate in candidates) {
            val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: continue
            val readableAfd = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } ?: false
            }.getOrDefault(false)
            if (readableAfd) return candidate

            val readableStream = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
            }.getOrDefault(false)
            if (readableStream) return candidate
        }
        return fullPhoto ?: thumbPhoto
    }

    suspend fun lookupDisplayName(phoneNumber: String): String? =
        lookupContactInfo(phoneNumber)?.displayName

    private fun normalizeDigits(number: String): String =
        number.filter { it.isDigit() || it == '+' }

    private class MutableContactBuilder(
        val id: Long,
        val lookupKey: String,
        val displayName: String,
        val isFavorite: Boolean,
        var photoUri: String?,
        val numbers: MutableList<ContactPhoneNumber> = mutableListOf()
    )
}
