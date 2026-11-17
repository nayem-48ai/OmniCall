package com.example.sip

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
    val localRtpPort: Int,
    var cseq: Int,
    var remoteSdp: SdpMediaSpec? = null,
    var lastInviteRequest: SipRawMessage? = null
)

/**
 * Pure-Kotlin RFC 3261 SIP User Agent managing registration keep-alive,
 * outgoing/incoming INVITE dialogs, Digest auth, Hold re-INVITE, and INFO DTMF.
 */
class SipClientEngine(
    private val sipAccountRepository: SipAccountRepository,
    private val eventBus: CallEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cseqCounter = AtomicInteger(10)

    private val _signalingEvents = MutableSharedFlow<SipSignalingEvent>(extraBufferCapacity = 32)
    val signalingEvents: SharedFlow<SipSignalingEvent> = _signalingEvents.asSharedFlow()

    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var listenerJob: Job? = null
    private var keepAliveJob: Job? = null

    private var activeAccount: SipAccountModel? = null
    private var activePassword: String? = null
    private var localIp: String = "127.0.0.1"
    private var localPort: Int = 5060

    private val dialogs = mutableMapOf<String, ActiveSipDialog>()

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
            sendRegister(account, password)

            keepAliveJob?.cancel()
            keepAliveJob = scope.launch {
                while (isActive) {
                    delay(120_000L) // Refresh REGISTER every 2 minutes
                    sendRegister(account, password)
                }
            }
        }
    }

    private fun openTransportSocket(account: SipAccountModel): Boolean {
        return runCatching {
            val remoteAddress = InetAddress.getByName(account.domain)
            when (account.transport) {
                SipTransport.UDP -> {
                    val sock = DatagramSocket()
                    sock.connect(remoteAddress, account.port)
                    localPort = sock.localPort
                    udpSocket = sock
                }
                SipTransport.TCP -> {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(remoteAddress, account.port), 3500)
                    localPort = sock.localPort
                    tcpSocket = sock
                }
                SipTransport.TLS -> {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val sock = factory.createSocket() as SSLSocket
                    sock.connect(InetSocketAddress(remoteAddress, account.port), 4000)
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
                    runCatching { sock.receive(packet) }.onFailure { break }
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingRawPacket(raw, account)
                }
            } else {
                val input = BufferedInputStream(tcpSocket?.getInputStream() ?: return@launch)
                val buf = ByteArray(16384)
                val accumulator = StringBuilder()
                while (isActive) {
                    val read = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (read <= 0) break
                    accumulator.append(String(buf, 0, read, Charsets.UTF_8))
                    val content = accumulator.toString()
                    if (content.contains("\r\n\r\n") || content.contains("\n\n")) {
                        accumulator.clear()
                        handleIncomingRawPacket(content, account)
                    }
                }
            }
        }
    }

    private suspend fun handleIncomingRawPacket(raw: String, account: SipAccountModel) {
        val msg = SipMessageCodec.parse(raw) ?: return
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
                "BYE", "CANCEL" -> handleIncomingTerminate(msg, callId, account)
                "OPTIONS" -> {
                    val ok = SipMessageCodec.buildResponse(
                        200, "OK", msg, localIp, localPort,
                        account.username, account.transport, SipMessageCodec.newTag()
                    )
                    sendRaw(ok, account)
                }
            }
        }
    }

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
                    callId = msg.firstHeader("Call-ID") ?: SipMessageCodec.newCallId(account.domain),
                    cseq = cseqCounter.incrementAndGet(),
                    fromTag = SipMessageCodec.newTag(),
                    branch = SipMessageCodec.newBranch(),
                    authHeader = auth
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
                val sdp = SipMessageCodec.buildSdpOffer(localIp, dialog.localRtpPort)
                val nextCseq = cseqCounter.incrementAndGet()
                dialog.cseq = nextCseq
                val invite = SipMessageCodec.buildInviteRequest(
                    targetUri = dialog.targetUri,
                    domain = account.domain,
                    username = account.username,
                    displayName = account.displayName,
                    localIp = localIp,
                    localSipPort = localPort,
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
                    localIp = localIp,
                    localSipPort = localPort,
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
        val fromHeader = msg.firstHeader("From").orEmpty()
        val displayName = fromHeader.substringBefore('<').replace("\"", "").trim()
        val fromUri = fromHeader.substringAfter('<', fromHeader).substringBefore('>').removePrefix("sip:")
        val remoteSdp = SipMessageCodec.parseSdp(msg.body)

        val toTag = SipMessageCodec.newTag()
        val ringing = SipMessageCodec.buildResponse(
            180, "Ringing", msg, localIp, localPort,
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
            SipSignalingEvent.IncomingInvite(callId, fromUri, displayName.ifBlank { fromUri }, remoteSdp, msg)
        )
    }

    private suspend fun handleIncomingTerminate(msg: SipRawMessage, callId: String, account: SipAccountModel) {
        val ok = SipMessageCodec.buildResponse(
            200, "OK", msg, localIp, localPort,
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

        val sdp = SipMessageCodec.buildSdpOffer(localIp, localRtpPort, preferWideband)
        val invite = SipMessageCodec.buildInviteRequest(
            targetUri = targetUri,
            domain = account.domain,
            username = account.username,
            displayName = account.displayName,
            localIp = localIp,
            localSipPort = localPort,
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
        val sdp = SipMessageCodec.buildSdpOffer(localIp, localRtpPort, preferWideband)
        val ok = SipMessageCodec.buildResponse(
            statusCode = 200,
            reasonPhrase = "OK",
            request = req,
            localIp = localIp,
            localPort = localPort,
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
        val sdp = SipMessageCodec.buildSdpOffer(localIp, dialog.localRtpPort, direction = direction)
        val nextCseq = cseqCounter.incrementAndGet()
        dialog.cseq = nextCseq
        val reInvite = SipMessageCodec.buildSimpleInDialogRequest(
            method = "INVITE",
            targetUri = dialog.remoteTargetUri,
            fromHeader = dialog.fromHeader,
            toHeader = dialog.toHeader,
            localIp = localIp,
            localSipPort = localPort,
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
            localIp = localIp,
            localSipPort = localPort,
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
            localIp = localIp,
            localSipPort = localPort,
            transport = dialog.account.transport,
            callId = callId,
            cseq = cseqCounter.incrementAndGet()
        )
        sendRaw(bye, dialog.account)
    }

    private fun sendRegister(account: SipAccountModel, password: String) {
        val req = SipMessageCodec.buildRegisterRequest(
            domain = account.domain,
            username = account.username,
            localIp = localIp,
            localPort = localPort,
            transport = account.transport,
            callId = SipMessageCodec.newCallId(account.domain),
            cseq = cseqCounter.incrementAndGet(),
            fromTag = SipMessageCodec.newTag(),
            branch = SipMessageCodec.newBranch()
        )
        sendRaw(req, account)
    }

    private fun sendRaw(message: String, account: SipAccountModel): Boolean {
        val bytes = message.toByteArray(Charsets.UTF_8)
        return runCatching {
            when (account.transport) {
                SipTransport.UDP -> {
                    val sock = udpSocket ?: return false
                    sock.send(DatagramPacket(bytes, bytes.size))
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
