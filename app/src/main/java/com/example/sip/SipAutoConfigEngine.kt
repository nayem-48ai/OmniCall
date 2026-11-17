package com.example.sip

import com.example.domain.model.SipTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

sealed interface AutoConfigProgress {
    data class Step(val message: String, val stepIndex: Int, val totalSteps: Int) : AutoConfigProgress
    data class Success(
        val resolvedHost: String,
        val transport: SipTransport,
        val port: Int,
        val latencyMs: Long,
        val serverBanner: String,
        val registrationVerified: Boolean,
        val diagnosticLog: List<String>
    ) : AutoConfigProgress

    data class Failure(
        val reason: String,
        val diagnosticLog: List<String>
    ) : AutoConfigProgress
}

private data class ProbeCandidate(
    val host: String,
    val port: Int,
    val transport: SipTransport,
    val sourceLabel: String
)

/**
 * Deliverable #2: Smart SIP Auto-Configuration Engine
 * ---------------------------------------------------
 * Automatically determines the optimal transport protocol (UDP -> TCP -> TLS)
 * and port from minimal user credentials (Domain/Host, Username, Password, optional Port).
 *
 * Discovery Pipeline:
 * 1. DNS SRV Discovery (_sip._udp, _sip._tcp, _sips._tcp) via [DnsSrvResolver].
 * 2. Sequential/Fallback transport probing with RFC 3261 `OPTIONS` and `REGISTER`.
 * 3. Digest MD5/SHA-256 authentication challenge verification (401/407 -> 200 OK).
 */
class SipAutoConfigEngine(private val dnsSrvResolver: DnsSrvResolver) {

