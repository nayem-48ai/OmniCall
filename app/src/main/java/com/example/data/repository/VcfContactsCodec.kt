package com.example.data.repository

import java.io.ByteArrayOutputStream
import java.util.UUID

data class VcfParsedContact(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val phoneNumbers: List<ContactPhoneNumber>,
    val email: String? = null,
    val organization: String? = null,
    val note: String? = null
)

/**
 * Pure-Kotlin vCard (.vcf) codec supporting vCard 2.1, 3.0, and 4.0 import parsing
 * (including line unfolding, QUOTED-PRINTABLE UTF-8 decoding, TEL types, and PREF flags)
 * and standards-compliant vCard 3.0 export generation.
 */
object VcfContactsCodec {

    fun generateVcf(contacts: List<ContactItem>): String {
        if (contacts.isEmpty()) return ""
        val sb = StringBuilder()
        contacts.forEach { contact ->
            val safeName = escapeVcardValue(contact.displayName.ifBlank { "Unknown" })
            sb.append("BEGIN:VCARD\r\n")
            sb.append("VERSION:3.0\r\n")
            sb.append("FN:").append(safeName).append("\r\n")
            sb.append("N:;").append(safeName).append(";;;\r\n")
            contact.phoneNumbers.forEach { phone ->
                val cleanNum = phone.number.trim()
                if (cleanNum.isNotEmpty()) {
                    val vcardType = mapLabelToVcardType(phone.typeLabel)
                    val prefParam = if (phone.isDefault) ";TYPE=PREF" else ""
                    sb.append("TEL;TYPE=")
                        .append(vcardType)
                        .append(prefParam)
                        .append(":")
                        .append(cleanNum)
                        .append("\r\n")
                }
            }
            sb.append("END:VCARD\r\n")
        }
        return sb.toString()
    }

