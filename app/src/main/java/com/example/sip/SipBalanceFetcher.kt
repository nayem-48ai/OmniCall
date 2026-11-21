package com.example.sip

import com.example.data.repository.SipAccountRepository
import com.example.domain.model.SipAccountModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface BalanceFetchResult {
    data class Success(val balance: String, val rawSnippet: String = "") : BalanceFetchResult
    data class Failure(val reason: String) : BalanceFetchResult
}

/**
 * Fetches SIP account Balance from provider endpoints (e.g., ICC Communication OTTSSO or Custom URL)
 * using HTTP/1.1 form-urlencoded POST with stored SIP username & password.
 *
 * Strictly extracts **Balance** (explicitly ignoring **Credit** fields/labels).
 */
class SipBalanceFetcher(
    private val sipAccountRepository: SipAccountRepository
) {
    suspend fun fetchBalance(account: SipAccountModel): BalanceFetchResult = withContext(Dispatchers.IO) {
        val urlString = account.resolvedBalanceUrl()
        if (urlString.isBlank()) {
            return@withContext BalanceFetchResult.Failure("No balance check URL configured")
        }

        val password = sipAccountRepository.getPassword(account.id).orEmpty()
        if (account.username.isBlank() || password.isBlank()) {
            return@withContext BalanceFetchResult.Failure("Missing SIP username or password")
        }

        return@withContext try {
            val responseBody = executeHttpPostWithRedirects(
                targetUrl = urlString,
                username = account.username,
                password = password
            )
            val parsedBalance = BalanceResponseParser.extractBalance(responseBody)
            if (parsedBalance != null) {
                sipAccountRepository.updateBalanceResult(
                    accountId = account.id,
                    balance = parsedBalance,
                    error = null,
                    updatedMs = System.currentTimeMillis()
                )
                BalanceFetchResult.Success(balance = parsedBalance)
            } else {
                val err = BalanceResponseParser.detectAuthOrServerError(responseBody)
                    ?: "Balance value not found in provider response"
                sipAccountRepository.updateBalanceResult(
                    accountId = account.id,
                    balance = account.lastBalance,
                    error = err,
                    updatedMs = System.currentTimeMillis()
                )
                BalanceFetchResult.Failure(err)
            }
        } catch (t: Throwable) {
            val msg = t.message?.take(80) ?: "Network error while checking balance"
            sipAccountRepository.updateBalanceResult(
                accountId = account.id,
                balance = account.lastBalance,
                error = msg,
                updatedMs = System.currentTimeMillis()
            )
            BalanceFetchResult.Failure(msg)
        }
    }

    private fun executeHttpPostWithRedirects(
        targetUrl: String,
        username: String,
        password: String
    ): String {
        val validatedInitial = validateBalanceUrl(targetUrl)
            ?: throw IllegalArgumentException("Balance URL must use HTTPS")

        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        var currentUrl = validatedInitial.toExternalForm()
        var method = "POST"
        val postBody = "username=${URLEncoder.encode(username, "UTF-8")}" +
            "&password=${URLEncoder.encode(password, "UTF-8")}"

        for (hop in 0 until 6) {
            val parsedUrl = validateBalanceUrl(currentUrl)
                ?: throw IllegalStateException("Refusing non-HTTPS balance endpoint")
            val uri = parsedUrl.toURI()
            val conn = (parsedUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 15_000
                useCaches = false
                setRequestProperty("Connection", "close")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; RMX3286 Build/SP1A.210812.016) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.8103.40 Mobile Safari/537.36"
                )
                val hostHeader = if (parsedUrl.port > 0) "${parsedUrl.host}:${parsedUrl.port}" else parsedUrl.host
                setRequestProperty("Host", hostHeader)
                setRequestProperty("Accept", "application/json, text/html, */*")

                val cookieHeaders = runCatching { cookieManager.get(uri, emptyMap())["Cookie"] }.getOrNull()
                if (!cookieHeaders.isNullOrEmpty()) {
                    setRequestProperty("Cookie", cookieHeaders.joinToString("; "))
                }

                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }

            if (method == "POST") {
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(postBody)
                    writer.flush()
                }
            }

            val code = conn.responseCode
            runCatching { cookieManager.put(uri, conn.headerFields) }
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")?.trim()
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect missing Location header (HTTP $code)")
                }
                val resolvedNext = URL(parsedUrl, location).toExternalForm()
                if (!isSafeRedirectTarget(validatedInitial.toExternalForm(), resolvedNext)) {
                    throw IllegalStateException("Blocked unsafe redirect target")
                }
                currentUrl = resolvedNext
                method = if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER
                ) {
                    "GET"
                } else {
                    method
                }
                continue
            }

            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()
            conn.disconnect()

            if (code >= 400 && body.isBlank()) {
                throw IllegalStateException("Server returned HTTP $code")
            }
            return body
        }
        throw IllegalStateException("Too many redirects")
    }

    companion object {
        fun validateBalanceUrl(rawUrl: String): URL? {
            val parsed = runCatching { URL(rawUrl.trim()) }.getOrNull() ?: return null
            if (!parsed.protocol.equals("https", ignoreCase = true)) return null
            if (parsed.host.isNullOrBlank()) return null
            return parsed
        }

        fun isSafeRedirectTarget(initialUrl: String, nextUrl: String): Boolean {
            val init = validateBalanceUrl(initialUrl) ?: return false
            val next = validateBalanceUrl(nextUrl) ?: return false
            val initHost = init.host.lowercase()
            val nextHost = next.host.lowercase()
            return nextHost == initHost || nextHost.endsWith(".$initHost")
        }
    }
}

