package com.example.sip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import com.example.data.repository.SipAccountRepository
import com.example.domain.ai.CallEventBus
import com.example.domain.ai.OmniCallEvent
import com.example.domain.model.CallSourceType
import com.example.domain.model.SipAccountModel
import com.example.domain.model.SipRegistrationStatus
import com.example.domain.model.SipTransport
import com.example.telecom.SipBackgroundService
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

sealed interface SipSignalingEvent {
    data class OutgoingRinging(val callId: String, val remoteSdp: SdpMediaSpec? = null) : SipSignalingEvent
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
    data class CallFailed(
        val callId: String,
        val reason: String,
        val statusCode: Int = 0,
        val beforeEarlyRinging: Boolean = false
    ) : SipSignalingEvent
}

data class SipNetworkHealthResult(
    val isReachable: Boolean,
    val networkLabel: String,
    val localIp: String,
    val proxyHost: String,
    val transport: SipTransport,
    val port: Int,
    val latencyMs: Long? = null,
    val sipStatusCode: Int? = null,
    val summary: String
)

sealed interface SipTransportValidationResult {
    data class Valid(
        val resolvedAddress: InetAddress,
        val transport: SipTransport,
        val port: Int,
        val localIp: String
    ) : SipTransportValidationResult

    data class Invalid(val reason: String) : SipTransportValidationResult
}

private data class PeerEndpoint(
    val address: InetAddress,
    val port: Int
)

private data class ActiveSipDialog(
    val callId: String,
    val account: SipAccountModel,
    val targetUri: String,
    val requestUri: String,
    val fromHeader: String,
    var toHeader: String,
    var remoteTargetUri: String,
    var localRtpPort: Int,
    var cseq: Int,
    val isOutgoing: Boolean = true,
    var lastBranch: String = "",
    val preferWideband: Boolean = true,
    var authAttempts: Int = 0,
    var routeSet: List<String> = emptyList(),
    var peerEndpoint: PeerEndpoint? = null,
    @Volatile var gotResponse: Boolean = false,
    @Volatile var gotEarlyRinging: Boolean = false,
    @Volatile var isConfirmed: Boolean = false,
    @Volatile var ackReceived: Boolean = false,
    var remoteSdp: SdpMediaSpec? = null,
    var lastInviteRequest: SipRawMessage? = null
)

/**
 * Pure-Kotlin RFC 3261 SIP User Agent managing NAT-aware registration keep-alive,
 * pre-dial proxy/transport validation, outgoing/incoming INVITE dialogs, Digest auth,
 * Record-Route/Route sets, Hold re-INVITE, CANCEL/487, and INFO DTMF.
 */
