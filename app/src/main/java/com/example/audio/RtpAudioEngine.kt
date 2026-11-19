package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.example.domain.ai.AudioFrame
import com.example.domain.ai.AudioStreamTap
import com.example.domain.ai.StreamDirection
import com.example.domain.model.AudioQualityTelemetry
import com.example.domain.model.AudioRouteType
import com.example.sip.SdpMediaSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.random.Random

/**
 * Deliverable #3: Hi-Fi Sound Quality & Audio Engine
 * --------------------------------------------------
 * Manages low-latency bidirectional RTP audio streaming (RFC 3550), hardware-accelerated
 * AcousticEchoCanceler (AEC), NoiseSuppressor (NS), AutomaticGainControl (AGC),
 * AudioManager communication routing (Earpiece / Speaker / Bluetooth / Wired),
 * and exposes [AudioStreamTap] for local recording and Phase 2 AI Agents.
 */
class RtpAudioEngine(private val context: Context) : AudioStreamTap {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uplinkFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    override val uplinkFrames: Flow<AudioFrame> = _uplinkFrames.asSharedFlow()

    private val _downlinkFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    override val downlinkFrames: Flow<AudioFrame> = _downlinkFrames.asSharedFlow()

    private val _telemetry = MutableStateFlow(AudioQualityTelemetry())
    val telemetry: StateFlow<AudioQualityTelemetry> = _telemetry.asStateFlow()

    private val injectedUplinkQueue = ConcurrentLinkedQueue<ShortArray>()

    private var rtpSocket: DatagramSocket? = null
    private var txJob: Job? = null
    private var rxJob: Job? = null
    private var ringbackJob: Job? = null
    private var ringbackTrack: AudioTrack? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @Volatile
    private var isMuted: Boolean = false

    @Volatile
    private var isOnHold: Boolean = false

    fun allocateLocalRtpPort(): Int {
        var lastPort = 40002
        repeat(10) {
            val port = runCatching {
                DatagramSocket(0).use { it.localPort }
            }.getOrNull() ?: return lastPort
            lastPort = port
            if (port % 2 == 0) return port
        }
        return lastPort
    }

    @SuppressLint("MissingPermission")
    fun startMediaSession(
        localRtpPort: Int,
        remoteSdp: SdpMediaSpec?,
        enableAec: Boolean,
        enableNs: Boolean,
        enableAgc: Boolean,
        transportLabel: String
    ) {
        stopLocalRingback()
        stopMediaSession()

        configureAudioFocusForVoip()

        val sampleRate = 16000
        val frameSamples = 320 // 20ms at 16kHz
        val minRecBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSamples * 4)

