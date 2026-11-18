package com.example.sip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.data.repository.SipAccountRepository
import com.example.domain.ai.CallEventBus
import com.example.domain.ai.OmniCallEvent
import com.example.domain.model.CallSourceType
import com.example.domain.model.SipAccountModel
import com.example.domain.model.SipRegistrationStatus
import com.example.domain.model.SipTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

sealed interface SipSignalingEvent {
    data class OutgoingRinging(val callId: String) : SipSignalingEvent
    data class CallConnected(val callId: String, val remoteSdp: SdpMediaSpec?) : SipSignalingEvent
    data class IncomingInvite(
        val callId: String,
        val fromUri: String,
        val displayName: String,
        val account: SipAccountModel,
        val remoteSdp: SdpMediaSpec?,
        val rawRequest: SipRawMessage
    ) : SipSignalingEvent
    data class RemoteTerminated(val callId: String, val reason: String) : SipSignalingEvent
    data class CallFailed(val callId: String, val reason: String) : SipSignalingEvent
}

private data class ActiveSipDialog(
    val callId: String,
    val account: SipAccountModel,
    val targetUri: String,
    val fromHeader: String,
    var toHeader: String,
    var remoteTargetUri: String,
    var localRtpPort: Int,
    var cseq: Int,
    var remoteSdp: SdpMediaSpec? = null,
    var lastInviteRequest: SipRawMessage? = null
)

/**
 * Pure-Kotlin RFC 3261 SIP User Agent managing NAT-aware registration keep-alive,
 * outgoing/incoming INVITE dialogs, Digest auth, Hold re-INVITE, and INFO DTMF.
 */