/**
 * Extracts account **Balance** from JSON, XML, key-value, or HTML responses while
 * explicitly ignoring **Credit** fields/values.
 */
object BalanceResponseParser {

    private val CURRENCY_HINT_KEYS = listOf("currency", "currency_code", "unit", "symbol")
    private val BALANCE_JSON_KEYS = listOf(
        "balance",
        "account_balance",
        "accountbalance",
        "current_balance",
        "currentbalance",
        "main_balance",
        "mainbalance",
        "available_balance",
        "availablebalance",
        "user_balance",
        "userbalance",
        "wallet_balance",
        "walletbalance",
        "bal"
    )

    fun extractBalance(rawBody: String): String? {
        val body = rawBody.trim()
        if (body.isEmpty()) return null

        // 1. Try JSON object / array parsing first
        if (body.startsWith("{") || body.startsWith("[")) {
            extractFromJson(body)?.let { return it }
        }

        // 2. Strip HTML tags/scripts for label-based extraction, but keep structured HTML fallback
        val withoutScripts = body
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")

        // Check XML/HTML element explicitly tagged with id/class/tag containing "balance" (and NOT "credit")
        val taggedMatch = extractFromTaggedHtmlOrXml(withoutScripts)
        if (taggedMatch != null) return taggedMatch

        // 3. Flatten HTML/XML tags to clean text lines and look for "Balance : <amount>"
        val plainText = withoutScripts
            .replace(Regex("(?i)</?(tr|td|th|div|p|li|br|span|h\\d|strong|b|label)[^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&#2547;", "৳")
            .replace("&amp;", "&")
            .replace(Regex("[ \\t]+"), " ")

        extractFromPlainText(plainText)?.let { return it }

        return null
    }

    fun detectAuthOrServerError(rawBody: String): String? {
        val lower = rawBody.lowercase()
        return when {
            lower.contains("invalid username") ||
                lower.contains("invalid password") ||
                lower.contains("wrong password") ||
                lower.contains("authentication failed") ||
                lower.contains("login failed") -> "Invalid SIP username or password"
            lower.contains("unauthorized") || lower.contains("401") -> "Unauthorized credentials"
            else -> null
        }
    }

    private fun extractFromJson(jsonText: String): String? {
        return runCatching {
            val element = Json.parseToJsonElement(jsonText)
            findBalanceInJsonElement(element)
        }.getOrNull()
    }

    private fun findBalanceInJsonElement(element: JsonElement): String? {
        return when (element) {
            is JsonObject -> findBalanceInJsonObject(element)
            is JsonArray -> {
                for (child in element) {
                    findBalanceInJsonElement(child)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun findBalanceInJsonObject(obj: JsonObject): String? {
        val keys = obj.keys.toList()
        var currencyHint: String? = null
        for (key in keys) {
            if (key.lowercase() in CURRENCY_HINT_KEYS) {
                val prim = obj[key] as? JsonPrimitive
                val c = prim?.contentOrNull?.trim().orEmpty()
                if (c.isNotBlank() && !c.equals("null", ignoreCase = true)) {
                    currencyHint = c
                }
            }
        }

        // Priority pass: exact match on known balance keys (never keys containing "credit")
        for (preferred in BALANCE_JSON_KEYS) {
            val matchedKey = keys.firstOrNull {
                val lower = it.lowercase()
                !lower.contains("credit") && lower == preferred
            } ?: continue
            val prim = obj[matchedKey] as? JsonPrimitive
            val rawVal = prim?.contentOrNull
            formatBalanceValue(rawVal, currencyHint)?.let { return it }
        }

        // Secondary pass: any key containing "balance" that does NOT contain "credit"
        for (key in keys) {
            val lower = key.lowercase()
            if (lower.contains("balance") && !lower.contains("credit")) {
                val prim = obj[key] as? JsonPrimitive
                val rawVal = prim?.contentOrNull
                formatBalanceValue(rawVal, currencyHint)?.let { return it }
            }
        }

        // Recurse into nested objects / arrays
        for (key in keys) {
            val child = obj[key] ?: continue
            if (child is JsonObject || child is JsonArray) {
                findBalanceInJsonElement(child)?.let { return it }
            }
        }
        return null
    }

    private fun extractFromTaggedHtmlOrXml(markup: String): String? {
        // XML tag e.g. <balance>123.45</balance> or <accountBalance>123.45 BDT</accountBalance>
        val xmlRegex = Regex("(?is)<([a-zA-Z0-9_:-]*balance[a-zA-Z0-9_:-]*)>\\s*([^<]{1,40})\\s*</\\1>")
        for (match in xmlRegex.findAll(markup)) {
            val tagName = match.groupValues[1].lowercase()
            if (tagName.contains("credit")) continue
            extractMonetaryToken(match.groupValues[2])?.let { return it }
        }

        // HTML attribute id/class containing balance (excluding credit), e.g. <span id="balance">120.50 BDT</span>
        val attrRegex = Regex(
            "(?is)<[a-zA-Z0-9]+\\b[^>]*(?:id|class|name)\\s*=\\s*[\"'][^\"']*balance[^\"']*[\"'][^>]*>([^<]{1,48})</"
        )
        for (match in attrRegex.findAll(markup)) {
            val fullTag = match.value.lowercase()
            if (fullTag.contains("credit")) continue
            extractMonetaryToken(match.groupValues[1])?.let { return it }
        }

        // JS inline variable or JSON inside script/html fallback: e.g. "balance":"120.50" (ignoring credit)
        val inlineKv = Regex("(?i)[\"']?(?:account_?|current_?|main_?|available_?)?balance[\"']?\\s*[:=]\\s*[\"']?([৳$€£₹]?\\s*-?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*(?:BDT|TK|USD|EUR|GBP|INR|৳))?)[\"']?")
        for (match in inlineKv.findAll(markup)) {
            val prefixStart = (match.range.first - 16).coerceAtLeast(0)
            val contextBefore = markup.substring(prefixStart, match.range.first).lowercase()
            if (contextBefore.contains("credit")) continue
            formatBalanceValue(match.groupValues[1], null)?.let { return it }
        }

        return null
    }

    private fun extractFromPlainText(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val amountPattern = Regex("([৳$€£₹]?\\s*-?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*(?:BDT|TK|Tk|USD|EUR|GBP|INR|৳))?)")

        for (i in lines.indices) {
            val line = lines[i]
            val lower = line.lowercase()
            if (!lower.contains("balance")) continue
            if (lower.contains("credit")) {
                // Only reject if "credit" modifies "balance" on the same token/segment
                val cleanedSegments = line.split("|", ",", ";")
                for (seg in cleanedSegments) {
                    val segLower = seg.lowercase()
                    if (segLower.contains("balance") && !segLower.contains("credit")) {
                        val afterLabel = seg.substringAfter("balance", "").ifBlank {
                            seg.substringAfter("Balance", "")
                        }
                        extractMonetaryToken(afterLabel)?.let { return it }
                    }
                }
                continue
            }

            // Look on the same line after "balance"
            val idx = lower.indexOf("balance")
            val after = line.substring(idx + "balance".length)
            extractMonetaryToken(after)?.let { return it }

            // Look on the immediate next non-empty line (e.g., table <th>Balance</th>\n<td>125.00 BDT</td>)
            if (i + 1 < lines.size) {
                val nextLine = lines[i + 1]
                if (!nextLine.lowercase().contains("credit")) {
                    val m = amountPattern.find(nextLine)
                    if (m != null) {
                        formatBalanceValue(m.groupValues[1], null)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun extractMonetaryToken(segment: String): String? {
        val m = Regex("([৳$€£₹]?\\s*-?\\d[\\d,]*(?:\\.\\d+)?(?:\\s*(?:BDT|TK|Tk|USD|EUR|GBP|INR|৳))?)")
            .find(segment) ?: return null
        return formatBalanceValue(m.groupValues[1], null)
    }

    private fun formatBalanceValue(raw: String?, currencyHint: String?): String? {
        if (raw == null) return null
        val cleaned = raw.trim()
        if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return null
        if (!cleaned.any { it.isDigit() }) return null
        val compact = cleaned.replace(Regex("\\s+"), " ")
        return if (!currencyHint.isNullOrBlank() &&
            !compact.contains(currencyHint, ignoreCase = true) &&
            compact.all { it.isDigit() || it == '.' || it == '-' || it == ',' }
        ) {
            "$compact $currencyHint"
        } else {
            compact
        }
    }
}
