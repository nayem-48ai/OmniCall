package com.example.telecom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import androidx.core.content.ContextCompat
import com.example.audio.DualStreamCallRecorder
import com.example.audio.RtpAudioEngine
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.CallLogRepository
import com.example.data.repository.ContactsRepository
import com.example.domain.ai.CallEventBus
import com.example.domain.ai.CallInterceptor
import com.example.domain.ai.InterceptDecision
import com.example.domain.ai.OmniCallEvent
import com.example.domain.model.ActiveCallState
import com.example.domain.model.AudioQualityTelemetry
import com.example.domain.model.AudioRouteType
import com.example.domain.model.CallDirection
import com.example.domain.model.CallLifecycleState
import com.example.domain.model.CallSourceType
import com.example.domain.model.ResolvedOutgoingRoute
import com.example.domain.model.SipAccountModel
import com.example.sip.SdpMediaSpec
import com.example.sip.SipClientEngine
import com.example.sip.SipSignalingEvent
import com.example.ui.call.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unified Call Coordinator bridging:
 * - Native SIM calls via [TelecomCallManager] + [OmniCallInCallService]
 * - Pure-Kotlin SIP VoIP calls via [SipClientEngine] + [VoipConnectionService]
 * - Hi-Fi RTP Audio & Hardware AEC/NS/AGC via [RtpAudioEngine]
 * - Manual In-Call Recording via [DualStreamCallRecorder]
 * - Phase 2 AI Agent hooks via [CallEventBus] & optional [CallInterceptor]
 */