    fun parseVcf(rawContent: String): List<VcfParsedContact> {
        if (rawContent.isBlank()) return emptyList()
        val unfoldedLines = unfoldLines(rawContent)
        val result = mutableListOf<VcfParsedContact>()

        var inCard = false
        var fullName: String? = null
        var structuredName: String? = null
        var email: String? = null
        var org: String? = null
        var note: String? = null
        val phones = mutableListOf<ContactPhoneNumber>()
        val seenNumbers = hashSetOf<String>()

        fun resetCard() {
            inCard = false
            fullName = null
            structuredName = null
            email = null
            org = null
            note = null
            phones.clear()
            seenNumbers.clear()
        }

        for (line in unfoldedLines) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VCARD", ignoreCase = true)) {
                resetCard()
                inCard = true
                continue
            }
            if (trimmed.equals("END:VCARD", ignoreCase = true)) {
                if (inCard) {
                    val resolvedName = fullName?.takeIf { it.isNotBlank() }
                        ?: structuredName?.takeIf { it.isNotBlank() }
                        ?: org?.takeIf { it.isNotBlank() }
                        ?: phones.firstOrNull()?.number
                        ?: "Unnamed Contact"

                    if (phones.isNotEmpty() || !fullName.isNullOrBlank() || !structuredName.isNullOrBlank()) {
                        result.add(
                            VcfParsedContact(
                                displayName = resolvedName,
                                phoneNumbers = phones.toList(),
                                email = email?.takeIf { it.isNotBlank() },
                                organization = org?.takeIf { it.isNotBlank() },
                                note = note?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
                resetCard()
                continue
            }
            if (!inCard) continue

            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) continue

            val headerPart = line.substring(0, colonIdx)
            val rawValue = line.substring(colonIdx + 1)
            val segments = headerPart.split(';')
            val propName = segments.first().substringAfterLast('.').trim().uppercase()
            val params = segments.drop(1).map { it.trim() }

            val isQuotedPrintable = params.any {
                it.equals("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) ||
                    it.equals("QUOTED-PRINTABLE", ignoreCase = true)
            }
            val decodedValue = if (isQuotedPrintable) {
                decodeQuotedPrintable(rawValue)
            } else {
                unescapeVcardValue(rawValue)
            }.trim()

            when (propName) {
                "FN" -> {
                    if (decodedValue.isNotBlank()) {
                        fullName = decodedValue
                    }
                }
                "N" -> {
                    val parts = decodedValue.split(';').map { it.trim() }
                    val family = parts.getOrNull(0).orEmpty()
                    val given = parts.getOrNull(1).orEmpty()
                    val middle = parts.getOrNull(2).orEmpty()
                    val assembled = listOf(given, middle, family)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    if (assembled.isNotBlank()) {
                        structuredName = assembled
                    }
                }
                "TEL" -> {
                    val number = rawValue.trim()
                        .removePrefix("tel:")
                        .removePrefix("TEL:")
                        .trim()
                    if (number.isNotBlank()) {
                        val normKey = number.filter { it.isDigit() || it == '+' }.ifEmpty { number }
                        if (seenNumbers.add(normKey)) {
                            val typeTokens = extractTypeTokens(params)
                            val isPref = typeTokens.any { it == "PREF" } ||
                                params.any { it.startsWith("PREF=", ignoreCase = true) }
                            val label = mapVcardTypeToLabel(typeTokens)
                            phones.add(
                                ContactPhoneNumber(
                                    number = number,
                                    typeLabel = label,
                                    isDefault = isPref
                                )
                            )
                        }
                    }
                }
                "EMAIL" -> {
                    if (email == null && decodedValue.isNotBlank()) {
                        email = decodedValue
                    }
                }
                "ORG" -> {
                    if (org == null && decodedValue.isNotBlank()) {
                        org = decodedValue.replace(';', ' ').trim()
                    }
                }
                "NOTE" -> {
                    if (note == null && decodedValue.isNotBlank()) {
                        note = decodedValue
                    }
                }
            }
        }
        return result
    }

    private fun unfoldLines(raw: String): List<String> {
        val physical = raw.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val out = mutableListOf<String>()
        var quotedPrintableSoftContinue = false

        for (line in physical) {
            if (quotedPrintableSoftContinue && out.isNotEmpty()) {
                val prev = out.removeAt(out.lastIndex).dropLast(1)
                val combined = prev + line
                out.add(combined)
                quotedPrintableSoftContinue = combined.contains("QUOTED-PRINTABLE", ignoreCase = true) ||
                    (quotedPrintableSoftContinue && combined.endsWith("="))
                // Re-evaluate trailing soft break cleanly
                quotedPrintableSoftContinue = combined.endsWith("=")
            } else if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                val prev = out.removeAt(out.lastIndex)
                val combined = prev + line.substring(1)
                out.add(combined)
                quotedPrintableSoftContinue = combined.contains("QUOTED-PRINTABLE", ignoreCase = true) &&
                    combined.endsWith("=")
            } else {
                out.add(line)
                quotedPrintableSoftContinue = line.contains("QUOTED-PRINTABLE", ignoreCase = true) &&
                    line.endsWith("=")
            }
        }
        return out
    }

    private fun extractTypeTokens(params: List<String>): List<String> {
        val tokens = mutableListOf<String>()
        params.forEach { param ->
            val cleaned = if (param.contains('=')) {
                val key = param.substringBefore('=').trim().uppercase()
                val value = param.substringAfter('=').trim().uppercase()
                if (key == "TYPE") value else ""
            } else {
                param.trim().uppercase()
            }
            if (cleaned.isNotEmpty()) {
                cleaned.split(',').forEach { part ->
                    val token = part.trim().removeSurrounding("\"")
                    if (token.isNotEmpty()) tokens.add(token)
                }
            }
        }
        return tokens
    }

    private fun mapLabelToVcardType(typeLabel: String): String {
        val upper = typeLabel.trim().uppercase()
        return when {
            upper.contains("MOBILE") || upper.contains("CELL") -> "CELL"
            upper.contains("WORK") || upper.contains("OFFICE") -> "WORK"
            upper.contains("HOME") -> "HOME"
            upper.contains("MAIN") -> "MAIN"
            upper.contains("FAX") -> "FAX"
            else -> "VOICE"
        }
    }

    private fun mapVcardTypeToLabel(tokens: List<String>): String {
        return when {
            tokens.any { it == "CELL" || it == "MOBILE" } -> "Mobile"
            tokens.any { it == "WORK" } -> "Work"
            tokens.any { it == "HOME" } -> "Home"
            tokens.any { it == "MAIN" } -> "Main"
            tokens.any { it == "FAX" } -> "Fax"
            else -> "Phone"
        }
    }

    private fun escapeVcardValue(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
    }

    private fun unescapeVcardValue(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\;", ";")
            .replace("\\,", ",")
            .replace("\\\\", "\\")
    }

    private fun decodeQuotedPrintable(input: String): String {
        val cleaned = input.replace("=\r\n", "").replace("=\n", "")
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < cleaned.length) {
            val ch = cleaned[i]
            if (ch == '=' && i + 2 < cleaned.length) {
                val hex = cleaned.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    out.write(byteVal)
                    i += 3
                    continue
                }
            }
            out.write(ch.code and 0xFF)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