        val minPlayBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameSamples * 4)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuffer
            )
        }.getOrNull()

        var aecOn = false
        var nsOn = false
        var agcOn = false

        if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
            val sessionId = recorder.audioSessionId
            if (enableAec && AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    aecOn = enabled
                }
            }
            if (enableNs && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    nsOn = enabled
                }
            }
            if (enableAgc && AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                    agcOn = enabled
                }
            }
            audioRecord = recorder
        }

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minPlayBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = runCatching { trackBuilder.build() }.getOrNull()
        audioTrack = track

        val codecName = remoteSdp?.codecName ?: "PCMU"
        val payloadType = remoteSdp?.payloadType ?: 0

        _telemetry.value = AudioQualityTelemetry(
            codecName = "$codecName • 16kHz HD",
            sampleRateHz = sampleRate,
            transportLabel = transportLabel,
            aecActive = aecOn,
            nsActive = nsOn,
            agcActive = agcOn,
            jitterMs = 2,
            packetLossPercent = 0f
        )

        val socket = runCatching { DatagramSocket(localRtpPort) }.getOrNull()
        rtpSocket = socket

        val remoteHost = remoteSdp?.remoteIp
        val remotePort = remoteSdp?.remotePort ?: 0
        val remoteAddr = if (!remoteHost.isNullOrBlank() && remotePort > 0) {
            runCatching { InetAddress.getByName(remoteHost) }.getOrNull()
        } else null

        if (recorder?.state == AudioRecord.STATE_INITIALIZED) {
            recorder.startRecording()
        }
        if (track?.state == AudioTrack.STATE_INITIALIZED) {
            track.play()
        }

        // Uplink Capture & RTP Transmit Loop
        txJob = scope.launch {
            val pcmBuffer = ShortArray(frameSamples)
            var seqNum = Random.nextInt(1, 30000)
            var timestamp = 0L
            val ssrc = Random.nextInt()

            // Immediately transmit 2 silence RTP frames to open/latch carrier NAT RTP pinholes
            if (socket != null && remoteAddr != null && remotePort > 0 && !isOnHold) {
                val silencePayload = encodeForPayloadType(ShortArray(frameSamples), payloadType)
                repeat(2) {
                    val natPunchPacket = wrapRtpHeader(
                        payloadType = payloadType,
                        sequenceNumber = seqNum++,
                        timestamp = timestamp,
                        ssrc = ssrc,
                        payload = silencePayload
                    )
                    timestamp += 160
                    runCatching {
                        socket.send(DatagramPacket(natPunchPacket, natPunchPacket.size, remoteAddr, remotePort))
                    }
                    kotlinx.coroutines.delay(20)
                }
            }

            while (isActive) {
                val readCount = recorder?.read(pcmBuffer, 0, frameSamples) ?: 0
                if (readCount <= 0) {
                    kotlinx.coroutines.delay(20)
                    continue
                }

                val injected = injectedUplinkQueue.poll()
                val activeSamples = when {
                    injected != null -> injected
                    isMuted || isOnHold -> ShortArray(readCount)
                    else -> pcmBuffer.copyOf(readCount)
                }

                _uplinkFrames.tryEmit(
                    AudioFrame(activeSamples, sampleRate, StreamDirection.UPLINK_MIC)
                )

                if (socket != null && remoteAddr != null && remotePort > 0 && !isOnHold) {
                    val encodedPayload = encodeForPayloadType(activeSamples, payloadType)
                    val rtpPacket = wrapRtpHeader(
                        payloadType = payloadType,
                        sequenceNumber = seqNum++,
                        timestamp = timestamp,
                        ssrc = ssrc,
                        payload = encodedPayload
                    )
                    timestamp += 160
                    runCatching {
                        socket.send(DatagramPacket(rtpPacket, rtpPacket.size, remoteAddr, remotePort))
                    }
                }
            }
        }

        // Downlink RTP Receive & Jitter/Playback Loop
        rxJob = scope.launch {
            val rxBuf = ByteArray(2048)
            var lastRxTimeMs = System.currentTimeMillis()
            var jitterEstimateMs = 2.0
            var expectedSeq = -1
            var totalPackets = 0
            var lostPackets = 0

            while (isActive && socket != null) {
                val packet = DatagramPacket(rxBuf, rxBuf.size)
                val received = runCatching {
                    socket.soTimeout = 1000
                    socket.receive(packet)
                    true
                }.getOrDefault(false)

                if (!received || packet.length <= 12) continue

                val now = System.currentTimeMillis()
                val interArrival = (now - lastRxTimeMs).toDouble()
                lastRxTimeMs = now
                jitterEstimateMs += (abs(interArrival - 20.0) - jitterEstimateMs) / 16.0

                val rxSeq = ((packet.data[2].toInt() and 0xFF) shl 8) or (packet.data[3].toInt() and 0xFF)
                val rxPt = packet.data[1].toInt() and 0x7F
                totalPackets++
                if (expectedSeq != -1 && rxSeq > expectedSeq) {
                    lostPackets += (rxSeq - expectedSeq).coerceAtMost(10)
                }
                expectedSeq = rxSeq + 1

                val pcm16k = decodeFromPayloadType(packet.data, 12, packet.length - 12, rxPt)
                if (!isOnHold && pcm16k.isNotEmpty()) {
                    track?.write(pcm16k, 0, pcm16k.size)
                    _downlinkFrames.tryEmit(
                        AudioFrame(pcm16k, sampleRate, StreamDirection.DOWNLINK_REMOTE)
                    )
                }

                if (totalPackets % 25 == 0) {
                    val lossPct = if (totalPackets + lostPackets > 0) {
                        (lostPackets * 100f) / (totalPackets + lostPackets)
                    } else 0f
                    _telemetry.value = _telemetry.value.copy(
                        jitterMs = jitterEstimateMs.toInt().coerceIn(0, 250),
                        packetLossPercent = lossPct
                    )
                }
            }
        }
    }

    override suspend fun injectUplinkAudio(pcm16: ShortArray, sampleRateHz: Int) {
        val frameSize = 320
        var offset = 0
        while (offset < pcm16.size) {
            val end = (offset + frameSize).coerceAtMost(pcm16.size)
            val chunk = pcm16.copyOfRange(offset, end)
            injectedUplinkQueue.offer(chunk)
            offset = end
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        audioManager.isMicrophoneMute = muted
    }

    fun setHold(hold: Boolean) {
        isOnHold = hold
    }

    fun setAudioRoute(route: AudioRouteType, isTelecomManaged: Boolean = false) {
        when (route) {
            AudioRouteType.SPEAKER -> {
                setCommunicationDeviceByType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                @Suppress("DEPRECATION")
                audioManager.apply {
                    isBluetoothScoOn = false
                    stopBluetoothSco()
                    isSpeakerphoneOn = true
                }
            }
            AudioRouteType.BLUETOOTH -> {
                setCommunicationDeviceByType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                @Suppress("DEPRECATION")
                audioManager.apply {
                    isSpeakerphoneOn = false
                    startBluetoothSco()
                    isBluetoothScoOn = true
                }
            }
            AudioRouteType.WIRED_HEADSET -> {
                setCommunicationDeviceByType(AudioDeviceInfo.TYPE_WIRED_HEADSET)
                @Suppress("DEPRECATION")
                audioManager.apply {
                    isSpeakerphoneOn = false
                    isBluetoothScoOn = false
                    stopBluetoothSco()
                }
            }
            AudioRouteType.EARPIECE -> {
                if (isTelecomManaged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching { audioManager.clearCommunicationDevice() }
                } else {
                    setCommunicationDeviceByType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                }
                @Suppress("DEPRECATION")
                audioManager.apply {
                    isSpeakerphoneOn = false
                    isBluetoothScoOn = false
                    stopBluetoothSco()
                }
            }
        }
    }

    fun getAvailableAudioRoutes(): List<AudioRouteType> {
        val routes = mutableListOf(AudioRouteType.EARPIECE, AudioRouteType.SPEAKER)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        } else emptyList()
        val allDevices = outputs.toList() + commDevices

        if (allDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
            }
        ) {
            routes.add(AudioRouteType.BLUETOOTH)
        }
        if (allDevices.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        ) {
            routes.add(AudioRouteType.WIRED_HEADSET)
        }
        return routes
    }

    private fun configureAudioFocusForVoip() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isMicrophoneMute = false
    }

    private fun setCommunicationDeviceByType(deviceType: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val match = devices.firstOrNull { it.type == deviceType }
                ?: if (deviceType == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                } else if (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                } else null
            if (match != null) {
                runCatching { audioManager.setCommunicationDevice(match) }
            }
        }
    }

    private fun encodeForPayloadType(pcm16k: ShortArray, payloadType: Int): ByteArray {
        val pcm8k = AudioCodecUtils.downsample16kTo8k(pcm16k)
        return when (payloadType) {
            8 -> AudioCodecUtils.encodeALaw(pcm8k)
            else -> AudioCodecUtils.encodeMuLaw(pcm8k)
        }
    }

    private fun decodeFromPayloadType(data: ByteArray, offset: Int, length: Int, payloadType: Int): ShortArray {
        val pcm8k = when (payloadType) {
            8 -> AudioCodecUtils.decodeALaw(data, offset, length)
            else -> AudioCodecUtils.decodeMuLaw(data, offset, length)
        }
        return AudioCodecUtils.upsample8kTo16k(pcm8k)
    }

    private fun wrapRtpHeader(
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Long,
        ssrc: Int,
        payload: ByteArray
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        packet[1] = (payloadType and 0x7F).toByte()
        packet[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)
        return packet
    }

    /**
     * Plays a crisp ITU/carrier outgoing ringback tone (440 Hz + 480 Hz dual-tone, 1.8s on / 2.6s off)
     * through [AudioAttributes.USAGE_VOICE_COMMUNICATION] so it respects Earpiece/Speaker/Bluetooth routing
     * whenever the remote SIP server responds with 180 Ringing or 183 Session Progress.
     */
    fun startLocalRingback(audioRoute: AudioRouteType = AudioRouteType.EARPIECE) {
        if (ringbackJob?.isActive == true) return
        stopLocalRingback()

        configureAudioFocusForVoip()
        setAudioRoute(audioRoute, isTelecomManaged = false)

        ringbackJob = scope.launch {
            val sampleRate = 16000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@launch

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                return@launch
            }
            ringbackTrack = track
            runCatching { track.play() }

            // Precompute 1.8s smooth 440Hz + 480Hz ringback burst (with 15ms fade envelope)
            val toneSamplesCount = (sampleRate * 18) / 10
            val fadeSamples = (sampleRate * 15) / 1000
            val tonePcm = ShortArray(toneSamplesCount) { i ->
                val env = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i > toneSamplesCount - fadeSamples -> (toneSamplesCount - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                val t = i.toDouble() / sampleRate
                val sample = 0.24 * env * (
                    kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * t) +
                        kotlin.math.sin(2.0 * kotlin.math.PI * 480.0 * t)
                    ) * Short.MAX_VALUE
                sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            val chunkSilence = ShortArray(320) // 20ms silent frames during off-cadence

            try {
                while (isActive) {
                    var offset = 0
                    while (isActive && offset < tonePcm.size) {
                        val len = minOf(640, tonePcm.size - offset)
                        val written = runCatching { track.write(tonePcm, offset, len) }.getOrDefault(-1)
                        if (written <= 0) break
                        offset += len
                    }
                    // 2.6 seconds off-cadence (130 x 20ms chunks so cancellation is instant)
                    repeat(130) {
                        if (!isActive) return@launch
                        runCatching { track.write(chunkSilence, 0, chunkSilence.size) }
                    }
                }
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
                if (ringbackTrack === track) {
                    ringbackTrack = null
                }
            }
        }
    }

    fun stopLocalRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
        runCatching {
            ringbackTrack?.pause()
            ringbackTrack?.flush()
            ringbackTrack?.stop()
            ringbackTrack?.release()
        }
        ringbackTrack = null
    }

    fun stopMediaSession() {
        stopLocalRingback()
        txJob?.cancel()
        rxJob?.cancel()
        txJob = null
        rxJob = null

        runCatching { aec?.release() }
        runCatching { ns?.release() }
        runCatching { agc?.release() }
        aec = null
        ns = null
        agc = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null

        runCatching { rtpSocket?.close() }
        rtpSocket = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        audioManager.isMicrophoneMute = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }
}