class ActiveCallCoordinator(
    private val context: Context,
    private val telecomCallManager: TelecomCallManager,
    private val simDetector: SimSubscriptionDetector,
    private val contactsRepository: ContactsRepository,
    private val sipClientEngine: SipClientEngine,
    private val rtpAudioEngine: RtpAudioEngine,
    private val callRecorder: DualStreamCallRecorder,
    private val callLogRepository: CallLogRepository,
    private val prefsRepository: AppPreferencesRepository,
    private val notificationManager: CallNotificationManager,
    private val eventBus: CallEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val finalizeMutex = Mutex()

    private val _activeCall = MutableStateFlow(ActiveCallState())
    val activeCall: StateFlow<ActiveCallState> = _activeCall.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    var callInterceptor: CallInterceptor? = null

    private var timerJob: Job? = null
    private var telemetryJob: Job? = null
    private var localRtpPort: Int = 40002
    private var activeSipAccount: SipAccountModel? = null
    private var activeCallDirection: CallDirection = CallDirection.OUTGOING
    private var currentCallLogged: Boolean = false

    private val dtmfToneGenerator: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()
    }

    init {
        observeSipSignaling()
    }

    private fun observeSipSignaling() {
        scope.launch {
            sipClientEngine.signalingEvents.collect { event ->
                when (event) {
                    is SipSignalingEvent.OutgoingRinging -> {
                        if (_activeCall.value.callId == event.callId) {
                            _activeCall.update {
                                it.copy(
                                    lifecycle = CallLifecycleState.RINGING_OUTGOING,
                                    statusMessage = "Ringing via ${activeSipAccount?.transport?.label ?: "SIP"}..."
                                )
                            }
                        }
                    }
                    is SipSignalingEvent.CallConnected -> {
                        if (_activeCall.value.callId == event.callId) {
                            onSipMediaConnected(event.remoteSdp)
                        }
                    }
                    is SipSignalingEvent.IncomingInvite -> {
                        handleIncomingSipInvite(event)
                    }
                    is SipSignalingEvent.RemoteTerminated -> {
                        if (_activeCall.value.callId == event.callId) {
                            finalizeAndClearCall("Call ended by remote party")
                        }
                    }
                    is SipSignalingEvent.CallFailed -> {
                        if (_activeCall.value.callId == event.callId) {
                            finalizeAndClearCall(event.reason)
                        }
                    }
                }
            }
        }
    }

    fun placeCall(destination: String, route: ResolvedOutgoingRoute) {
        val trimmed = destination.trim()
        if (trimmed.isBlank()) return

        when (route) {
            is ResolvedOutgoingRoute.CellularSim -> {
                val sourceType = if (route.sim.slotIndex == 0) CallSourceType.SIM_1 else CallSourceType.SIM_2
                activeCallDirection = CallDirection.OUTGOING
                currentCallLogged = false
                val placed = telecomCallManager.placeNativeSimCall(trimmed, route.sim)
                if (!placed) return

                if (!telecomCallManager.isDefaultDialer()) {
                    scope.launch {
                        val info = contactsRepository.lookupContactInfo(trimmed)
                        callLogRepository.logCall(
                            remoteHandle = trimmed,
                            displayName = info?.displayName ?: trimmed,
                            direction = CallDirection.OUTGOING,
                            sourceType = sourceType,
                            lineLabel = route.sim.fullLabel,
                            durationSec = 0
                        )
                    }
                    return
                }

                _activeCall.value = ActiveCallState(
                    callId = UUID.randomUUID().toString(),
                    remoteHandle = trimmed,
                    displayName = trimmed,
                    sourceType = sourceType,
                    routeBadge = route.sim.fullLabel,
                    lifecycle = CallLifecycleState.DIALING,
                    isIncoming = false,
                    availableAudioRoutes = rtpAudioEngine.getAvailableAudioRoutes(),
                    telemetry = AudioQualityTelemetry(
                        codecName = "Cellular HD",
                        transportLabel = route.sim.shortLabel
                    ),
                    statusMessage = "Dialing on ${route.sim.shortLabel}..."
                )
                resolveContactInfoAsync(trimmed)
            }

            is ResolvedOutgoingRoute.SipAccount -> {
                val account = route.account
                activeSipAccount = account
                activeCallDirection = CallDirection.OUTGOING
                currentCallLogged = false
                val callId = "sip_${UUID.randomUUID().toString().take(12)}"
                localRtpPort = rtpAudioEngine.allocateLocalRtpPort()

                _activeCall.value = ActiveCallState(
                    callId = callId,
                    remoteHandle = trimmed,
                    displayName = trimmed,
                    sourceType = CallSourceType.SIP,
                    routeBadge = "${account.displayName} • ${account.transport.label}",
                    lifecycle = CallLifecycleState.DIALING,
                    isIncoming = false,
                    availableAudioRoutes = rtpAudioEngine.getAvailableAudioRoutes(),
                    telemetry = AudioQualityTelemetry(
                        codecName = "Negotiating SDP...",
                        transportLabel = "${account.transport.label}:${account.port}"
                    ),
                    statusMessage = "Sending SIP INVITE..."
                )
                resolveContactInfoAsync(trimmed)

                VoipCallForegroundService.startOrUpdate(
                    context = context,
                    title = trimmed,
                    subtitle = "Dialing via ${account.displayName}",
                    isRecording = false
                )

                val targetSipUri = if (trimmed.contains("@")) "sip:$trimmed" else "sip:$trimmed@${account.domain}"
                telecomCallManager.notifyTelecomOutgoingSipCall(targetSipUri, account, callId)

                scope.launch {
                    val prefs = prefsRepository.settingsFlow.first()
                    val sent = sipClientEngine.placeOutgoingInvite(
                        callId = callId,
                        destination = trimmed,
                        account = account,
                        localRtpPort = localRtpPort,
                        preferWideband = prefs.preferWidebandCodecs
                    )
                    if (!sent) {
                        finalizeAndClearCall("Unable to reach SIP server ${account.domain}")
                    } else {
                        eventBus.publish(
                            OmniCallEvent.CallStateChanged(
                                callId,
                                trimmed,
                                CallSourceType.SIP,
                                CallDirection.OUTGOING,
                                CallLifecycleState.DIALING
                            )
                        )
                    }
                }
            }
        }
    }

    private fun resolveContactInfoAsync(numberOrUri: String) {
        scope.launch {
            val info = contactsRepository.lookupContactInfo(numberOrUri)
            if (info != null) {
                _activeCall.update { current ->
                    if (current.remoteHandle == numberOrUri) {
                        current.copy(
                            displayName = info.displayName,
                            remotePhotoUri = info.photoUri ?: current.remotePhotoUri
                        )
                    } else current
                }
            }
        }
    }

    private suspend fun handleIncomingSipInvite(event: SipSignalingEvent.IncomingInvite) {
        val interceptor = callInterceptor
        if (interceptor != null) {
            when (interceptor.evaluateIncomingCall(event.callId, event.fromUri, CallSourceType.SIP)) {
                is InterceptDecision.RejectWithReason -> {
                    sipClientEngine.terminateCall(event.callId)
                    return
                }
                is InterceptDecision.AutoAnswerByAgent -> {
                    answerIncomingCall()
                    return
                }
                InterceptDecision.RingNormally -> Unit
            }
        }

        activeCallDirection = CallDirection.INCOMING
        currentCallLogged = false
        val account = activeSipAccount
        val badge = if (account != null) "${account.displayName} • ${account.transport.label}" else "SIP VoIP"
        val info = contactsRepository.lookupContactInfo(event.fromUri)
        val resolvedName = info?.displayName ?: event.displayName

        _activeCall.value = ActiveCallState(
            callId = event.callId,
            remoteHandle = event.fromUri,
            displayName = resolvedName,
            remotePhotoUri = info?.photoUri,
            sourceType = CallSourceType.SIP,
            routeBadge = badge,
            lifecycle = CallLifecycleState.RINGING_INCOMING,
            isIncoming = true,
            availableAudioRoutes = rtpAudioEngine.getAvailableAudioRoutes(),
            statusMessage = "Incoming VoIP Call"
        )

        telecomCallManager.notifyTelecomIncomingSipCall(event.fromUri, resolvedName, event.callId)
        notificationManager.showIncomingCallNotification(resolvedName, event.fromUri, badge)
        runCatching {
            context.startActivity(
                Intent(context, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    fun answerIncomingCall() {
        notificationManager.cancelIncomingNotification()
        val current = _activeCall.value
        if (current.sourceType != CallSourceType.SIP) {
            nativeInCallRef?.get()?.answer(VideoProfile.STATE_AUDIO_ONLY)
            return
        }

        scope.launch {
            localRtpPort = rtpAudioEngine.allocateLocalRtpPort()
            val prefs = prefsRepository.settingsFlow.first()
            val remoteSdp = sipClientEngine.answerIncomingInvite(
                callId = current.callId,
                localRtpPort = localRtpPort,
                preferWideband = prefs.preferWidebandCodecs
            )
            onSipMediaConnected(remoteSdp)
        }
    }

    fun declineIncomingCall() {
        notificationManager.cancelIncomingNotification()
        val current = _activeCall.value
        if (current.sourceType != CallSourceType.SIP) {
            activeCallDirection = CallDirection.MISSED
            nativeInCallRef?.get()?.disconnect()
            onNativeSimCallEnded()
            return
        }
        activeCallDirection = CallDirection.MISSED
        scope.launch {
            sipClientEngine.terminateCall(current.callId)
            finalizeAndClearCall("Call declined")
        }
    }

    private fun onSipMediaConnected(remoteSdp: SdpMediaSpec?) {
        val current = _activeCall.value
        val connectedTime = System.currentTimeMillis()
        _activeCall.update {
            it.copy(
                lifecycle = CallLifecycleState.ACTIVE,
                connectedAtEpochMs = connectedTime,
                statusMessage = "Connected • Hi-Fi Audio"
            )
        }
        voipConnectionRef?.get()?.setActive()

        VoipCallForegroundService.startOrUpdate(
            context = context,
            title = current.displayName.ifBlank { current.remoteHandle },
            subtitle = current.routeBadge,
            isRecording = current.isRecording
        )

        scope.launch {
            val prefs = prefsRepository.settingsFlow.first()
            rtpAudioEngine.startMediaSession(
                localRtpPort = localRtpPort,
                remoteSdp = remoteSdp,
                enableAec = prefs.enableHardwareAec,
                enableNs = prefs.enableHardwareNs,
                enableAgc = prefs.enableHardwareAgc,
                transportLabel = current.routeBadge
            )
        }

        startTimersAndTelemetry()
        eventBus.publish(
            OmniCallEvent.CallStateChanged(
                current.callId,
                current.remoteHandle,
                CallSourceType.SIP,
                activeCallDirection,
                CallLifecycleState.ACTIVE
            )
        )
    }

    fun toggleMute() {
        val nextMute = !_activeCall.value.isMuted
        _activeCall.update { it.copy(isMuted = nextMute) }
        if (_activeCall.value.sourceType == CallSourceType.SIP) {
            rtpAudioEngine.setMuted(nextMute)
        } else {
            nativeInCallServiceRef?.get()?.setMuted(nextMute)
                ?: run {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.isMicrophoneMute = nextMute
                }
        }
    }

    fun toggleHold() {
        setHold(!_activeCall.value.isOnHold)
    }

    fun setHold(hold: Boolean) {
        if (_activeCall.value.isOnHold == hold) return
        _activeCall.update {
            it.copy(
                isOnHold = hold,
                lifecycle = if (hold) CallLifecycleState.ON_HOLD else CallLifecycleState.ACTIVE
            )
        }
        if (_activeCall.value.sourceType == CallSourceType.SIP) {
            rtpAudioEngine.setHold(hold)
            scope.launch { sipClientEngine.setHold(_activeCall.value.callId, hold) }
        } else {
            val nativeCall = nativeInCallRef?.get()
            if (hold) nativeCall?.hold() else nativeCall?.unhold()
        }
    }

    fun selectAudioRoute(route: AudioRouteType) {
        _activeCall.update { it.copy(audioRoute = route) }
        if (_activeCall.value.sourceType == CallSourceType.SIP) {
            rtpAudioEngine.setAudioRoute(route)
        } else {
            nativeInCallServiceRef?.get()?.applyAudioRoute(route)
                ?: rtpAudioEngine.setAudioRoute(route)
        }
    }

    fun onSystemAudioRouteChanged(route: AudioRouteType) {
        _activeCall.update { it.copy(audioRoute = route) }
    }

    /**
     * Sends proper timed DTMF tones so IVR / AI voice agents on both Cellular SIM and SIP VoIP
     * reliably recognize digits pressed during the call.
     */
    fun sendDtmf(digit: Char) {
        val current = _activeCall.value
        playLocalDtmfFeedback(digit)
        if (current.sourceType == CallSourceType.SIP) {
            scope.launch {
                sipClientEngine.sendDtmfInfo(current.callId, digit)
                val pcmTone = generateDtmfPcm16k(digit, durationMs = 160)
                if (pcmTone.isNotEmpty()) {
                    rtpAudioEngine.injectUplinkAudio(pcmTone, 16000)
                }
            }
        } else {
            val nativeCall = nativeInCallRef?.get()
            if (nativeCall != null) {
                scope.launch {
                    runCatching { nativeCall.playDtmfTone(digit) }
                    delay(200L)
                    runCatching { nativeCall.stopDtmfTone() }
                }
            }
        }
    }

    private fun playLocalDtmfFeedback(digit: Char) {
        val tone = when (digit) {
            '0' -> ToneGenerator.TONE_DTMF_0
            '1' -> ToneGenerator.TONE_DTMF_1
            '2' -> ToneGenerator.TONE_DTMF_2
            '3' -> ToneGenerator.TONE_DTMF_3
            '4' -> ToneGenerator.TONE_DTMF_4
            '5' -> ToneGenerator.TONE_DTMF_5
            '6' -> ToneGenerator.TONE_DTMF_6
            '7' -> ToneGenerator.TONE_DTMF_7
            '8' -> ToneGenerator.TONE_DTMF_8
            '9' -> ToneGenerator.TONE_DTMF_9
            '*' -> ToneGenerator.TONE_DTMF_S
            '#' -> ToneGenerator.TONE_DTMF_P
            else -> return
        }
        runCatching { dtmfToneGenerator?.startTone(tone, 140) }
    }

    private fun generateDtmfPcm16k(digit: Char, durationMs: Int): ShortArray {
        val freqs = when (digit) {
            '1' -> 697.0 to 1209.0
            '2' -> 697.0 to 1336.0
            '3' -> 697.0 to 1477.0
            '4' -> 770.0 to 1209.0
            '5' -> 770.0 to 1336.0
            '6' -> 770.0 to 1477.0
            '7' -> 852.0 to 1209.0
            '8' -> 852.0 to 1336.0
            '9' -> 852.0 to 1477.0
            '*' -> 941.0 to 1209.0
            '0' -> 941.0 to 1336.0
            '#' -> 941.0 to 1477.0
            else -> return ShortArray(0)
        }
        val sampleRate = 16000
        val count = (sampleRate * durationMs) / 1000
        val amplitude = 11000.0
        return ShortArray(count) { i ->
            val t = i.toDouble() / sampleRate
            val v = amplitude * (sin(2.0 * PI * freqs.first * t) + sin(2.0 * PI * freqs.second * t)) * 0.5
            v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Manual in-call Record toggle button (no external announcement or consent dialog).
     */
    fun toggleManualRecording() {
        val current = _activeCall.value
        if (current.lifecycle == CallLifecycleState.IDLE) return

        scope.launch {
            if (!current.isRecording) {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    _activeCall.update {
                        it.copy(statusMessage = "Grant Microphone permission to record calls")
                    }
                    return@launch
                }

                // Ensure microphone foreground service is active on Android 14+ before opening AudioRecord
                VoipCallForegroundService.startOrUpdate(
                    context = context,
                    title = current.displayName.ifBlank { current.remoteHandle },
                    subtitle = current.routeBadge,
                    isRecording = true
                )
                delay(80L)

                val prefs = prefsRepository.settingsFlow.first()
                val started = callRecorder.startRecording(
                    callId = current.callId,
                    remoteHandle = current.remoteHandle,
                    displayName = current.displayName,
                    sourceType = current.sourceType,
                    sourceLabel = current.routeBadge.ifBlank { current.sourceType.label },
                    format = prefs.recordingFormat
                )
                if (started) {
                    _activeCall.update {
                        it.copy(
                            isRecording = true,
                            recordingDurationSec = 0,
                            statusMessage = "Recording locally..."
                        )
                    }
                } else {
                    VoipCallForegroundService.startOrUpdate(
                        context = context,
                        title = current.displayName.ifBlank { current.remoteHandle },
                        subtitle = current.routeBadge,
                        isRecording = false
                    )
                    _activeCall.update {
                        it.copy(statusMessage = "Audio capture unavailable on this line")
                    }
                }
            } else {
                val saved = callRecorder.stopRecording()
                _activeCall.update {
                    it.copy(
                        isRecording = false,
                        statusMessage = if (saved != null) "Recording saved (${saved.format.extension.uppercase()})" else "Recording stopped"
                    )
                }
                VoipCallForegroundService.startOrUpdate(
                    context = context,
                    title = current.displayName.ifBlank { current.remoteHandle },
                    subtitle = current.routeBadge,
                    isRecording = false
                )
            }
        }
    }

    fun endActiveCall() {
        val current = _activeCall.value
        if (current.lifecycle == CallLifecycleState.IDLE) return

        if (current.sourceType != CallSourceType.SIP) {
            nativeInCallRef?.get()?.disconnect()
            onNativeSimCallEnded()
            return
        }

        scope.launch {
            sipClientEngine.terminateCall(current.callId)
            finalizeAndClearCall("Call ended")
        }
    }

    fun onNativeSimCallUpdated(
        remoteHandle: String,
        displayName: String,
        accountHandleId: String?,
        lifecycle: CallLifecycleState,
        isIncoming: Boolean
    ) {
        val existing = _activeCall.value
        val sims = simDetector.detectActiveSims()
        val matchedSim = sims.firstOrNull { it.phoneAccountHandleId == accountHandleId }
            ?: sims.firstOrNull()

        val sourceType = when {
            existing.sourceType == CallSourceType.SIM_2 || matchedSim?.slotIndex == 1 -> CallSourceType.SIM_2
            else -> CallSourceType.SIM_1
        }
        val routeBadge = matchedSim?.fullLabel ?: existing.routeBadge.ifBlank { sourceType.label }

        if (existing.lifecycle == CallLifecycleState.IDLE) {
            currentCallLogged = false
            activeCallDirection = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING
        }

        val wasConnected = existing.connectedAtEpochMs
        val connectedAt = if (lifecycle == CallLifecycleState.ACTIVE && wasConnected == null) {
            System.currentTimeMillis()
        } else wasConnected

        _activeCall.value = existing.copy(
            callId = existing.callId.ifBlank { UUID.randomUUID().toString() },
            remoteHandle = remoteHandle,
            displayName = existing.displayName.takeIf { it.isNotBlank() && it != remoteHandle } ?: displayName,
            sourceType = sourceType,
            routeBadge = routeBadge,
            lifecycle = lifecycle,
            isIncoming = isIncoming,
            isOnHold = lifecycle == CallLifecycleState.ON_HOLD,
            availableAudioRoutes = rtpAudioEngine.getAvailableAudioRoutes(),
            telemetry = existing.telemetry.copy(
                codecName = "Cellular HD",
                transportLabel = matchedSim?.shortLabel ?: sourceType.label
            ),
            connectedAtEpochMs = connectedAt
        )
        resolveContactInfoAsync(remoteHandle)

        if (lifecycle == CallLifecycleState.RINGING_INCOMING) {
            notificationManager.showIncomingCallNotification(displayName, remoteHandle, routeBadge)
        } else {
            notificationManager.cancelIncomingNotification()
        }

        if (lifecycle == CallLifecycleState.ACTIVE && timerJob == null) {
            startTimersAndTelemetry()
        }
    }

    fun onNativeSimCallEnded() {
        notificationManager.cancelIncomingNotification()
        val existing = _activeCall.value
        if (existing.isIncoming && existing.connectedAtEpochMs == null) {
            activeCallDirection = CallDirection.MISSED
        }
        scope.launch { finalizeAndClearCall("Call ended") }
    }

    private fun startTimersAndTelemetry() {
        timerJob?.cancel()
        _elapsedSeconds.value = 0
        timerJob = scope.launch {
            while (isActive) {
                delay(1000L)
                _elapsedSeconds.update { it + 1 }
                if (_activeCall.value.isRecording) {
                    _activeCall.update { it.copy(recordingDurationSec = it.recordingDurationSec + 1) }
                }
            }
        }

        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            rtpAudioEngine.telemetry.collect { metrics ->
                if (_activeCall.value.sourceType == CallSourceType.SIP) {
                    _activeCall.update { it.copy(telemetry = metrics) }
                }
            }
        }
    }

    private suspend fun finalizeAndClearCall(reason: String) = finalizeMutex.withLock {
        val finished = _activeCall.value
        if (finished.lifecycle == CallLifecycleState.IDLE && finished.remoteHandle.isBlank()) {
            return@withLock
        }

        timerJob?.cancel()
        telemetryJob?.cancel()
        timerJob = null
        telemetryJob = null

        val durationSec = _elapsedSeconds.value

        var savedRecordingId: Long? = null
        if (callRecorder.isRecording) {
            savedRecordingId = callRecorder.stopRecording()?.id
        }

        rtpAudioEngine.stopMediaSession()
        notificationManager.cancelIncomingNotification()
        VoipCallForegroundService.stop(context)

        voipConnectionRef?.get()?.apply {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
        voipConnectionRef = null

        if (finished.remoteHandle.isNotBlank() && !currentCallLogged) {
            currentCallLogged = true
            callLogRepository.logCall(
                remoteHandle = finished.remoteHandle,
                displayName = finished.displayName,
                direction = activeCallDirection,
                sourceType = finished.sourceType,
                lineLabel = finished.routeBadge.ifBlank { finished.sourceType.label },
                durationSec = durationSec,
                recordingId = savedRecordingId
            )
        }

        eventBus.publish(
            OmniCallEvent.CallStateChanged(
                finished.callId,
                finished.remoteHandle,
                finished.sourceType,
                activeCallDirection,
                CallLifecycleState.ENDED
            )
        )

        _activeCall.value = ActiveCallState(statusMessage = reason)
        delay(200L)
        _activeCall.value = ActiveCallState()
        _elapsedSeconds.value = 0
    }

    companion object {
        private var voipConnectionRef: WeakReference<VoipConnectionService.OmniVoipConnection>? = null
        private var nativeInCallServiceRef: WeakReference<OmniCallInCallService>? = null
        private var nativeInCallRef: WeakReference<Call>? = null

        fun registerVoipConnection(callId: String, connection: VoipConnectionService.OmniVoipConnection) {
            voipConnectionRef = WeakReference(connection)
        }

        fun bindNativeInCallService(service: OmniCallInCallService, call: Call) {
            nativeInCallServiceRef = WeakReference(service)
            nativeInCallRef = WeakReference(call)
        }

        fun unbindNativeInCallService() {
            nativeInCallRef = null
        }
    }
}
