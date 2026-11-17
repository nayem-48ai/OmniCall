package com.example.sip

import com.example.domain.model.SipTransport
import java.security.MessageDigest
import java.util.UUID

data class SipHeader(val name: String, val value: String)

data class SipRawMessage(
    val isRequest: Boolean,
    val method: String = "",
    val requestUri: String = "",
    val statusCode: Int = 0,
    val reasonPhrase: String = "",
    val headers: Map<String, List<String>>,
    val body: String = ""
) {
    fun firstHeader(name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    fun headerValues(name: String): List<String> {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: emptyList()
    }
}

data class DigestChallenge(
    val realm: String,
    val nonce: String,
    val qop: String?,
    val opaque: String?,
    val algorithm: String,
    val isProxy: Boolean
)

data class SdpMediaSpec(
    val remoteIp: String,
    val remotePort: Int,
    val payloadType: Int,
    val codecName: String,
    val sampleRateHz: Int,
    val dtmfPayloadType: Int = 101,
    val isSendOnlyOrInactive: Boolean = false
)

object SipMessageCodec {

    private const val CRLF = "\r\n"
    private const val USER_AGENT = "OmniCall/1.0 (Android RFC3261)"

    fun newBranch(): String = "z9hG4bK-${UUID.randomUUID().toString().replace("-", "").take(16)}"
    fun newTag(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    fun newCallId(domain: String): String = "${UUID.randomUUID().toString().take(16)}@$domain"

    fun buildRegisterRequest(
        domain: String,
        username: String,
        localIp: String,
        localPort: Int,
        transport: SipTransport,
        callId: String,
        cseq: Int,
        fromTag: String,
        branch: String,
        expiresSec: Int = 300,
        authHeader: Pair<String, String>? = null
    ): String {
        val transportUpper = transport.label
        val requestUri = "sip:$domain"
        val aor = "<sip:$username@$domain>"
        val contact = "<sip:$username@$localIp:$localPort;transport=${transportUpper.lowercase()}>"

        return buildString {
            append("REGISTER $requestUri SIP/2.0$CRLF")
            append("Via: SIP/2.0/$transportUpper $localIp:$localPort;rport;branch=$branch$CRLF")
            append("Max-Forwards: 70$CRLF")
            append("From: $aor;tag=$fromTag$CRLF")
            append("To: $aor$CRLF")
            append("Call-ID: $callId$CRLF")
            append("CSeq: $cseq REGISTER$CRLF")
            append("Contact: $contact$CRLF")
            append("Expires: $expiresSec$CRLF")
            append("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, INFO$CRLF")
            append("User-Agent: $USER_AGENT$CRLF")
            if (authHeader != null) {
                append("${authHeader.first}: ${authHeader.second}$CRLF")
            }
            append("Content-Length: 0$CRLF")
            append(CRLF)
        }
    }

    fun buildOptionsRequest(
        domain: String,
        port: Int,
        username: String,
        localIp: String,
        localPort: Int,
        transport: SipTransport,
        callId: String = newCallId(domain),
        cseq: Int = 1
    ): String {
        val transportUpper = transport.label
        val branch = newBranch()
        val fromTag = newTag()
        return buildString {
            append("OPTIONS sip:$domain:$port SIP/2.0$CRLF")
            append("Via: SIP/2.0/$transportUpper $localIp:$localPort;rport;branch=$branch$CRLF")
            append("Max-Forwards: 70$CRLF")
            append("From: <sip:$username@$domain>;tag=$fromTag$CRLF")
            append("To: <sip:$domain>$CRLF")
            append("Call-ID: $callId$CRLF")
            append("CSeq: $cseq OPTIONS$CRLF")
            append("Accept: application/sdp$CRLF")
            append("User-Agent: $USER_AGENT$CRLF")
            append("Content-Length: 0$CRLF")
            append(CRLF)
        }
    }

    fun buildInviteRequest(
        targetUri: String,
        domain: String,
        username: String,
        displayName: String,
        localIp: String,
        localSipPort: Int,
        transport: SipTransport,
        callId: String,
        cseq: Int,
        fromTag: String,
        branch: String,
        sdpBody: String,
        authHeader: Pair<String, String>? = null
    ): String {
        val transportUpper = transport.label
        val bodyBytes = sdpBody.toByteArray(Charsets.UTF_8)
        val safeName = displayName.replace("\"", "").ifBlank { username }
        return buildString {
            append("INVITE $targetUri SIP/2.0$CRLF")
            append("Via: SIP/2.0/$transportUpper $localIp:$localSipPort;rport;branch=$branch$CRLF")
            append("Max-Forwards: 70$CRLF")
            append("From: \"$safeName\" <sip:$username@$domain>;tag=$fromTag$CRLF")
            append("To: <$targetUri>$CRLF")
            append("Call-ID: $callId$CRLF")
            append("CSeq: $cseq INVITE$CRLF")
            append("Contact: <sip:$username@$localIp:$localSipPort;transport=${transportUpper.lowercase()}>$CRLF")
            append("Supported: replaces, timer$CRLF")
            append("Allow: INVITE, ACK, CANCEL, BYE, OPTIONS, INFO$CRLF")
            append("User-Agent: $USER_AGENT$CRLF")
            if (authHeader != null) {
                append("${authHeader.first}: ${authHeader.second}$CRLF")
            }
            append("Content-Type: application/sdp$CRLF")
            append("Content-Length: ${bodyBytes.size}$CRLF")
            append(CRLF)
            append(sdpBody)
        }
    }

    fun buildAckRequest(
        targetUri: String,
        fromHeader: String,
        toHeader: String,
        localIp: String,
        localSipPort: Int,
        transport: SipTransport,
        callId: String,
        cseq: Int
    ): String {
        val branch = newBranch()
        return buildString {
            append("ACK $targetUri SIP/2.0$CRLF")
            append("Via: SIP/2.0/${transport.label} $localIp:$localSipPort;rport;branch=$branch$CRLF")
            append("Max-Forwards: 70$CRLF")
            append("From: $fromHeader$CRLF")
            append("To: $toHeader$CRLF")
            append("Call-ID: $callId$CRLF")
            append("CSeq: $cseq ACK$CRLF")
            append("User-Agent: $USER_AGENT$CRLF")
            append("Content-Length: 0$CRLF")
            append(CRLF)
        }
    }

    fun buildSimpleInDialogRequest(
        method: String,
        targetUri: String,
        fromHeader: String,
        toHeader: String,
        localIp: String,
        localSipPort: Int,
        transport: SipTransport,
        callId: String,
        cseq: Int,
        contentType: String? = null,
        body: String = ""
    ): String {
        val branch = newBranch()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        return buildString {
            append("$method $targetUri SIP/2.0$CRLF")
            append("Via: SIP/2.0/${transport.label} $localIp:$localSipPort;rport;branch=$branch$CRLF")
            append("Max-Forwards: 70$CRLF")
            append("From: $fromHeader$CRLF")
            append("To: $toHeader$CRLF")
            append("Call-ID: $callId$CRLF")
            append("CSeq: $cseq $method$CRLF")
            append("User-Agent: $USER_AGENT$CRLF")
            if (contentType != null && bodyBytes.isNotEmpty()) {
                append("Content-Type: $contentType$CRLF")
            }
            append("Content-Length: ${bodyBytes.size}$CRLF")
            append(CRLF)
            if (bodyBytes.isNotEmpty()) append(body)
        }
    }

    fun buildResponse(
        statusCode: Int,
        reasonPhrase: String,
        request: SipRawMessage,
        localIp: String,
        localPort: Int,
        username: String,
        transport: SipTransport,
        toTag: String,
        body: String = "",
        contentType: String? = null
    ): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val rawTo = request.firstHeader("To").orEmpty()
        val toWithTag = if (rawTo.contains("tag=", ignoreCase = true)) rawTo else "$rawTo;tag=$toTag"
        return buildString {
            append("SIP/2.0 $statusCode $reasonPhrase$CRLF")
            request.headerValues("Via").forEach { via -> append("Via: $via$CRLF") }
            request.firstHeader("From")?.let { append("From: $it$CRLF") }
            append("To: $toWithTag$CRLF")
            request.firstHeader("Call-ID")?.let { append("Call-ID: $it$CRLF") }
            request.firstHeader("CSeq")?.let { append("CSeq: $it$CRLF") }
            append("Contact: <sip:$username@$localIp:$localPort;transport=${transport.label.lowercase()}>$CRLF")
            append("User-Agent: $USER_AGENT$CRLF")
            if (contentType != null && bodyBytes.isNotEmpty()) {
                append("Content-Type: $contentType$CRLF")
            }
            append("Content-Length: ${bodyBytes.size}$CRLF")
            append(CRLF)
            if (bodyBytes.isNotEmpty()) append(body)
        }
    }

    fun parse(raw: String): SipRawMessage? {
        val separatorIdx = raw.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: raw.indexOf("\n\n")
        val headerBlock = if (separatorIdx >= 0) raw.substring(0, separatorIdx) else raw
        val body = if (separatorIdx >= 0) {
            val offset = if (raw.startsWith("\r\n\r\n", separatorIdx)) 4 else 2
            raw.substring(separatorIdx + offset)
        } else {
            ""
        }
        val lines = headerBlock.split(Regex("\r?\n")).filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val startLine = lines.first().trim()

        val headers = linkedMapOf<String, MutableList<String>>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val key = expandCompactHeader(lines[i].substring(0, idx).trim())
                val value = lines[i].substring(idx + 1).trim()
                headers.getOrPut(key) { mutableListOf() }.add(value)
            }
        }

        return if (startLine.startsWith("SIP/2.0", ignoreCase = true)) {
            val parts = startLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val reason = parts.getOrNull(2) ?: ""
            SipRawMessage(
                isRequest = false,
                statusCode = code,
                reasonPhrase = reason,
                headers = headers,
                body = body
            )
        } else {
            val parts = startLine.split(' ', limit = 3)
            if (parts.size < 2) return null
            SipRawMessage(
                isRequest = true,
                method = parts[0].uppercase(),
                requestUri = parts[1],
                headers = headers,
                body = body
            )
        }
    }

    private fun expandCompactHeader(name: String): String = when (name.lowercase()) {
        "v" -> "Via"
        "f" -> "From"
        "t" -> "To"
        "i" -> "Call-ID"
        "m" -> "Contact"
        "l" -> "Content-Length"
        "c" -> "Content-Type"
        else -> name
    }

    fun parseChallenge(response: SipRawMessage): DigestChallenge? {
        val proxyAuth = response.firstHeader("Proxy-Authenticate")
        val wwwAuth = response.firstHeader("WWW-Authenticate")
        val raw = proxyAuth ?: wwwAuth ?: return null
        val isProxy = proxyAuth != null

        fun extract(key: String): String? {
            val quoted = Regex("""$key\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
            if (quoted != null) return quoted
            return Regex("""$key\s*=\s*([^,\s]+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
        }

        val realm = extract("realm") ?: return null
        val nonce = extract("nonce") ?: return null
        return DigestChallenge(
            realm = realm,
            nonce = nonce,
            qop = extract("qop"),
            opaque = extract("opaque"),
            algorithm = extract("algorithm") ?: "MD5",
            isProxy = isProxy
        )
    }

    fun buildDigestAuthorizationHeader(
        challenge: DigestChallenge,
        username: String,
        password: String,
        method: String,
        uri: String
    ): Pair<String, String> {
        val algoName = if (challenge.algorithm.contains("256", ignoreCase = true)) "SHA-256" else "MD5"
        val digest = MessageDigest.getInstance(algoName)
        fun hexHash(input: String): String = digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val ha1 = hexHash("$username:${challenge.realm}:$password")
        val ha2 = hexHash("$method:$uri")
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(12)
        val nc = "00000001"
        val qopToken = challenge.qop?.split(",")?.firstOrNull { it.trim().equals("auth", true) }?.trim()

        val responseHash = if (qopToken != null) {
            hexHash("$ha1:${challenge.nonce}:$nc:$cnonce:$qopToken:$ha2")
        } else {
            hexHash("$ha1:${challenge.nonce}:$ha2")
        }

        val headerName = if (challenge.isProxy) "Proxy-Authorization" else "Authorization"
        val value = buildString {
            append("Digest username=\"$username\", ")
            append("realm=\"${challenge.realm}\", ")
            append("nonce=\"${challenge.nonce}\", ")
            append("uri=\"$uri\", ")
            append("algorithm=${challenge.algorithm}, ")
            if (qopToken != null) {
                append("qop=$qopToken, nc=$nc, cnonce=\"$cnonce\", ")
            }
            if (!challenge.opaque.isNullOrBlank()) {
                append("opaque=\"${challenge.opaque}\", ")
            }
            append("response=\"$responseHash\"")
        }
        return headerName to value
    }

    fun buildSdpOffer(
        localIp: String,
        localRtpPort: Int,
        preferWideband: Boolean = true,
        direction: String = "sendrecv"
    ): String {
        val sessionId = System.currentTimeMillis() / 1000L
        val codecs = if (preferWideband) "9 0 8 101" else "0 8 9 101"
        return buildString {
            append("v=0$CRLF")
            append("o=OmniCall $sessionId $sessionId IN IP4 $localIp$CRLF")
            append("s=OmniCall VoIP Session$CRLF")
            append("c=IN IP4 $localIp$CRLF")
            append("t=0 0$CRLF")
            append("m=audio $localRtpPort RTP/AVP $codecs$CRLF")
            append("a=rtpmap:9 G722/8000$CRLF")
            append("a=rtpmap:0 PCMU/8000$CRLF")
            append("a=rtpmap:8 PCMA/8000$CRLF")
            append("a=rtpmap:101 telephone-event/8000$CRLF")
            append("a=fmtp:101 0-16$CRLF")
            append("a=ptime:20$CRLF")
            append("a=$direction$CRLF")
        }
    }

    fun parseSdp(sdp: String): SdpMediaSpec? {
        val lines = sdp.split(Regex("\r?\n")).map { it.trim() }
        var connectionIp: String? = null
        var mediaPort: Int? = null
        var payloadTypes: List<Int> = emptyList()
        val rtpmap = mutableMapOf<Int, Pair<String, Int>>()
        var inactiveOrHold = false

        for (line in lines) {
            when {
                line.startsWith("c=IN IP4 ") -> {
                    connectionIp = line.removePrefix("c=IN IP4 ").substringBefore("/").trim()
                }
                line.startsWith("m=audio ") -> {
                    val tokens = line.removePrefix("m=audio ").split(Regex("\\s+"))
                    mediaPort = tokens.getOrNull(0)?.toIntOrNull()
                    payloadTypes = tokens.drop(2).mapNotNull { it.toIntOrNull() }
                }
                line.startsWith("a=rtpmap:") -> {
                    val rest = line.removePrefix("a=rtpmap:")
                    val pt = rest.substringBefore(' ').toIntOrNull()
                    val codecSpec = rest.substringAfter(' ', "")
                    val codecName = codecSpec.substringBefore('/').uppercase()
                    val clock = codecSpec.substringAfter('/', "8000").substringBefore('/').toIntOrNull() ?: 8000
                    if (pt != null && codecName.isNotBlank()) {
                        rtpmap[pt] = codecName to clock
                    }
                }
                line == "a=sendonly" || line == "a=inactive" -> {
                    inactiveOrHold = true
                }
            }
        }

        val ip = connectionIp ?: return null
        val port = mediaPort ?: return null
        val dtmfPt = rtpmap.entries.firstOrNull { it.value.first == "TELEPHONE-EVENT" }?.key ?: 101

        // Pick highest priority negotiated audio codec (prefer G.722 wideband if offered)
        val chosenPt = payloadTypes.firstOrNull { it == 9 || it == 0 || it == 8 }
            ?: payloadTypes.firstOrNull { it != dtmfPt }
            ?: 0

        val (codecName, sampleRate) = when (chosenPt) {
            9 -> "G722" to 16000
            0 -> "PCMU" to 8000
            8 -> "PCMA" to 8000
            else -> rtpmap[chosenPt] ?: ("PCMU" to 8000)
        }

        return SdpMediaSpec(
            remoteIp = ip,
            remotePort = port,
            payloadType = chosenPt,
            codecName = codecName,
            sampleRateHz = sampleRate,
            dtmfPayloadType = dtmfPt,
            isSendOnlyOrInactive = inactiveOrHold
        )
    }
}