    fun autoConfigure(
        domainInput: String,
        username: String,
        password: String,
        optionalPort: Int? = null
    ): Flow<AutoConfigProgress> = flow {
        val log = mutableListOf<String>()
        val cleanDomain = domainInput.trim().removePrefix("sip:").substringBefore("/").trim()
        val hostOnly = cleanDomain.substringBefore(":")
        val inlinePort = cleanDomain.substringAfter(":", "").toIntOrNull()
        val overridePort = optionalPort?.takeIf { it in 1..65535 } ?: inlinePort

        if (hostOnly.isBlank() || username.isBlank() || password.isBlank()) {
            emit(AutoConfigProgress.Failure("Host, username, and password are required.", log))
            return@flow
        }

        emit(AutoConfigProgress.Step("Resolving DNS SRV & A records for $hostOnly...", 1, 5))
        val srvEndpoints = runCatching { dnsSrvResolver.resolveSipSrvRecords(hostOnly) }.getOrDefault(emptyList())
        if (srvEndpoints.isNotEmpty()) {
            log.add("DNS SRV discovered ${srvEndpoints.size} endpoint(s): " +
                srvEndpoints.joinToString { "${it.transport.label}://${it.targetHost}:${it.port}" })
        } else {
            log.add("No DNS SRV records found; falling back to standard UDP -> TCP -> TLS probing.")
        }

        val candidates = buildCandidateQueue(hostOnly, overridePort, srvEndpoints)
        val localIp = resolveLocalIpv4Address()

        var stepIdx = 2
        for (candidate in candidates) {
            val stepNum = stepIdx.coerceAtMost(4)
            emit(
                AutoConfigProgress.Step(
                    "Probing ${candidate.transport.label} on ${candidate.host}:${candidate.port}...",
                    stepNum,
                    5
                )
            )
            stepIdx++

            val probeStart = System.currentTimeMillis()
            val probeResult = probeCandidate(
                candidate = candidate,
                domain = hostOnly,
                username = username.trim(),
                password = password,
                localIp = localIp
            )
            val latencyMs = (System.currentTimeMillis() - probeStart).coerceAtLeast(1L)

            if (probeResult.reachable) {
                log.add(
                    "${candidate.transport.label}:${candidate.port} responded in ${latencyMs}ms " +
                        "(server=${probeResult.serverBanner}, authVerified=${probeResult.authSucceeded})"
                )
                emit(AutoConfigProgress.Step("Verifying SIP REGISTER & locking transport...", 5, 5))
                if (probeResult.authSucceeded) {
                    emit(
                        AutoConfigProgress.Success(
                            resolvedHost = candidate.host,
                            transport = candidate.transport,
                            port = candidate.port,
                            latencyMs = latencyMs,
                            serverBanner = probeResult.serverBanner,
                            registrationVerified = true,
                            diagnosticLog = log
                        )
                    )
                    return@flow
                } else {
                    emit(
                        AutoConfigProgress.Failure(
                            reason = probeResult.errorDetail
                                ?: "SIP server reachable on ${candidate.transport.label}:${candidate.port}, but authentication was rejected. Check username/password.",
                            diagnosticLog = log
                        )
                    )
                    return@flow
                }
            } else {
                log.add("${candidate.transport.label}:${candidate.port} failed: ${probeResult.errorDetail}")
            }
        }

        emit(
            AutoConfigProgress.Failure(
                reason = "Unable to reach SIP server at $hostOnly over UDP, TCP, or TLS. Verify domain, port, and network connectivity.",
                diagnosticLog = log
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun buildCandidateQueue(
        host: String,
        overridePort: Int?,
        srvEndpoints: List<SrvEndpoint>
    ): List<ProbeCandidate> {
        val candidates = mutableListOf<ProbeCandidate>()
        if (overridePort != null) {
            // Respect user-supplied port while still testing UDP -> TCP -> TLS automatically
            candidates.add(ProbeCandidate(host, overridePort, SipTransport.UDP, "Custom Port"))
            candidates.add(ProbeCandidate(host, overridePort, SipTransport.TCP, "Custom Port"))
            candidates.add(ProbeCandidate(host, overridePort, SipTransport.TLS, "Custom Port"))
        } else {
            srvEndpoints.forEach { srv ->
                candidates.add(ProbeCandidate(srv.targetHost, srv.port, srv.transport, "DNS SRV"))
            }
            // Sequential standard fallback: UDP (5060) -> TCP (5060) -> TLS (5061)
            val fallbacks = listOf(
                ProbeCandidate(host, 5060, SipTransport.UDP, "Standard UDP"),
                ProbeCandidate(host, 5060, SipTransport.TCP, "Standard TCP"),
                ProbeCandidate(host, 5061, SipTransport.TLS, "Standard TLS")
            )
            fallbacks.forEach { fb ->
                if (candidates.none { it.host == fb.host && it.port == fb.port && it.transport == fb.transport }) {
                    candidates.add(fb)
                }
            }
        }
        return candidates
    }

    private data class ProbeOutcome(
        val reachable: Boolean,
        val authSucceeded: Boolean,
        val serverBanner: String = "SIP/2.0",
        val errorDetail: String? = null
    )

    private fun probeCandidate(
        candidate: ProbeCandidate,
        domain: String,
        username: String,
        password: String,
        localIp: String
    ): ProbeOutcome {
        val address = runCatching { InetAddress.getByName(candidate.host) }.getOrElse {
            return ProbeOutcome(false, false, errorDetail = "DNS resolution failed (${it.message})")
        }

        val callId = SipMessageCodec.newCallId(domain)
        val fromTag = SipMessageCodec.newTag()
        val initialRegister = SipMessageCodec.buildRegisterRequest(
            domain = domain,
            username = username,
            localIp = localIp,
            localPort = candidate.port,
            transport = candidate.transport,
            callId = callId,
            cseq = 1,
            fromTag = fromTag,
            branch = SipMessageCodec.newBranch(),
            expiresSec = 300
        )

        val firstRaw = runCatching {
            sendAndReceive(candidate.transport, address, candidate.port, initialRegister)
        }.getOrElse {
            return ProbeOutcome(false, false, errorDetail = it.message ?: "Socket timeout")
        } ?: return ProbeOutcome(false, false, errorDetail = "No SIP response received")

        val firstParsed = SipMessageCodec.parse(firstRaw)
            ?: return ProbeOutcome(false, false, errorDetail = "Malformed SIP response")

        val banner = firstParsed.firstHeader("Server")
            ?: firstParsed.firstHeader("User-Agent")
            ?: "SIP Proxy"

        return when (firstParsed.statusCode) {
            in 200..299 -> ProbeOutcome(reachable = true, authSucceeded = true, serverBanner = banner)
            401, 407 -> {
                val challenge = SipMessageCodec.parseChallenge(firstParsed)
                    ?: return ProbeOutcome(true, false, banner, "Missing Digest challenge header")
                val authHeader = SipMessageCodec.buildDigestAuthorizationHeader(
                    challenge = challenge,
                    username = username,
                    password = password,
                    method = "REGISTER",
                    uri = "sip:$domain"
                )
                val authedRegister = SipMessageCodec.buildRegisterRequest(
                    domain = domain,
                    username = username,
                    localIp = localIp,
                    localPort = candidate.port,
                    transport = candidate.transport,
                    callId = callId,
                    cseq = 2,
                    fromTag = fromTag,
                    branch = SipMessageCodec.newBranch(),
                    expiresSec = 300,
                    authHeader = authHeader
                )
                val secondRaw = runCatching {
                    sendAndReceive(candidate.transport, address, candidate.port, authedRegister)
                }.getOrNull() ?: return ProbeOutcome(true, false, banner, "Auth response timeout")

                val secondParsed = SipMessageCodec.parse(secondRaw)
                if (secondParsed != null && secondParsed.statusCode in 200..299) {
                    ProbeOutcome(reachable = true, authSucceeded = true, serverBanner = banner)
                } else {
                    val code = secondParsed?.statusCode ?: 0
                    val reason = secondParsed?.reasonPhrase ?: "Rejected"
                    ProbeOutcome(true, false, banner, "SIP $code $reason")
                }
            }
            else -> ProbeOutcome(
                reachable = true,
                authSucceeded = false,
                serverBanner = banner,
                errorDetail = "SIP ${firstParsed.statusCode} ${firstParsed.reasonPhrase}"
            )
        }
    }

    private fun sendAndReceive(
        transport: SipTransport,
        address: InetAddress,
        port: Int,
        payload: String
    ): String? {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        return when (transport) {
            SipTransport.UDP -> {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 2200
                    socket.send(DatagramPacket(bytes, bytes.size, address, port))
                    val rxBuf = ByteArray(8192)
                    val rxPacket = DatagramPacket(rxBuf, rxBuf.size)
                    socket.receive(rxPacket)
                    String(rxPacket.data, 0, rxPacket.length, Charsets.UTF_8)
                }
            }
            SipTransport.TCP -> {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 2500)
                    socket.soTimeout = 2500
                    socket.getOutputStream().apply {
                        write(bytes)
                        flush()
                    }
                    readSipStreamMessage(BufferedInputStream(socket.getInputStream()))
                }
            }
            SipTransport.TLS -> {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket() as SSLSocket).use { socket ->
                    socket.connect(InetSocketAddress(address, port), 3000)
                    socket.soTimeout = 3000
                    socket.startHandshake()
                    socket.getOutputStream().apply {
                        write(bytes)
                        flush()
                    }
                    readSipStreamMessage(BufferedInputStream(socket.getInputStream()))
                }
            }
        }
    }

    private fun readSipStreamMessage(input: BufferedInputStream): String? {
        val buffer = ByteArray(8192)
        val sb = StringBuilder()
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, 0, buffer.size - totalRead)
            if (read <= 0) break
            sb.append(String(buffer, 0, read, Charsets.UTF_8))
            totalRead += read
            val text = sb.toString()
            if (text.contains("\r\n\r\n") || text.contains("\n\n")) {
                return text
            }
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    companion object {
        fun resolveLocalIpv4Address(): String {
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress ?: "127.0.0.1"
            }.getOrDefault("127.0.0.1")
        }
    }
}