class SipClientEngine(
    private val context: Context,
    private val sipAccountRepository: SipAccountRepository,
    private val eventBus: CallEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cseqCounter = AtomicInteger(10)
    private val transportMutex = Mutex()

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
    @Volatile private var sentMappedContactOnRegister: Boolean = false
    @Volatile private var registeredExpiresSec: Int = 120
    @Volatile private var registerCallId: String = ""
    @Volatile private var registerFromTag: String = ""
    @Volatile private var registerAuthAttempts: Int = 0
    @Volatile private var activeNetworkHandle: Long? = null
    @Volatile private var lastTransportError: String? = null
    @Volatile private var lastNetworkHealthCache: CachedNetworkHealth? = null

    private data class CachedNetworkHealth(
        val accountId: Long,
        val networkHandle: Long?,
        val localIp: String,
        val checkedAtMs: Long,
        val result: SipNetworkHealthResult
    )

    private val dialogs = ConcurrentHashMap<String, ActiveSipDialog>()

    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val netHandle = network.networkHandle
            val previousHandle = activeNetworkHandle
            activeNetworkHandle = netHandle

            // Never tear down sockets during an active call, or on initial registration callback
            // when the transport socket was already intentionally opened for this same network.
            if (dialogs.isNotEmpty()) return
            val hasOpenSocket = isActiveSocketOpen(activeAccount?.transport)
            if (previousHandle == null && hasOpenSocket) return
            if (previousHandle == netHandle && hasOpenSocket) return

            scheduleNetworkReconnect()
        }

        override fun onLost(network: Network) {
            if (activeNetworkHandle == network.networkHandle) {
                activeNetworkHandle = null
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (hasInternet && activeAccount != null && dialogs.isEmpty() && !isActiveSocketOpen(activeAccount?.transport)) {
                activeNetworkHandle = network.networkHandle
                scheduleNetworkReconnect()
            }
        }
    }

    init {
        scope.launch {
            registerConnectivityWatcher()
        }
    }

    private fun isActiveSocketOpen(transport: SipTransport?): Boolean = when (transport) {
        SipTransport.UDP -> udpSocket != null && udpSocket?.isClosed == false
        SipTransport.TCP, SipTransport.TLS -> tcpSocket != null && tcpSocket?.isClosed == false && tcpSocket?.isConnected == true
        null -> (udpSocket != null && udpSocket?.isClosed == false) ||
            (tcpSocket != null && tcpSocket?.isClosed == false)
    }

    private fun registerConnectivityWatcher() {
        if (networkCallbackRegistered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            activeNetworkHandle = cm.activeNetwork?.networkHandle
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
            networkCallbackRegistered = true
        }
    }

    private fun scheduleNetworkReconnect() {
        val acc = activeAccount ?: return
        if (!acc.isEnabled || dialogs.isNotEmpty()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(800L)
            val currentAcc = activeAccount ?: return@launch
            if (currentAcc.isEnabled && dialogs.isEmpty()) {
                startAccountRegistration(currentAcc)
            }
        }
    }

    /**
     * Pre-dial SIP proxy & UDP/TCP/TLS transport validation.
     * Ensures configuration, credentials, DNS resolution, and the configured socket transport
     * are valid and ready BEFORE transitioning the UI/call state into DIALING.
     */
    suspend fun validateOutgoingTransport(
        account: SipAccountModel,
        destination: String
    ): SipTransportValidationResult = withContext(Dispatchers.IO) {
        val syntaxError = validateAccountSettingsSyntax(account, destination)
        if (syntaxError != null) {
            return@withContext SipTransportValidationResult.Invalid(syntaxError)
        }

        val password = sipAccountRepository.getPassword(account.id)
        if (password.isNullOrBlank()) {
            return@withContext SipTransportValidationResult.Invalid(
                "SIP credentials missing for ${account.displayName}. Re-save your SIP account in Settings."
            )
        }

        val proxyHost = account.effectiveProxyHost()
        val resolvedAddr = runCatching {
            InetAddress.getAllByName(proxyHost)
                .firstOrNull { it is Inet4Address }
                ?: InetAddress.getByName(proxyHost)
        }.getOrElse { err ->
            return@withContext SipTransportValidationResult.Invalid(
                "Cannot resolve SIP proxy '$proxyHost': ${err.message ?: "Check network connection"}"
            )
        }

        transportMutex.withLock {
            val currentIp = SipAutoConfigEngine.resolveLocalIpv4ForRemote(resolvedAddr, account.port)
            val prevAcc = activeAccount
            val sameEndpoint = prevAcc != null &&
                prevAcc.id == account.id &&
                prevAcc.effectiveProxyHost().equals(proxyHost, ignoreCase = true) &&
                prevAcc.port == account.port &&
                prevAcc.transport == account.transport &&
                localIp == currentIp

            val socketReady = sameEndpoint && isActiveSocketOpen(account.transport)
            if (!socketReady) {
                stopTransportLocked()
                activeAccount = account
                activePassword = password
                localIp = currentIp
                mappedPublicIp = null
                mappedPublicPort = null
                sentMappedContactOnRegister = false
                remoteHostAddress = resolvedAddr

                val opened = openTransportSocket(account, resolvedAddr)
                if (!opened) {
                    return@withContext SipTransportValidationResult.Invalid(
                        "Failed to open ${account.transport.label} transport to $proxyHost:${account.port}"
                    )
                }
                startIncomingReader(account)
                // Send REGISTER cleanly so proxy associates this socket & NAT mapping
                if (registerCallId.isBlank()) {
                    registerCallId = SipMessageCodec.newCallId(account.domain)
                    registerFromTag = SipMessageCodec.newTag()
                }
                sendRegister(account)
                startKeepAliveLoop(account)
            } else {
                activeAccount = account
                activePassword = password
                remoteHostAddress = resolvedAddr
            }

            SipTransportValidationResult.Valid(
                resolvedAddress = resolvedAddr,
                transport = account.transport,
                port = account.port,
                localIp = localIp
            )
        }
    }

    fun startAccountRegistration(account: SipAccountModel) {
        scope.launch {
            transportMutex.withLock {
                stopTransportLocked()
                val syntaxErr = validateAccountSettingsSyntax(account, destination = account.username.ifBlank { "test" })
                if (syntaxErr != null) {
                    sipAccountRepository.updateRegistrationStatus(
                        account.id,
                        SipRegistrationStatus.ERROR,
                        syntaxErr
                    )
                    return@launch
                }
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
                sentMappedContactOnRegister = false
                registeredExpiresSec = 120
                registerAuthAttempts = 0
                registerCallId = SipMessageCodec.newCallId(account.domain)
                registerFromTag = SipMessageCodec.newTag()
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                activeNetworkHandle = cm?.activeNetwork?.networkHandle ?: activeNetworkHandle

                sipAccountRepository.updateRegistrationStatus(account.id, SipRegistrationStatus.REGISTERING)
                eventBus.publish(
                    OmniCallEvent.SipRegistrationChanged(
                        account.id,
                        account.sipUri,
                        SipRegistrationStatus.REGISTERING
                    )
                )

                val proxyHost = account.effectiveProxyHost()
                val resolvedAddr = runCatching {
                    InetAddress.getAllByName(proxyHost)
                        .firstOrNull { it is Inet4Address }
                        ?: InetAddress.getByName(proxyHost)
                }.getOrNull()

                if (resolvedAddr != null) {
                    localIp = SipAutoConfigEngine.resolveLocalIpv4ForRemote(resolvedAddr, account.port)
                }

                val connected = resolvedAddr != null && openTransportSocket(account, resolvedAddr)
                if (!connected) {
                    sipAccountRepository.updateRegistrationStatus(
                        account.id,
                        SipRegistrationStatus.ERROR,
                        "Failed to open ${account.transport.label} socket to $proxyHost:${account.port}"
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
                startKeepAliveLoop(account)
                SipBackgroundService.startOrUpdate(
                    context = context,
                    title = "SIP Connecting • ${account.displayName}",
                    subtitle = "${account.sipUri} (${account.transport.label})"
                )
            }
        }
    }

    fun reconnectActiveAccountIfNeeded() {
        val acc = activeAccount ?: return
        if (!acc.isEnabled) return
        if (!isActiveSocketOpen(acc.transport)) {
            startAccountRegistration(acc)
        } else {
            scope.launch {
                sendNatKeepAlive(acc)
                sendRegister(acc)
            }
        }
    }

    fun ensureBackgroundServiceIfRegistered() {
        val acc = activeAccount ?: return
        if (!acc.isEnabled) return
        SipBackgroundService.startOrUpdate(
            context = context,
            title = "SIP Ready • ${acc.displayName}",
            subtitle = "Listening in background • ${acc.sipUri}"
        )
    }

    private fun startKeepAliveLoop(account: SipAccountModel) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            var elapsedSec = 0
            while (isActive) {
                delay(9_000L)
                elapsedSec += 9
                if (!isActiveSocketOpen(account.transport) && dialogs.isEmpty()) {
                    startAccountRegistration(account)
                    break
                }
                val targetAddr = remoteHostAddress
                val currentIp = if (targetAddr != null) {
                    SipAutoConfigEngine.resolveLocalIpv4ForRemote(targetAddr, account.port)
                } else {
                    SipAutoConfigEngine.resolveLocalIpv4Address()
                }
                if (currentIp != localIp && currentIp != "127.0.0.1" && dialogs.isEmpty()) {
                    // Network interface IP switched (e.g. Wi-Fi <-> 4G) — re-register on new IP
                    startAccountRegistration(account)
                    break
                }
                sendNatKeepAlive(account)
                val refreshIntervalSec = (registeredExpiresSec / 2).coerceIn(20, 55)
                if (elapsedSec >= refreshIntervalSec) {
                    elapsedSec = 0
                    sendRegister(account)
                }
            }
        }
    }

    private fun openTransportSocket(account: SipAccountModel, preResolvedAddress: InetAddress? = null): Boolean {
        return runCatching {
            val proxyHost = account.effectiveProxyHost()
            val remoteAddress = preResolvedAddress ?: InetAddress.getAllByName(proxyHost)
                .firstOrNull { it is Inet4Address }
                ?: InetAddress.getByName(proxyHost)
            remoteHostAddress = remoteAddress
            when (account.transport) {
                SipTransport.UDP -> {
                    // Unconnected DatagramSocket so load-balanced cluster SBC replies and incoming INVITEs
                    // from proxy cluster IPs/ephemeral ports are never silently dropped by the kernel.
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
                var consecutiveErrors = 0
                while (isActive) {
                    val sock = udpSocket ?: break
                    if (sock.isClosed) break
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                        consecutiveErrors = 0
                    } catch (_: java.net.PortUnreachableException) {
                        // Transient ICMP port-unreachable must never kill the active SIP listener socket
                        continue
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    } catch (_: Throwable) {
                        if (!isActive || sock.isClosed) break
                        consecutiveErrors++
                        if (consecutiveErrors >= 3) {
                            if (activeAccount?.id == account.id && activeAccount?.isEnabled == true) {
                                scheduleNetworkReconnect()
                            }
                            break
                        }
                        delay(150L)
                        continue
                    }
                    if (packet.length <= 4) continue // Ignore CRLF keepalive pongs
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val sourcePeer = packet.address?.let { PeerEndpoint(it, packet.port) }
                    handleIncomingRawPacket(raw, account, sourcePeer)
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
            handleIncomingRawPacket(fullMsg, account, sourcePeer = null)
        }
    }

    private suspend fun handleIncomingRawPacket(
        raw: String,
        account: SipAccountModel,
        sourcePeer: PeerEndpoint?
    ) {
        val msg = SipMessageCodec.parse(raw) ?: return
        val natChanged = updateMappedAddressFromVia(msg)
        val callId = msg.firstHeader("Call-ID") ?: return
        val cseqHeader = msg.firstHeader("CSeq") ?: ""
        val cseqMethod = cseqHeader.substringAfter(' ', "").trim().uppercase()

        if (!msg.isRequest) {
            when (cseqMethod) {
                "REGISTER" -> handleRegisterResponse(msg, account, natChanged)
                "INVITE" -> handleInviteResponse(msg, callId, account, sourcePeer)
            }
        } else {
            when (msg.method) {
                "INVITE" -> handleIncomingInvite(msg, callId, account, sourcePeer)
                "ACK" -> dialogs[callId]?.ackReceived = true
                "BYE", "CANCEL" -> handleIncomingTerminate(msg, callId, account, sourcePeer)
                "OPTIONS", "NOTIFY", "UPDATE" -> {
                    if (msg.method == "UPDATE") {
                        SipMessageCodec.parseSdp(msg.body)?.let { dialogs[callId]?.remoteSdp = it }
                    }
                    val ok = SipMessageCodec.buildResponse(
                        200, "OK", msg, effectiveContactIp(), effectiveContactPort(),
                        account.username, account.transport, SipMessageCodec.newTag()
                    )
                    sendRaw(ok, account, sourcePeer)
                }
            }
        }
    }

    private fun updateMappedAddressFromVia(msg: SipRawMessage): Boolean {
        val via = msg.firstHeader("Via") ?: return false
        val receivedIp = Regex("""received=([^;\s,]+)""", RegexOption.IGNORE_CASE)
            .find(via)?.groupValues?.getOrNull(1)
        val rport = Regex("""rport=(\d+)""", RegexOption.IGNORE_CASE)
            .find(via)?.groupValues?.getOrNull(1)?.toIntOrNull()
        var changed = false
        if (!receivedIp.isNullOrBlank() && receivedIp != mappedPublicIp) {
            mappedPublicIp = receivedIp
            changed = true
        }
        if (rport != null && rport in 1..65535 && rport != mappedPublicPort) {
            mappedPublicPort = rport
            changed = true
        }
        return changed
    }

    private fun effectiveContactIp(): String = mappedPublicIp ?: localIp
    private fun effectiveContactPort(): Int = mappedPublicPort ?: localPort

    private suspend fun handleRegisterResponse(
        msg: SipRawMessage,
        account: SipAccountModel,
        natChanged: Boolean
    ) {
        when (msg.statusCode) {
            in 200..299 -> {
                registerAuthAttempts = 0
                SipMessageCodec.extractContactExpiresSec(msg)?.let { exp ->
                    registeredExpiresSec = exp
                }
                // If initial REGISTER used private localIp in Contact before learning NAT mapping,
                // immediately refresh REGISTER once with mapped public Contact so incoming INVITEs reach us.
                if (mappedPublicIp != null && (!sentMappedContactOnRegister || natChanged)) {
                    sendRegister(account)
                }
                sipAccountRepository.updateRegistrationStatus(account.id, SipRegistrationStatus.REGISTERED)
                SipBackgroundService.startOrUpdate(
                    context = context,
                    title = "SIP Ready • ${account.displayName}",
                    subtitle = "Listening in background • ${account.sipUri}"
                )
                eventBus.publish(
                    OmniCallEvent.SipRegistrationChanged(
                        account.id,
                        account.sipUri,
                        SipRegistrationStatus.REGISTERED
                    )
                )
            }
            401, 407 -> {
                registerAuthAttempts += 1
                if (registerAuthAttempts > 2) {
                    sipAccountRepository.updateRegistrationStatus(
                        account.id,
                        SipRegistrationStatus.ERROR,
                        "SIP auth rejected (${msg.statusCode})"
                    )
                    return
                }
                val challenge = SipMessageCodec.parseChallenge(msg) ?: return
                val password = activePassword ?: return
                val auth = SipMessageCodec.buildDigestAuthorizationHeader(
                    challenge, account.username, password, "REGISTER", "sip:${account.domain}"
                )
                val contactIp = effectiveContactIp()
                val contactPort = effectiveContactPort()
                sentMappedContactOnRegister = mappedPublicIp != null
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
                    contactIp = contactIp,
                    contactPort = contactPort
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

    private suspend fun handleInviteResponse(
        msg: SipRawMessage,
        callId: String,
        account: SipAccountModel,
        sourcePeer: PeerEndpoint?
    ) {
        val dialog = dialogs[callId] ?: return
        val respCseq = msg.firstHeader("CSeq")?.trim()?.substringBefore(' ')?.toIntOrNull()
        if (respCseq != null && respCseq != dialog.cseq) {
            return
        }
        dialog.gotResponse = true
        if (sourcePeer != null) {
            dialog.peerEndpoint = sourcePeer
        }

        when (msg.statusCode) {
            100 -> Unit
            180, 183 -> {
                dialog.gotEarlyRinging = true
                SipMessageCodec.parseSdp(msg.body)?.let { dialog.remoteSdp = it }
                _signalingEvents.emit(SipSignalingEvent.OutgoingRinging(callId, dialog.remoteSdp))
            }
            401, 407 -> {
                val respTo = msg.firstHeader("To") ?: dialog.toHeader
                // RFC 3261 §17.1.1.3: Non-2xx ACK MUST use the exact same Via branch as the INVITE
                val ack = SipMessageCodec.buildAckRequest(
                    targetUri = dialog.targetUri,
                    fromHeader = dialog.fromHeader,
                    toHeader = respTo,
                    localIp = localIp,
                    localSipPort = localPort,
                    transport = account.transport,
                    callId = callId,
                    cseq = dialog.cseq,
                    branch = dialog.lastBranch
                )
                sendRaw(ack, account, dialog.peerEndpoint)

                dialog.authAttempts += 1
                if (dialog.authAttempts > 2) {
                    val beforeRing = !dialog.gotEarlyRinging
                    dialogs.remove(callId)
                    _signalingEvents.emit(
                        SipSignalingEvent.CallFailed(
                            callId = callId,
                            reason = "SIP authentication rejected (${msg.statusCode})",
                            statusCode = msg.statusCode,
                            beforeEarlyRinging = beforeRing
                        )
                    )
                    return
                }

                val challenge = SipMessageCodec.parseChallenge(msg) ?: run {
                    val beforeRing = !dialog.gotEarlyRinging
                    dialogs.remove(callId)
                    _signalingEvents.emit(
                        SipSignalingEvent.CallFailed(
                            callId = callId,
                            reason = "Missing SIP auth challenge",
                            statusCode = msg.statusCode,
                            beforeEarlyRinging = beforeRing
                        )
                    )
                    return
                }
                val password = activePassword ?: return
                val auth = SipMessageCodec.buildDigestAuthorizationHeader(
                    challenge = challenge,
                    username = account.username,
                    password = password,
                    method = "INVITE",
                    uri = dialog.requestUri
                )
                val sdp = SipMessageCodec.buildSdpOffer(localIp, dialog.localRtpPort, dialog.preferWideband)
                val nextCseq = cseqCounter.incrementAndGet()
                val nextBranch = SipMessageCodec.newBranch()
                dialog.cseq = nextCseq
                dialog.lastBranch = nextBranch
                dialog.gotResponse = false

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
                    branch = nextBranch,
                    sdpBody = sdp,
                    authHeader = auth,
                    contactIp = effectiveContactIp(),
                    contactPort = effectiveContactPort()
                )
                sendRaw(invite, account, dialog.peerEndpoint)
                launchUdpInviteRetransmits(callId, nextCseq, invite, account, dialog.peerEndpoint)
            }
            in 200..299 -> {
                val alreadyConfirmed = dialog.isConfirmed
                dialog.isConfirmed = true
                val toHeader = msg.firstHeader("To") ?: dialog.toHeader
                dialog.toHeader = toHeader
                val rawContact = msg.firstHeader("Contact")
                val contactUri = rawContact?.let { SipMessageCodec.extractSipUri(it) }?.takeIf { it.isNotBlank() }
                    ?: dialog.targetUri
                dialog.remoteTargetUri = contactUri
                dialog.routeSet = msg.headerValues("Record-Route").reversed()
                val remoteSdp = SipMessageCodec.parseSdp(msg.body)
                if (remoteSdp != null) {
                    dialog.remoteSdp = remoteSdp
                }

                val ack = SipMessageCodec.buildAckRequest(
                    targetUri = contactUri,
                    fromHeader = dialog.fromHeader,
                    toHeader = toHeader,
                    localIp = localIp,
                    localSipPort = localPort,
                    transport = account.transport,
                    callId = callId,
                    cseq = dialog.cseq,
                    routeSet = dialog.routeSet
                )
                sendRaw(ack, account, dialog.peerEndpoint)
                if (!alreadyConfirmed) {
                    _signalingEvents.emit(SipSignalingEvent.CallConnected(callId, remoteSdp ?: dialog.remoteSdp))
                }
            }
            in 400..699 -> {
                val beforeRing = !dialog.gotEarlyRinging
                val respTo = msg.firstHeader("To") ?: dialog.toHeader
                val ack = SipMessageCodec.buildAckRequest(
                    targetUri = dialog.targetUri,
                    fromHeader = dialog.fromHeader,
                    toHeader = respTo,
                    localIp = localIp,
                    localSipPort = localPort,
                    transport = account.transport,
                    callId = callId,
                    cseq = dialog.cseq,
                    branch = dialog.lastBranch
                )
                sendRaw(ack, account, dialog.peerEndpoint)
                dialogs.remove(callId)
                _signalingEvents.emit(
                    SipSignalingEvent.CallFailed(
                        callId = callId,
                        reason = "SIP ${msg.statusCode} ${msg.reasonPhrase}",
                        statusCode = msg.statusCode,
                        beforeEarlyRinging = beforeRing
                    )
                )
            }
        }
    }

    private suspend fun handleIncomingInvite(
        msg: SipRawMessage,
        callId: String,
        account: SipAccountModel,
        sourcePeer: PeerEndpoint?
    ) {
        val existingDialog = dialogs[callId]
        if (existingDialog != null) {
            if (sourcePeer != null) existingDialog.peerEndpoint = sourcePeer
            val toTag = existingDialog.fromHeader.substringAfter("tag=", SipMessageCodec.newTag())
            if (existingDialog.isConfirmed) {
                SipMessageCodec.parseSdp(msg.body)?.let { existingDialog.remoteSdp = it }
                val sdp = SipMessageCodec.buildSdpOffer(localIp, existingDialog.localRtpPort, existingDialog.preferWideband)
                val ok = SipMessageCodec.buildResponse(
                    200, "OK", msg, effectiveContactIp(), effectiveContactPort(),
                    account.username, account.transport, toTag,
                    body = sdp, contentType = "application/sdp"
                )
                sendRaw(ok, account, existingDialog.peerEndpoint)
            } else {
                val ringing = SipMessageCodec.buildResponse(
                    180, "Ringing", msg, effectiveContactIp(), effectiveContactPort(),
                    account.username, account.transport, toTag
                )
                sendRaw(ringing, account, existingDialog.peerEndpoint)
            }
            return
        }

        val toTag = SipMessageCodec.newTag()
        val trying = SipMessageCodec.buildResponse(
            100, "Trying", msg, effectiveContactIp(), effectiveContactPort(),
            account.username, account.transport, ""
        )
        sendRaw(trying, account, sourcePeer)

        val fromHeader = msg.firstHeader("From").orEmpty()
        val displayName = if (fromHeader.contains('<')) {
            fromHeader.substringBefore('<').replace("\"", "").trim()
        } else {
            ""
        }
        val extractedFromUri = SipMessageCodec.extractSipUri(fromHeader).removePrefix("sip:")
        val fromUri = extractedFromUri.ifBlank { fromHeader.removePrefix("sip:") }
        val remoteSdp = SipMessageCodec.parseSdp(msg.body)

        val ringing = SipMessageCodec.buildResponse(
            180, "Ringing", msg, effectiveContactIp(), effectiveContactPort(),
            account.username, account.transport, toTag
        )
        sendRaw(ringing, account, sourcePeer)

        val rawTo = msg.firstHeader("To").orEmpty().trim()
        val bracketedTo = if (rawTo.isNotBlank() && !rawTo.contains('<') && !rawTo.contains("tag=", ignoreCase = true)) {
            "<${rawTo.substringBefore(';')}>${if (rawTo.contains(';')) ";${rawTo.substringAfter(';')}" else ""}"
        } else {
            rawTo
        }
        val localFromHeader = if (bracketedTo.contains("tag=", ignoreCase = true)) bracketedTo else "$bracketedTo;tag=$toTag"
        val remoteContact = msg.firstHeader("Contact")
            ?.let { SipMessageCodec.extractSipUri(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "sip:$fromUri"

        dialogs[callId] = ActiveSipDialog(
            callId = callId,
            account = account,
            targetUri = "sip:$fromUri",
            requestUri = SipMessageCodec.buildRequestUri(remoteContact, account.transport),
            fromHeader = localFromHeader,
            toHeader = fromHeader,
            remoteTargetUri = remoteContact,
            localRtpPort = 40000,
            cseq = 1,
            isOutgoing = false,
            routeSet = msg.headerValues("Record-Route"),
            peerEndpoint = sourcePeer,
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

    private suspend fun handleIncomingTerminate(
        msg: SipRawMessage,
        callId: String,
        account: SipAccountModel,
        sourcePeer: PeerEndpoint?
    ) {
        val dialog = dialogs.remove(callId)
        val ok = SipMessageCodec.buildResponse(
            200, "OK", msg, effectiveContactIp(), effectiveContactPort(),
            account.username, account.transport, SipMessageCodec.newTag()
        )
        sendRaw(ok, account, sourcePeer ?: dialog?.peerEndpoint)

        // RFC 3261 §9.2: When CANCEL cancels a pending INVITE, also send 487 Request Terminated for the INVITE
        if (msg.method == "CANCEL" && dialog?.lastInviteRequest != null && !dialog.isConfirmed) {
            val toTag = dialog.fromHeader.substringAfter("tag=", SipMessageCodec.newTag())
            val reqTerminated = SipMessageCodec.buildResponse(
                487, "Request Terminated", dialog.lastInviteRequest!!,
                effectiveContactIp(), effectiveContactPort(),
                account.username, account.transport, toTag
            )
            sendRaw(reqTerminated, account, sourcePeer ?: dialog.peerEndpoint)
        }

        _signalingEvents.emit(SipSignalingEvent.RemoteTerminated(callId, "Call ended by remote party"))
    }

    suspend fun placeOutgoingInvite(
        callId: String,
        destination: String,
        account: SipAccountModel,
        localRtpPort: Int,
        preferWideband: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val password = sipAccountRepository.getPassword(account.id) ?: return@withContext false

        transportMutex.withLock {
            activeAccount = account
            activePassword = password

            val resolvedAddr = remoteHostAddress ?: runCatching {
                val proxyHost = account.effectiveProxyHost()
                InetAddress.getAllByName(proxyHost)
                    .firstOrNull { it is Inet4Address }
                    ?: InetAddress.getByName(proxyHost)
            }.getOrNull()
            if (resolvedAddr != null) {
                remoteHostAddress = resolvedAddr
                localIp = SipAutoConfigEngine.resolveLocalIpv4ForRemote(resolvedAddr, account.port)
            }

            if (!isActiveSocketOpen(account.transport)) {
                if (resolvedAddr != null) {
                    localIp = SipAutoConfigEngine.resolveLocalIpv4ForRemote(resolvedAddr, account.port)
                } else {
                    localIp = SipAutoConfigEngine.resolveLocalIpv4Address()
                }
                if (!openTransportSocket(account, resolvedAddr)) return@withContext false
                startIncomingReader(account)
            }
        }

        val cleanTarget = destination.trim().removePrefix("sip:")
        val targetUri = if (cleanTarget.contains("@")) "sip:$cleanTarget" else "sip:$cleanTarget@${account.domain}"
        val requestUri = SipMessageCodec.buildRequestUri(targetUri, account.transport)
        val fromTag = SipMessageCodec.newTag()
        val fromHeader = "\"${account.displayName}\" <sip:${account.username}@${account.domain}>;tag=$fromTag"
        val cseq = cseqCounter.incrementAndGet()
        val branch = SipMessageCodec.newBranch()

        val dialog = ActiveSipDialog(
            callId = callId,
            account = account,
            targetUri = targetUri,
            requestUri = requestUri,
            fromHeader = fromHeader,
            toHeader = "<$targetUri>",
            remoteTargetUri = targetUri,
            localRtpPort = localRtpPort,
            cseq = cseq,
            isOutgoing = true,
            lastBranch = branch,
            preferWideband = preferWideband
        )
        dialogs[callId] = dialog

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
            branch = branch,
            sdpBody = sdp,
            contactIp = effectiveContactIp(),
            contactPort = effectiveContactPort()
        )
        var sent = sendRaw(invite, account)
        if (!sent) {
            // Recover stale/closed UDP or TCP/TLS socket and retry once
            val recovered = transportMutex.withLock {
                stopTransportLocked()
                val resolvedAddr = runCatching {
                    val proxyHost = account.effectiveProxyHost()
                    InetAddress.getAllByName(proxyHost)
                        .firstOrNull { it is Inet4Address }
                        ?: InetAddress.getByName(proxyHost)
                }.getOrNull()
                localIp = if (resolvedAddr != null) {
                    SipAutoConfigEngine.resolveLocalIpv4ForRemote(resolvedAddr, account.port)
                } else {
                    SipAutoConfigEngine.resolveLocalIpv4Address()
                }
                if (openTransportSocket(account, resolvedAddr)) {
                    startIncomingReader(account)
                    true
                } else {
                    false
                }
            }
            if (recovered) {
                val retrySdp = SipMessageCodec.buildSdpOffer(localIp, localRtpPort, preferWideband)
                val retryInvite = SipMessageCodec.buildInviteRequest(
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
                    branch = branch,
                    sdpBody = retrySdp,
                    contactIp = effectiveContactIp(),
                    contactPort = effectiveContactPort()
                )
                sent = sendRaw(retryInvite, account)
                if (sent) {
                    launchUdpInviteRetransmits(callId, cseq, retryInvite, account, null)
                    return@withContext true
                }
            }
            dialogs.remove(callId)
            return@withContext false
        }

        launchUdpInviteRetransmits(callId, cseq, invite, account, null)
        true
    }

    private fun launchUdpInviteRetransmits(
        callId: String,
        expectedCseq: Int,
        inviteRaw: String,
        account: SipAccountModel,
        peer: PeerEndpoint?
    ) {
        if (account.transport != SipTransport.UDP) return
        scope.launch {
            val delays = listOf(600L, 1200L)
            for (waitMs in delays) {
                delay(waitMs)
                val active = dialogs[callId] ?: break
                if (active.gotResponse || active.cseq != expectedCseq) break
                sendRaw(inviteRaw, account, peer)
            }
        }
    }

    suspend fun answerIncomingInvite(
        callId: String,
        localRtpPort: Int,
        preferWideband: Boolean
    ): SdpMediaSpec? = withContext(Dispatchers.IO) {
        val dialog = dialogs[callId] ?: return@withContext null
        val req = dialog.lastInviteRequest ?: return@withContext null
        dialog.localRtpPort = localRtpPort
        dialog.isConfirmed = true
        dialog.ackReceived = false
        val sdp = SipMessageCodec.buildSdpOffer(localIp, localRtpPort, preferWideband)
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
        sendRaw(ok, dialog.account, dialog.peerEndpoint)
        if (dialog.account.transport == SipTransport.UDP) {
            scope.launch {
                for (waitMs in listOf(500L, 1000L, 1500L)) {
                    delay(waitMs)
                    val active = dialogs[callId] ?: break
                    if (active.ackReceived) break
                    sendRaw(ok, active.account, active.peerEndpoint)
                }
            }
        }
        dialog.remoteSdp
    }

    suspend fun setHold(callId: String, hold: Boolean) = withContext(Dispatchers.IO) {
        val dialog = dialogs[callId] ?: return@withContext
        val direction = if (hold) "sendonly" else "sendrecv"
        val sdp = SipMessageCodec.buildSdpOffer(localIp, dialog.localRtpPort, dialog.preferWideband, direction = direction)
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
            body = sdp,
            routeSet = dialog.routeSet
        )
        sendRaw(reInvite, dialog.account, dialog.peerEndpoint)
        Unit
    }

    suspend fun sendDtmfInfo(callId: String, digit: Char) = withContext(Dispatchers.IO) {
        val dialog = dialogs[callId] ?: return@withContext
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
            body = body,
            routeSet = dialog.routeSet
        )
        sendRaw(info, dialog.account, dialog.peerEndpoint)
        eventBus.publish(OmniCallEvent.DtmfDigitReceived(callId, digit))
    }

    suspend fun terminateCall(callId: String) = withContext(Dispatchers.IO) {
        val dialog = dialogs.remove(callId) ?: return@withContext
        if (dialog.isOutgoing && !dialog.isConfirmed) {
            // RFC 3261 §9.1: CANCEL MUST carry the exact same Via branch and CSeq number as the INVITE
            val cancel = SipMessageCodec.buildSimpleInDialogRequest(
                method = "CANCEL",
                targetUri = dialog.targetUri,
                fromHeader = dialog.fromHeader,
                toHeader = dialog.toHeader,
                localIp = localIp,
                localSipPort = localPort,
                transport = dialog.account.transport,
                callId = callId,
                cseq = dialog.cseq,
                branch = dialog.lastBranch
            )
            sendRaw(cancel, dialog.account, dialog.peerEndpoint)
        } else if (!dialog.isOutgoing && !dialog.isConfirmed && dialog.lastInviteRequest != null) {
            val toTag = dialog.fromHeader.substringAfter("tag=", SipMessageCodec.newTag())
            val busy = SipMessageCodec.buildResponse(
                statusCode = 486,
                reasonPhrase = "Busy Here",
                request = dialog.lastInviteRequest!!,
                localIp = effectiveContactIp(),
                localPort = effectiveContactPort(),
                username = dialog.account.username,
                transport = dialog.account.transport,
                toTag = toTag
            )
            sendRaw(busy, dialog.account, dialog.peerEndpoint)
        } else {
            val bye = SipMessageCodec.buildSimpleInDialogRequest(
                method = "BYE",
                targetUri = dialog.remoteTargetUri,
                fromHeader = dialog.fromHeader,
                toHeader = dialog.toHeader,
                localIp = localIp,
                localSipPort = localPort,
                transport = dialog.account.transport,
                callId = callId,
                cseq = cseqCounter.incrementAndGet(),
                routeSet = dialog.routeSet
            )
            sendRaw(bye, dialog.account, dialog.peerEndpoint)
        }
        Unit
    }

    private fun sendRegister(account: SipAccountModel) {
        val contactIp = effectiveContactIp()
        val contactPort = effectiveContactPort()
        sentMappedContactOnRegister = mappedPublicIp != null
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
            contactIp = contactIp,
            contactPort = contactPort
        )
        sendRaw(req, account)
    }

    private fun sendNatKeepAlive(account: SipAccountModel) {
        sendRaw("\r\n\r\n", account)
    }

    private fun sendRaw(
        message: String,
        account: SipAccountModel,
        peerOverride: PeerEndpoint? = null
    ): Boolean {
        val bytes = message.toByteArray(Charsets.UTF_8)
        return runCatching {
            when (account.transport) {
                SipTransport.UDP -> {
                    val sock = udpSocket ?: error("UDP socket is closed")
                    val targetAddr = peerOverride?.address
                        ?: remoteHostAddress
                        ?: InetAddress.getAllByName(account.effectiveProxyHost())
                            .firstOrNull { it is Inet4Address }
                        ?: InetAddress.getByName(account.effectiveProxyHost()).also {
                            remoteHostAddress = it
                        }
                    val targetPort = peerOverride?.port ?: account.port
                    sock.send(DatagramPacket(bytes, bytes.size, targetAddr, targetPort))
                }
                SipTransport.TCP, SipTransport.TLS -> {
                    val out = tcpSocket?.getOutputStream() ?: error("${account.transport.label} socket is closed")
                    out.write(bytes)
                    out.flush()
                }
            }
            lastTransportError = null
            true
        }.getOrElse { err ->
            lastTransportError = err.message ?: err.javaClass.simpleName
            false
        }
    }

    fun getLastTransportError(): String? = lastTransportError

    fun describeCurrentNetwork(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)
        val hasVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val base = when {
            caps == null -> "Offline / No Internet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val carrier = runCatching { tm?.networkOperatorName?.trim() }.getOrNull().orEmpty()
                if (carrier.isNotBlank()) "Mobile ($carrier)" else "Mobile Data"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Active Network"
        }
        return if (hasVpn) "$base + VPN" else base
    }

    /**
     * Checks live SIP server reachability on the current ISP / Wi-Fi / Mobile SIM connection
     * using a lightweight RFC 3261 OPTIONS ping before placing a call or from Settings.
     */
    suspend fun checkSipNetworkHealth(
        account: SipAccountModel,
        forceFreshProbe: Boolean = false
    ): SipNetworkHealthResult = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netHandle = cm?.activeNetwork?.networkHandle
        val networkLabel = describeCurrentNetwork()
        val proxyHost = account.effectiveProxyHost()

        val resolvedAddr = runCatching {
            InetAddress.getAllByName(proxyHost)
                .firstOrNull { it is Inet4Address }
                ?: InetAddress.getByName(proxyHost)
        }.getOrElse { err ->
            return@withContext SipNetworkHealthResult(
                isReachable = false,
                networkLabel = networkLabel,
                localIp = localIp,
                proxyHost = proxyHost,
                transport = account.transport,
                port = account.port,
                summary = "$networkLabel cannot resolve $proxyHost (${err.message ?: "DNS error"})"
            )
        }

        val currentIp = SipAutoConfigEngine.resolveLocalIpv4ForRemote(resolvedAddr, account.port)
        val now = System.currentTimeMillis()
        val cached = lastNetworkHealthCache
        if (!forceFreshProbe &&
            cached != null &&
            cached.accountId == account.id &&
            cached.networkHandle == netHandle &&
            cached.localIp == currentIp &&
            cached.result.isReachable &&
            (now - cached.checkedAtMs) < 25_000L
        ) {
            return@withContext cached.result
        }

        val startMs = System.currentTimeMillis()
        val statusResult = runCatching {
            when (account.transport) {
                SipTransport.UDP -> {
                    DatagramSocket().use { probe ->
                        probe.soTimeout = 1350
                        val req = SipMessageCodec.buildOptionsRequest(
                            domain = account.domain,
                            port = account.port,
                            username = account.username,
                            localIp = currentIp,
                            localPort = probe.localPort,
                            transport = SipTransport.UDP
                        )
                        val bytes = req.toByteArray(Charsets.UTF_8)
                        probe.send(DatagramPacket(bytes, bytes.size, resolvedAddr, account.port))
                        val rxBuf = ByteArray(4096)
                        val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
                        probe.receive(rxPacket)
                        val raw = String(rxPacket.data, 0, rxPacket.length, Charsets.UTF_8)
                        SipMessageCodec.parse(raw)?.statusCode ?: 200
                    }
                }
                SipTransport.TCP -> {
                    Socket().use { probe ->
                        probe.connect(InetSocketAddress(resolvedAddr, account.port), 1500)
                        probe.soTimeout = 1350
                        val req = SipMessageCodec.buildOptionsRequest(
                            domain = account.domain,
                            port = account.port,
                            username = account.username,
                            localIp = currentIp,
                            localPort = probe.localPort,
                            transport = SipTransport.TCP
                        )
                        val out = probe.getOutputStream()
                        out.write(req.toByteArray(Charsets.UTF_8))
                        out.flush()
                        val rxBuf = ByteArray(4096)
                        val read = probe.getInputStream().read(rxBuf)
                        if (read > 0) {
                            val raw = String(rxBuf, 0, read, Charsets.UTF_8)
                            SipMessageCodec.parse(raw)?.statusCode ?: 200
                        } else {
                            200
                        }
                    }
                }
                SipTransport.TLS -> {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket() as SSLSocket).use { probe ->
                        probe.connect(InetSocketAddress(resolvedAddr, account.port), 1800)
                        probe.soTimeout = 1500
                        probe.startHandshake()
                        val req = SipMessageCodec.buildOptionsRequest(
                            domain = account.domain,
                            port = account.port,
                            username = account.username,
                            localIp = currentIp,
                            localPort = probe.localPort,
                            transport = SipTransport.TLS
                        )
                        val out = probe.getOutputStream()
                        out.write(req.toByteArray(Charsets.UTF_8))
                        out.flush()
                        val rxBuf = ByteArray(4096)
                        val read = probe.getInputStream().read(rxBuf)
                        if (read > 0) {
                            val raw = String(rxBuf, 0, read, Charsets.UTF_8)
                            SipMessageCodec.parse(raw)?.statusCode ?: 200
                        } else {
                            200
                        }
                    }
                }
            }
        }.getOrNull()

        val latency = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
        val result = if (statusResult != null) {
            SipNetworkHealthResult(
                isReachable = true,
                networkLabel = networkLabel,
                localIp = currentIp,
                proxyHost = proxyHost,
                transport = account.transport,
                port = account.port,
                latencyMs = latency,
                sipStatusCode = statusResult,
                summary = "$networkLabel • ${account.transport.label}:${account.port} Ready (${latency}ms, SIP $statusResult)"
            )
        } else {
            SipNetworkHealthResult(
                isReachable = false,
                networkLabel = networkLabel,
                localIp = currentIp,
                proxyHost = proxyHost,
                transport = account.transport,
                port = account.port,
                latencyMs = null,
                sipStatusCode = null,
                summary = "$networkLabel timed out reaching $proxyHost:${account.port} (${account.transport.label}) — ISP/SIM may block SIP"
            )
        }

        if (result.isReachable) {
            lastNetworkHealthCache = CachedNetworkHealth(
                accountId = account.id,
                networkHandle = netHandle,
                localIp = currentIp,
                checkedAtMs = now,
                result = result
            )
        }
        result
    }

    fun stopTransport() {
        activeAccount = null
        activePassword = null
        stopTransportLocked()
        SipBackgroundService.stop(context)
    }

    private fun stopTransportLocked() {
        keepAliveJob?.cancel()
        listenerJob?.cancel()
        runCatching { udpSocket?.close() }
        runCatching { tcpSocket?.close() }
        udpSocket = null
        tcpSocket = null
    }

    companion object {
        /**
         * Builds normalized dial fallback variants when an upstream SIP proxy/SBC rejects an E.164 '+'
         * formatted contact number before ringing (e.g., Bangladeshi IPTSPs expecting '01...' or '8801...').
         */
        fun buildDialFallbacks(destination: String): List<String> {
            val clean = destination.trim().removePrefix("sip:")
            if (clean.contains("@")) return emptyList()
            val bdMatch = Regex("""^\+?880(1[3-9]\d{8})$""").matchEntire(clean)
            if (bdMatch != null) {
                val subscriber = bdMatch.groupValues[1]
                return listOf("0$subscriber", "880$subscriber").filter { it != clean }
            }
            if (clean.startsWith("+")) {
                val withoutPlus = clean.removePrefix("+")
                if (withoutPlus.isNotBlank()) return listOf(withoutPlus)
            }
            return emptyList()
        }

        /**
         * Validates SIP account proxy host, port, transport protocol, and destination syntax
         * synchronously before attempting network resolution or entering DIALING state.
         */
        fun validateAccountSettingsSyntax(account: SipAccountModel, destination: String): String? {
            if (!account.isEnabled) {
                return "SIP line '${account.displayName}' is disabled. Enable it in Settings."
            }
            val dest = destination.trim()
            if (dest.isBlank() || dest.any { it.isWhitespace() }) {
                return "Enter a valid number or SIP URI before dialing."
            }
            val domain = account.domain.trim()
            if (domain.isBlank() || domain.any { it.isWhitespace() || it == '@' }) {
                return "Invalid SIP domain configured for '${account.displayName}'."
            }
            val proxyHost = account.effectiveProxyHost()
            if (proxyHost.isBlank() || proxyHost.any { it.isWhitespace() || it == '@' || it == '/' }) {
                return "Invalid SIP proxy host configured for '${account.displayName}'."
            }
            if (account.username.isBlank()) {
                return "Missing SIP username for '${account.displayName}'."
            }
            if (account.port !in 1..65535) {
                return "Invalid ${account.transport.label} port (${account.port}). Must be 1–65535."
            }
            return null
        }
    }
}