class SipClientEngine(
    private val context: Context,
    private val sipAccountRepository: SipAccountRepository,
    private val eventBus: CallEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cseqCounter = AtomicInteger(10)

    private val _signalingEvents = MutableSharedFlow<SipSignalingEvent>(extraBufferCapacity = 32)
    val signalingEvents: SharedFlow<SipSignalingEvent> = _signalingEvents.asSharedFlow()

    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var remoteHostAddress: InetAddress? = null
    private var listenerJob: Job? = null
    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile private var activeAccount: SipAccountModel? = null
    @Volatile private var activePassword: String? = null
    @Volatile private var localIp: String = "127.0.0.1"
    @Volatile private var localPort: Int = 5060
    @Volatile private var mappedPublicIp: String? = null
    @Volatile private var mappedPublicPort: Int? = null
    @Volatile private var registerCallId: String = ""
    @Volatile private var registerFromTag: String = ""

    private val dialogs = ConcurrentHashMap<String, ActiveSipDialog>()

    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleNetworkReconnect()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (hasInternet && activeAccount != null && udpSocket == null && tcpSocket == null) {
                scheduleNetworkReconnect()
            }
        }
    }

    init {
        registerConnectivityWatcher()
    }

    private fun registerConnectivityWatcher() {
        if (networkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun scheduleNetworkReconnect() {
        val acc = activeAccount ?: return
        if (!acc.isEnabled) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(800L)
            val currentAcc = activeAccount ?: return@launch
            if (currentAcc.isEnabled) {
                startAccountRegistration(currentAcc)
            }
        }
    }

    fun startAccountRegistration(account: SipAccountModel) {
        scope.launch {
            stopTransport()
            val password = sipAccountRepository.getPassword(account.id)
            if (password.isNullOrBlank()) {
                sipAccountRepository.updateRegistrationStatus(
                    account.id,
                    SipRegistrationStatus.ERROR,
                    "Missing credentials in keystore"
                )
                return@launch
            }
            activeAccount = account
            activePassword = password
            localIp = SipAutoConfigEngine.resolveLocalIpv4Address()
            mappedPublicIp = null
            mappedPublicPort = null
            registerCallId = SipMessageCodec.newCallId(account.domain)
            registerFromTag = SipMessageCodec.newTag()

            sipAccountRepository.updateRegistrationStatus(account.id, SipRegistrationStatus.REGISTERING)
            eventBus.publish(
                OmniCallEvent.SipRegistrationChanged(
                    account.id,
                    account.sipUri,
                    SipRegistrationStatus.REGISTERING
                )
            )

            val connected = openTransportSocket(account)
            if (!connected) {
                sipAccountRepository.updateRegistrationStatus(
                    account.id,
                    SipRegistrationStatus.ERROR,
                    "Failed to open ${account.transport.label} socket to ${account.domain}:${account.port}"
                )
                eventBus.publish(
                    OmniCallEvent.SipRegistrationChanged(
                        account.id,
                        account.sipUri,
                        SipRegistrationStatus.ERROR
                    )
                )
                return@launch
            }

            startIncomingReader(account)
            sendRegister(account)

            // Maintain NAT UDP pinhole with 20s CRLF keepalive and refresh REGISTER every 45s
            keepAliveJob?.cancel()
            keepAliveJob = scope.launch {
                var ticks = 0
                while (isActive) {
                    delay(20_000L)
                    ticks++
                    sendNatKeepAlive(account)
                    if (ticks % 2 == 0) {
                        sendRegister(account)
                    }
                }
            }
        }
    }

    private fun openTransportSocket(account: SipAccountModel): Boolean {
        return runCatching {
            val remoteAddress = InetAddress.getByName(account.domain)
            remoteHostAddress = remoteAddress
            when (account.transport) {
                SipTransport.UDP -> {
                    // Unconnected DatagramSocket so load-balanced proxy replies or incoming INVITEs
                    // from cluster SBC IPs/ephemeral ports are never silently dropped by the kernel.
                    val sock = DatagramSocket().apply {
                        reuseAddress = true
                    }
                    localPort = sock.localPort
                    udpSocket = sock
                }
                SipTransport.TCP -> {
                    val sock = Socket().apply {
                        keepAlive = true
                        tcpNoDelay = true
                    }
                    sock.connect(InetSocketAddress(remoteAddress, account.port), 4000)
                    localPort = sock.localPort
                    tcpSocket = sock
                }
                SipTransport.TLS -> {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sock = (factory.createSocket() as SSLSocket).apply {
                        keepAlive = true
                        tcpNoDelay = true
                    }
                    sock.connect(InetSocketAddress(remoteAddress, account.port), 4500)
                    sock.startHandshake()
                    localPort = sock.localPort
                    tcpSocket = sock
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun startIncomingReader(account: SipAccountModel) {
        listenerJob?.cancel()
        listenerJob = scope.launch {
            if (account.transport == SipTransport.UDP) {
                val buf = ByteArray(16384)
                while (isActive) {
                    val sock = udpSocket ?: break
                    val packet = DatagramPacket(buf, buf.size)
                    val ok = runCatching {
                        sock.receive(packet)
                        true
                    }.getOrDefault(false)
                    if (!ok) {
                        if (isActive && activeAccount?.id == account.id && activeAccount?.isEnabled == true) {
                            scheduleNetworkReconnect()
                        }
                        break
                    }
                    if (packet.length <= 4) continue // Ignore CRLF keepalive pongs
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingRawPacket(raw, account)
                }
            } else {
                val input = BufferedInputStream(tcpSocket?.getInputStream() ?: return@launch)
                val buf = ByteArray(8192)
                val byteBuffer = java.io.ByteArrayOutputStream()
                while (isActive) {
                    val read = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (read <= 0) {
                        if (isActive && activeAccount?.id == account.id && activeAccount?.isEnabled == true) {
                            scheduleNetworkReconnect()
                        }
                        break
                    }
                    byteBuffer.write(buf, 0, read)
                    drainFramedSipMessages(byteBuffer, account)
                }
            }
        }
    }

    private suspend fun drainFramedSipMessages(
        buffer: java.io.ByteArrayOutputStream,
        account: SipAccountModel
    ) {
        while (true) {
            val rawBytes = buffer.toByteArray()
            val text = String(rawBytes, Charsets.UTF_8)
            // Trim leading keepalive CRLFs
            val trimmedStart = text.indexOfFirst { it != '\r' && it != '\n' }
            if (trimmedStart < 0) {
                buffer.reset()
                return
            }
            val cleanText = if (trimmedStart > 0) text.substring(trimmedStart) else text
            val sepIdx = cleanText.indexOf("\r\n\r\n").takeIf { it >= 0 }
                ?: cleanText.indexOf("\n\n").takeIf { it >= 0 }
                ?: break
            val sepLen = if (cleanText.startsWith("\r\n\r\n", sepIdx)) 4 else 2
            val headerPart = cleanText.substring(0, sepIdx)
            val contentLength = Regex("""(?im)^(?:Content-Length|l)\s*:\s*(\d+)""")
                .find(headerPart)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val bodyStartInClean = sepIdx + sepLen
            val bodyBytes = cleanText.substring(bodyStartInClean).toByteArray(Charsets.UTF_8)
            if (bodyBytes.size < contentLength) break

            val fullMsg = headerPart + "\r\n\r\n" +
                String(bodyBytes, 0, contentLength, Charsets.UTF_8)
            val remainingBytes = bodyBytes.copyOfRange(contentLength, bodyBytes.size)
            buffer.reset()
            if (remainingBytes.isNotEmpty()) {
                buffer.write(remainingBytes)
            }
            handleIncomingRawPacket(fullMsg, account)
        }
    }

    private suspend fun handleIncomingRawPacket(raw: String, account: SipAccountModel) {
        val msg = SipMessageCodec.parse(raw) ?: return
        updateMappedAddressFromVia(msg)
        val callId = msg.firstHeader("Call-ID") ?: return
        val cseqHeader = msg.firstHeader("CSeq") ?: ""
        val cseqMethod = cseqHeader.substringAfter(' ', "").uppercase()

        if (!msg.isRequest) {
            when (cseqMethod) {
                "REGISTER" -> handleRegisterResponse(msg, account)
                "INVITE" -> handleInviteResponse(msg, callId, account)
            }
        } else {
            when (msg.method) {
                "INVITE" -> handleIncomingInvite(msg, callId, account)
                "ACK" -> Unit // Dialog already acknowledged; media running
                "BYE", "CANCEL" -> handleIncomingTerminate(msg, callId, account)
                "OPTIONS" -> {
                    val ok = SipMessageCodec.buildResponse(
                        200, "OK", msg, effectiveContactIp(), effectiveContactPort(),
                        account.username, account.transport, SipMessageCodec.newTag()
                    )
                    sendRaw(ok, account)
                }
            }
        }
    }

    private fun updateMappedAddressFromVia(msg: SipRawMessage) {
        val via = msg.firstHeader("Via") ?: return
        val receivedIp = Regex("""received=([^;\s]+)""", RegexOption.IGNORE_CASE)
            .find(via)?.groupValues?.getOrNull(1)
        val rport = Regex("""rport=(\d+)""", RegexOption.IGNORE_CASE)
            .find(via)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (!receivedIp.isNullOrBlank()) mappedPublicIp = receivedIp
        if (rport != null && rport in 1..65535) mappedPublicPort = rport
    }

    private fun effectiveContactIp(): String = mappedPublicIp ?: localIp
    private fun effectiveContactPort(): Int = mappedPublicPort ?: localPort

    private suspend fun handleRegisterResponse(msg: SipRawMessage, account: SipAccountModel) {
        when (msg.statusCode) {
            in 200..299 -> {
                sipAccountRepository.updateRegistrationStatus(account.id, SipRegistrationStatus.REGISTERED)
                eventBus.publish(
                    OmniCallEvent.SipRegistrationChanged(
                        account.id,
                        account.sipUri,
                        SipRegistrationStatus.REGISTERED
                    )
                )
            }
            401, 407 -> {
                val challenge = SipMessageCodec.parseChallenge(msg) ?: return
                val password = activePassword ?: return
                val auth = SipMessageCodec.buildDigestAuthorizationHeader(
                    challenge, account.username, password, "REGISTER", "sip:${account.domain}"
                )
                val req = SipMessageCodec.buildRegisterRequest(
                    domain = account.domain,
                    username = account.username,
                    localIp = localIp,
                    localPort = localPort,
                    transport = account.transport,
                    callId = msg.firstHeader("Call-ID") ?: registerCallId.ifBlank { SipMessageCodec.newCallId(account.domain) },
                    cseq = cseqCounter.incrementAndGet(),
                    fromTag = registerFromTag.ifBlank { SipMessageCodec.newTag() },
                    branch = SipMessageCodec.newBranch(),
                    expiresSec = 120,
                    authHeader = auth,
                    contactIp = effectiveContactIp(),
                    contactPort = effectiveContactPort()
                )
                sendRaw(req, account)
            }
            else -> {
                sipAccountRepository.updateRegistrationStatus(
                    account.id,
                    SipRegistrationStatus.ERROR,
                    "SIP ${msg.statusCode} ${msg.reasonPhrase}"
                )
            }
        }
    }

    private suspend fun handleInviteResponse(msg: SipRawMessage, callId: String, account: SipAccountModel) {
        val dialog = dialogs[callId] ?: return
        when (msg.statusCode) {
            180, 183 -> {
                _signalingEvents.emit(SipSignalingEvent.OutgoingRinging(callId))
            }
            401, 407 -> {
                val challenge = SipMessageCodec.parseChallenge(msg) ?: return
                val password = activePassword ?: return
                val auth = SipMessageCodec.buildDigestAuthorizationHeader(
                    challenge, account.username, password, "INVITE", dialog.targetUri
                )
                val sdp = SipMessageCodec.buildSdpOffer(effectiveContactIp(), dialog.localRtpPort)
                val nextCseq = cseqCounter.incrementAndGet()
                dialog.cseq = nextCseq
                val invite = SipMessageCodec.buildInviteRequest(
                    targetUri = dialog.targetUri,
                    domain = account.domain,
                    username = account.username,
                    displayName = account.displayName,
                    localIp = effectiveContactIp(),
                    localSipPort = effectiveContactPort(),
                    transport = account.transport,
                    callId = callId,
                    cseq = nextCseq,
                    fromTag = dialog.fromHeader.substringAfter("tag=", ""),
                    branch = SipMessageCodec.newBranch(),
                    sdpBody = sdp,
                    authHeader = auth
                )
                sendRaw(invite, account)
            }
            in 200..299 -> {
                val toHeader = msg.firstHeader("To") ?: dialog.toHeader
                dialog.toHeader = toHeader
                val contactUri = msg.firstHeader("Contact")
                    ?.substringAfter('<')?.substringBefore('>')
                    ?: dialog.targetUri
                dialog.remoteTargetUri = contactUri
                val remoteSdp = SipMessageCodec.parseSdp(msg.body)
                dialog.remoteSdp = remoteSdp

                val ack = SipMessageCodec.buildAckRequest(
                    targetUri = contactUri,
                    fromHeader = dialog.fromHeader,
                    toHeader = toHeader,
                    localIp = effectiveContactIp(),
                    localSipPort = effectiveContactPort(),
                    transport = account.transport,
                    callId = callId,
                    cseq = dialog.cseq
                )
                sendRaw(ack, account)
                _signalingEvents.emit(SipSignalingEvent.CallConnected(callId, remoteSdp))
            }
            in 400..699 -> {
                dialogs.remove(callId)
                _signalingEvents.emit(
                    SipSignalingEvent.CallFailed(callId, "SIP ${msg.statusCode} ${msg.reasonPhrase}")
                )
            }
        }
    }

    private suspend fun handleIncomingInvite(msg: SipRawMessage, callId: String, account: SipAccountModel) {
        val existingDialog = dialogs[callId]
        if (existingDialog != null) {
            // Re-INVITE or INVITE retransmission: acknowledge with 180 Ringing cleanly
            val toTag = existingDialog.fromHeader.substringAfter("tag=", SipMessageCodec.newTag())
            val ringing = SipMessageCodec.buildResponse(
                180, "Ringing", msg, effectiveContactIp(), effectiveContactPort(),
                account.username, account.transport, toTag
            )
            sendRaw(ringing, account)
            return
        }

        val toTag = SipMessageCodec.newTag()
        // Send immediate 100 Trying so SIP proxy won't retransmit/drop INVITE
        val trying = SipMessageCodec.buildResponse(
            100, "Trying", msg, effectiveContactIp(), effectiveContactPort(),
            account.username, account.transport, toTag
        )
        sendRaw(trying, account)

        val fromHeader = msg.firstHeader("From").orEmpty()
        val displayName = fromHeader.substringBefore('<').replace("\"", "").trim()
        val fromUri = fromHeader.substringAfter('<', fromHeader).substringBefore('>').removePrefix("sip:")
        val remoteSdp = SipMessageCodec.parseSdp(msg.body)

        val ringing = SipMessageCodec.buildResponse(
            180, "Ringing", msg, effectiveContactIp(), effectiveContactPort(),
            account.username, account.transport, toTag
        )
        sendRaw(ringing, account)

        dialogs[callId] = ActiveSipDialog(
            callId = callId,
            account = account,
            targetUri = "sip:$fromUri",
            fromHeader = msg.firstHeader("To").orEmpty() + ";tag=$toTag",
            toHeader = fromHeader,
            remoteTargetUri = msg.firstHeader("Contact")
                ?.substringAfter('<')?.substringBefore('>') ?: "sip:$fromUri",
            localRtpPort = 40000,
            cseq = 1,
            remoteSdp = remoteSdp,
            lastInviteRequest = msg
        )
        eventBus.publish(OmniCallEvent.IncomingCallDetected(callId, fromUri, CallSourceType.SIP))
        _signalingEvents.emit(
            SipSignalingEvent.IncomingInvite(
                callId = callId,
                fromUri = fromUri,
                displayName = displayName.ifBlank { fromUri },
                account = account,
                remoteSdp = remoteSdp,
                rawRequest = msg
            )
        )
    }

    private suspend fun handleIncomingTerminate(msg: SipRawMessage, callId: String, account: SipAccountModel) {
        val ok = SipMessageCodec.buildResponse(
            200, "OK", msg, effectiveContactIp(), effectiveContactPort(),
            account.username, account.transport, SipMessageCodec.newTag()
        )
        sendRaw(ok, account)
        dialogs.remove(callId)
        _signalingEvents.emit(SipSignalingEvent.RemoteTerminated(callId, "Remote party ended call"))
    }

    suspend fun placeOutgoingInvite(
        callId: String,
        destination: String,
        account: SipAccountModel,
        localRtpPort: Int,
        preferWideband: Boolean
    ): Boolean {
        val password = sipAccountRepository.getPassword(account.id) ?: return false
        activeAccount = account
        activePassword = password

        if (udpSocket == null && tcpSocket == null) {
            localIp = SipAutoConfigEngine.resolveLocalIpv4Address()
            if (!openTransportSocket(account)) return false
            startIncomingReader(account)
        }

        val cleanTarget = destination.trim().removePrefix("sip:")
        val targetUri = if (cleanTarget.contains("@")) "sip:$cleanTarget" else "sip:$cleanTarget@${account.domain}"
        val fromTag = SipMessageCodec.newTag()
        val fromHeader = "\"${account.displayName}\" <sip:${account.username}@${account.domain}>;tag=$fromTag"
        val cseq = cseqCounter.incrementAndGet()

        dialogs[callId] = ActiveSipDialog(
            callId = callId,
            account = account,
            targetUri = targetUri,
            fromHeader = fromHeader,
            toHeader = "<$targetUri>",
            remoteTargetUri = targetUri,
            localRtpPort = localRtpPort,
            cseq = cseq
        )

        val sdp = SipMessageCodec.buildSdpOffer(effectiveContactIp(), localRtpPort, preferWideband)
        val invite = SipMessageCodec.buildInviteRequest(
            targetUri = targetUri,
            domain = account.domain,
            username = account.username,
            displayName = account.displayName,
            localIp = effectiveContactIp(),
            localSipPort = effectiveContactPort(),
            transport = account.transport,
            callId = callId,
            cseq = cseq,
            fromTag = fromTag,
            branch = SipMessageCodec.newBranch(),
            sdpBody = sdp
        )
        return sendRaw(invite, account)
    }

    suspend fun answerIncomingInvite(callId: String, localRtpPort: Int, preferWideband: Boolean): SdpMediaSpec? {
        val dialog = dialogs[callId] ?: return null
        val req = dialog.lastInviteRequest ?: return null
        dialog.localRtpPort = localRtpPort
        val sdp = SipMessageCodec.buildSdpOffer(effectiveContactIp(), localRtpPort, preferWideband)
        val ok = SipMessageCodec.buildResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            request = req,
            localIp = effectiveContactIp(),
            localPort = effectiveContactPort(),
            username = dialog.account.username,
            transport = dialog.account.transport,
            toTag = dialog.fromHeader.substringAfter("tag=", SipMessageCodec.newTag()),
            body = sdp,
            contentType = "application/sdp"
        )
        sendRaw(ok, dialog.account)
        return dialog.remoteSdp
    }

    suspend fun setHold(callId: String, hold: Boolean) {
        val dialog = dialogs[callId] ?: return
        val direction = if (hold) "sendonly" else "sendrecv"
        val sdp = SipMessageCodec.buildSdpOffer(effectiveContactIp(), dialog.localRtpPort, direction = direction)
        val nextCseq = cseqCounter.incrementAndGet()
        dialog.cseq = nextCseq
        val reInvite = SipMessageCodec.buildSimpleInDialogRequest(
            method = "INVITE",
            targetUri = dialog.remoteTargetUri,
            fromHeader = dialog.fromHeader,
            toHeader = dialog.toHeader,
            localIp = effectiveContactIp(),
            localSipPort = effectiveContactPort(),
            transport = dialog.account.transport,
            callId = callId,
            cseq = nextCseq,
            contentType = "application/sdp",
            body = sdp
        )
        sendRaw(reInvite, dialog.account)
    }

    suspend fun sendDtmfInfo(callId: String, digit: Char) {
        val dialog = dialogs[callId] ?: return
        val body = "Signal=$digit\r\nDuration=160\r\n"
        val info = SipMessageCodec.buildSimpleInDialogRequest(
            method = "INFO",
            targetUri = dialog.remoteTargetUri,
            fromHeader = dialog.fromHeader,
            toHeader = dialog.toHeader,
            localIp = effectiveContactIp(),
            localSipPort = effectiveContactPort(),
            transport = dialog.account.transport,
            callId = callId,
            cseq = cseqCounter.incrementAndGet(),
            contentType = "application/dtmf-relay",
            body = body
        )
        sendRaw(info, dialog.account)
        eventBus.publish(OmniCallEvent.DtmfDigitReceived(callId, digit))
    }

    suspend fun terminateCall(callId: String) {
        val dialog = dialogs.remove(callId) ?: return
        val bye = SipMessageCodec.buildSimpleInDialogRequest(
            method = "BYE",
            targetUri = dialog.remoteTargetUri,
            fromHeader = dialog.fromHeader,
            toHeader = dialog.toHeader,
            localIp = effectiveContactIp(),
            localSipPort = effectiveContactPort(),
            transport = dialog.account.transport,
            callId = callId,
            cseq = cseqCounter.incrementAndGet()
        )
        sendRaw(bye, dialog.account)
    }

    private fun sendRegister(account: SipAccountModel) {
        val req = SipMessageCodec.buildRegisterRequest(
            domain = account.domain,
            username = account.username,
            localIp = localIp,
            localPort = localPort,
            transport = account.transport,
            callId = registerCallId.ifBlank { SipMessageCodec.newCallId(account.domain) },
            cseq = cseqCounter.incrementAndGet(),
            fromTag = registerFromTag.ifBlank { SipMessageCodec.newTag() },
            branch = SipMessageCodec.newBranch(),
            expiresSec = 120,
            contactIp = effectiveContactIp(),
            contactPort = effectiveContactPort()
        )
        sendRaw(req, account)
    }

    private fun sendNatKeepAlive(account: SipAccountModel) {
        sendRaw("\r\n\r\n", account)
    }

    private fun sendRaw(message: String, account: SipAccountModel): Boolean {
        val bytes = message.toByteArray(Charsets.UTF_8)
        return runCatching {
            when (account.transport) {
                SipTransport.UDP -> {
                    val sock = udpSocket ?: return false
                    val addr = remoteHostAddress ?: InetAddress.getByName(account.domain).also {
                        remoteHostAddress = it
                    }
                    sock.send(DatagramPacket(bytes, bytes.size, addr, account.port))
                }
                SipTransport.TCP, SipTransport.TLS -> {
                    val out = tcpSocket?.getOutputStream() ?: return false
                    out.write(bytes)
                    out.flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun stopTransport() {
        keepAliveJob?.cancel()
        listenerJob?.cancel()
        runCatching { udpSocket?.close() }
        runCatching { tcpSocket?.close() }
        udpSocket = null
        tcpSocket = null
    }
}
