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
import com.example.data.repository.BlocklistRepository
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
import com.example.sip.SipMessageCodec
import com.example.sip.SipSignalingEvent
import com.example.sip.SipTransportValidationResult
import com.example.ui.call.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val blocklistRepository: BlocklistRepository,
    private val sipClientEngine: SipClientEngine,
    private val rtpAudioEngine: RtpAudioEngine,
    private val callRecorder: DualStreamCallRecorder,
    private val callLogRepository: CallLogRepository,
    private val prefsRepository: AppPreferencesRepository,
    private val notificationManager: CallNotificationManager,
    private val proximityWakeLockManager: ProximitySensorWakeLockManager,
    private val eventBus: CallEventBus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val finalizeMutex = Mutex()

    private val _activeCall = MutableStateFlow(ActiveCallState())
    val activeCall: StateFlow<ActiveCallState> = _activeCall.asStateFlow()

    private val _callErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val callErrorEvents: SharedFlow<String> = _callErrorEvents.asSharedFlow()

    val isNearEar: StateFlow<Boolean> = proximityWakeLockManager.isNearEar

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private val _isCallUiMinimized = MutableStateFlow(false)
    val isCallUiMinimized: StateFlow<Boolean> = _isCallUiMinimized.asStateFlow()

    var callInterceptor: CallInterceptor? = null

    private var timerJob: Job? = null
    private var telemetryJob: Job? = null
    private var localRtpPort: Int = 40002
    private var activeSipAccount: SipAccountModel? = null
    private var activeCallDirection: CallDirection = CallDirection.OUTGOING
    private var currentCallLogged: Boolean = false
    private var autoRecordingTriggeredForCallId: String? = null
    private var pendingSipFallbackQueue: ArrayDeque<String> = ArrayDeque()
    private var activeSipFallbackAccount: SipAccountModel? = null
    @Volatile
    private var lastExplicitAudioSelectMs: Long = 0L

    private val dtmfToneGenerator: ToneGenerator? by lazy {
        runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()
    }

    init {
        observeSipSignaling()
        observeProximityPolicy()
    }

    private fun observeProximityPolicy() {
        scope.launch {
            _activeCall.collect { state ->
                proximityWakeLockManager.updateForCallState(state.lifecycle, state.audioRoute)
            }
        }
    }

    private fun observeSipSignaling() {
        scope.launch {
            sipClientEngine.signalingEvents.collect { event ->
                when (event) {
                    is SipSignalingEvent.OutgoingRinging -> {
                        if (_activeCall.value.callId == event.callId) {
                            pendingSipFallbackQueue.clear()
                            activeSipFallbackAccount = null
                            val route = _activeCall.value.audioRoute
                            _activeCall.update {
                                it.copy(
                                    lifecycle = CallLifecycleState.RINGING_OUTGOING,
                                    statusMessage = "Ringing via ${activeSipAccount?.transport?.label ?: "SIP"}..."
                                )
                            }
                            rtpAudioEngine.startLocalRingback(route)
                        }
                    }
                    is SipSignalingEvent.CallConnected -> {
                        if (_activeCall.value.callId == event.callId) {
                            pendingSipFallbackQueue.clear()
                            activeSipFallbackAccount = null
                            onSipMediaConnected(event.remoteSdp)
                        }
                    }
                    is SipSignalingEvent.IncomingInvite -> {
                        handleIncomingSipInvite(event)
                    }
                    is SipSignalingEvent.RemoteTerminated -> {
                        if (_activeCall.value.callId == event.callId) {
                            pendingSipFallbackQueue.clear()
                            activeSipFallbackAccount = null
                            finalizeAndClearCall("Call ended by remote party")
                        }
                    }
                    is SipSignalingEvent.CallFailed -> {
                        if (_activeCall.value.callId == event.callId) {
                            val fallbackAccount = activeSipFallbackAccount
                            val outcome = classifySipFailure(
                                statusCode = event.statusCode,
                                rawReason = event.reason,
                                beforeEarlyRinging = event.beforeEarlyRinging
                            )
                            val shouldFallback = outcome.isRetryableFormatAmbiguity &&
                                pendingSipFallbackQueue.isNotEmpty() &&
                                fallbackAccount != null
                            if (shouldFallback && fallbackAccount != null) {
                                val nextDestination = pendingSipFallbackQueue.removeFirst()
                                retryOutgoingSipFallback(fallbackAccount, nextDestination)
                            } else {
                                pendingSipFallbackQueue.clear()
                                activeSipFallbackAccount = null
                                if (outcome.showErrorBanner) {
                                    _callErrorEvents.tryEmit(outcome.userMessage)
                                }
                                finalizeAndClearCall(
                                    reason = outcome.userMessage,
                                    holdStatusMs = if (!outcome.showErrorBanner) 1050L else 200L
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun retryOutgoingSipFallback(account: SipAccountModel, nextDestination: String) {
        val nextCallId = SipMessageCodec.newCallId(account.domain)
        val previousDisplay = _activeCall.value.displayName
        _activeCall.update {
            it.copy(
                callId = nextCallId,
                remoteHandle = nextDestination,
                displayName = previousDisplay.ifBlank { nextDestination },
                lifecycle = CallLifecycleState.DIALING,
                statusMessage = "Dialing ($nextDestination)..."
            )
        }
        scope.launch {
            val prefs = prefsRepository.settingsFlow.first()
            val sent = sipClientEngine.placeOutgoingInvite(
                callId = nextCallId,
                destination = nextDestination,
                account = account,
                localRtpPort = localRtpPort,
                preferWideband = prefs.preferWidebandCodecs
            )
            if (!sent) {
                pendingSipFallbackQueue.clear()
                activeSipFallbackAccount = null
                val detail = sipClientEngine.getLastTransportError()?.let { " ($it)" }.orEmpty()
                _callErrorEvents.emit(
                    "Unable to send SIP INVITE to ${account.effectiveProxyHost()}:${account.port}$detail"
                )
                finalizeAndClearCall("Unable to reach SIP server ${account.effectiveProxyHost()}")
            }
        }
    }

    fun isCallIdActive(callId: String): Boolean {
        if (callId.isBlank()) return false
        val current = _activeCall.value
        return current.callId == callId &&
            current.lifecycle != CallLifecycleState.IDLE &&
            current.lifecycle != CallLifecycleState.ENDED
    }

    fun minimizeCallUi() {
        _isCallUiMinimized.value = true
    }

    fun expandCallUi() {
        _isCallUiMinimized.value = false
    }

    fun placeCall(destination: String, route: ResolvedOutgoingRoute) {
        val trimmed = destination.trim()
        if (trimmed.isBlank()) return

        when (route) {
            is ResolvedOutgoingRoute.CellularSim -> {
                clearAllVoipConnections()
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

                _isCallUiMinimized.value = false
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
                val sipDestination = if (trimmed.contains("@") || trimmed.startsWith("sip:", ignoreCase = true)) {
                    trimmed
                } else {
                    trimmed.replace(Regex("[\\s()\\-]+"), "")
                }
                clearAllVoipConnections()
                pendingSipFallbackQueue.clear()
                activeSipFallbackAccount = null

                scope.launch {
                    // Validate SIP proxy & UDP/TCP/TLS transport settings BEFORE transitioning to DIALING
                    when (val validation = sipClientEngine.validateOutgoingTransport(account, sipDestination)) {
                        is SipTransportValidationResult.Invalid -> {
                            _callErrorEvents.emit(validation.reason)
                            return@launch
                        }
                        is SipTransportValidationResult.Valid -> {
                            activeSipAccount = account
                            activeCallDirection = CallDirection.OUTGOING
                            currentCallLogged = false
                            pendingSipFallbackQueue = ArrayDeque(SipClientEngine.buildDialFallbacks(sipDestination))
                            activeSipFallbackAccount = account
                            val callId = SipMessageCodec.newCallId(account.domain)
                            localRtpPort = rtpAudioEngine.allocateLocalRtpPort()

                            _isCallUiMinimized.value = false
                            _activeCall.value = ActiveCallState(
                                callId = callId,
                                remoteHandle = sipDestination,
                                displayName = trimmed,
                                sourceType = CallSourceType.SIP,
                                routeBadge = "${account.displayName} • ${validation.transport.label}",
                                lifecycle = CallLifecycleState.DIALING,
                                isIncoming = false,
                                availableAudioRoutes = rtpAudioEngine.getAvailableAudioRoutes(),
                                telemetry = AudioQualityTelemetry(
                                    codecName = "Negotiating SDP...",
                                    transportLabel = "${validation.transport.label}:${validation.port}"
                                ),
                                statusMessage = "Checking ${sipClientEngine.describeCurrentNetwork()}..."
                            )
                            resolveContactInfoAsync(trimmed)

                            VoipCallForegroundService.startOrUpdate(
                                context = context,
                                title = trimmed,
                                subtitle = "Dialing via ${account.displayName}",
                                isRecording = false
                            )

                            val networkHealth = sipClientEngine.checkSipNetworkHealth(account)
                            if (networkHealth.isReachable) {
                                _activeCall.update {
                                    it.copy(
                                        statusMessage = "${networkHealth.networkLabel} (${networkHealth.latencyMs}ms) • Dialing..."
                                    )
                                }
                            } else {
                                _callErrorEvents.tryEmit(networkHealth.summary)
                                _activeCall.update {
                                    it.copy(
                                        statusMessage = "${networkHealth.networkLabel} SIP timeout — trying INVITE..."
                                    )
                                }
                            }

                            val prefs = prefsRepository.settingsFlow.first()
                            val sent = sipClientEngine.placeOutgoingInvite(
                                callId = callId,
                                destination = sipDestination,
                                account = account,
                                localRtpPort = localRtpPort,
                                preferWideband = prefs.preferWidebandCodecs
                            )
                            if (!sent) {
                                pendingSipFallbackQueue.clear()
                                activeSipFallbackAccount = null
                                val detail = sipClientEngine.getLastTransportError()?.let { " ($it)" }.orEmpty()
                                _callErrorEvents.emit(
                                    "Unable to send SIP INVITE to ${account.effectiveProxyHost()}:${validation.port}$detail"
                                )
                                finalizeAndClearCall("Unable to reach SIP server ${account.effectiveProxyHost()}")
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
        }
    }

    private fun resolveContactInfoAsync(numberOrUri: String) {
        scope.launch {
            val info = contactsRepository.lookupContactInfo(numberOrUri)
            if (info != null) {
                val queryDigits = numberOrUri.filter(Char::isDigit)
                var shouldRefreshIncomingNotif = false
                var notifName = info.displayName
                var notifHandle = numberOrUri
                var notifBadge = ""
                var notifPhotoUri: String? = null
                _activeCall.update { current ->
                    val currentDigits = current.remoteHandle.filter(Char::isDigit)
                    val matchesHandle = current.remoteHandle == numberOrUri ||
                        (queryDigits.length >= 7 && currentDigits.length >= 7 &&
                            (currentDigits == queryDigits || currentDigits.takeLast(8) == queryDigits.takeLast(8)))
                    if (matchesHandle) {
                        val mergedPhoto = info.photoUri ?: current.remotePhotoUri
                        if (current.lifecycle == CallLifecycleState.RINGING_INCOMING) {
                            shouldRefreshIncomingNotif = true
                            notifName = info.displayName
                            notifHandle = current.remoteHandle
                            notifBadge = current.routeBadge
                            notifPhotoUri = mergedPhoto
                        }
                        current.copy(
                            displayName = info.displayName.ifBlank { current.displayName },
                            remotePhotoUri = mergedPhoto
                        )
                    } else current
                }
                if (shouldRefreshIncomingNotif) {
                    val isCellular = _activeCall.value.sourceType != CallSourceType.SIP
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        notificationManager.showIncomingCallNotification(
                            callerName = notifName,
                            callerHandle = notifHandle,
                            lineBadge = notifBadge,
                            callerPhotoUri = notifPhotoUri,
                            isCellularTelecom = isCellular
                        )
                    }
                }
            }
        }
    }

    private suspend fun handleIncomingSipInvite(event: SipSignalingEvent.IncomingInvite) {
        if (blocklistRepository.isBlocked(event.fromUri)) {
            sipClientEngine.terminateCall(event.callId)
            return
        }

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
        val account = event.account
        activeSipAccount = account
        val badge = "${account.displayName} • ${account.transport.label}"
        val info = contactsRepository.lookupContactInfo(event.fromUri)
        val resolvedName = info?.displayName ?: event.displayName

        _isCallUiMinimized.value = false
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

        // Keep incoming SIP calls directly on OmniCall's IncomingCallActivity + CallStyle notification
        // + VoipCallForegroundService so OEM Telecom layers never silently abort self-managed calls.
        clearAllVoipConnections()
        VoipCallForegroundService.startOrUpdate(
            context = context,
            title = resolvedName,
            subtitle = "Incoming $badge",
            isRecording = false
        )
        notificationManager.showIncomingCallNotification(
            callerName = resolvedName,
            callerHandle = event.fromUri,
            lineBadge = badge,
            callerPhotoUri = info?.photoUri,
            isCellularTelecom = false
        )
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
        pendingSipFallbackQueue.clear()
        activeSipFallbackAccount = null
        scope.launch {
            sipClientEngine.terminateCall(current.callId)
            finalizeAndClearCall("Call declined", holdStatusMs = 0L)
        }
    }

    private fun onSipMediaConnected(remoteSdp: SdpMediaSpec?) {
        rtpAudioEngine.stopLocalRingback()
        val current = _activeCall.value
        val connectedTime = System.currentTimeMillis()
        _activeCall.update {
            it.copy(
                lifecycle = CallLifecycleState.ACTIVE,
                connectedAtEpochMs = connectedTime,
                statusMessage = "Connected • Hi-Fi Audio"
            )
        }
        voipConnections[current.callId]?.get()?.setActive()

        scope.launch {
            val prefs = prefsRepository.settingsFlow.first()
            VoipCallForegroundService.startAndAwaitReady(
                context = context,
                title = current.displayName.ifBlank { current.remoteHandle },
                subtitle = current.routeBadge,
                isRecording = prefs.autoCallRecording || current.isRecording
            )
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                rtpAudioEngine.startMediaSession(
                    localRtpPort = localRtpPort,
                    remoteSdp = remoteSdp,
                    enableAec = prefs.enableHardwareAec,
                    enableNs = prefs.enableHardwareNs,
                    enableAgc = prefs.enableHardwareAgc,
                    transportLabel = current.routeBadge
                )
            }
            if (prefs.autoCallRecording) {
                triggerAutoRecordingIfNeeded()
            }
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
        lastExplicitAudioSelectMs = System.currentTimeMillis()
        _activeCall.update {
            it.copy(
                audioRoute = route,
                availableAudioRoutes = (it.availableAudioRoutes + rtpAudioEngine.getAvailableAudioRoutes()).distinct()
            )
        }
        applyAudioRouteToHardware(route, _activeCall.value.sourceType)
    }

    private fun applyAudioRouteToHardware(route: AudioRouteType, sourceType: CallSourceType) {
        if (sourceType == CallSourceType.SIP) {
            rtpAudioEngine.setAudioRoute(route, isTelecomManaged = false)
        } else {
            nativeInCallServiceRef?.get()?.applyAudioRoute(route)
            rtpAudioEngine.setAudioRoute(route, isTelecomManaged = true)
        }
    }

    fun onSystemAudioRouteChanged(
        route: AudioRouteType,
        supportedRoutes: List<AudioRouteType> = emptyList()
    ) {
        val current = _activeCall.value
        val updatedRoutes = if (supportedRoutes.isNotEmpty()) supportedRoutes else current.availableAudioRoutes

        // Guard against transient Telecom/HAL EARPIECE callbacks overriding user Speaker selection
        // right when Speaker is toggled or when call recording initializes AudioRecord/MediaRecorder.
        val withinGuardWindow = (System.currentTimeMillis() - lastExplicitAudioSelectMs) < 1500L
        if (withinGuardWindow && route == AudioRouteType.EARPIECE && current.audioRoute != AudioRouteType.EARPIECE) {
            _activeCall.update { it.copy(availableAudioRoutes = updatedRoutes) }
            applyAudioRouteToHardware(current.audioRoute, current.sourceType)
            return
        }

        _activeCall.update {
            it.copy(
                audioRoute = route,
                availableAudioRoutes = updatedRoutes
            )
        }
    }

    fun onAvailableAudioRoutesChanged(routes: List<AudioRouteType>) {
        if (routes.isEmpty()) return
        _activeCall.update { it.copy(availableAudioRoutes = routes.distinct()) }
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
     * Manual or automatic in-call recording start/stop without announcement or consent dialog.
     */
    fun toggleManualRecording() {
        val current = _activeCall.value
        if (current.lifecycle == CallLifecycleState.IDLE) return

        scope.launch {
            if (!current.isRecording) {
                startRecordingInternal()
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

    private fun triggerAutoRecordingIfNeeded() {
        val current = _activeCall.value
        if (current.lifecycle != CallLifecycleState.ACTIVE) return
        if (current.callId.isBlank() || autoRecordingTriggeredForCallId == current.callId) return
        if (current.isRecording || callRecorder.isRecording) return
        autoRecordingTriggeredForCallId = current.callId
        scope.launch {
            delay(180L)
            if (_activeCall.value.lifecycle == CallLifecycleState.ACTIVE && !_activeCall.value.isRecording) {
                startRecordingInternal()
            }
        }
    }

    private suspend fun startRecordingInternal() {
        val current = _activeCall.value
        if (current.lifecycle == CallLifecycleState.IDLE || current.isRecording) return

        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _activeCall.update {
                it.copy(statusMessage = "Grant Microphone permission to record calls")
            }
            return
        }

        // Synchronously await Foreground Service with phoneCall|microphone before opening AudioRecord/MediaRecorder
        VoipCallForegroundService.startAndAwaitReady(
            context = context,
            title = current.displayName.ifBlank { current.remoteHandle },
            subtitle = current.routeBadge,
            isRecording = true
        )
        delay(60L)

        val prefs = prefsRepository.settingsFlow.first()
        val started = callRecorder.startRecording(
            callId = current.callId,
            remoteHandle = current.remoteHandle,
            displayName = current.displayName,
            sourceType = current.sourceType,
            sourceLabel = current.routeBadge.ifBlank { current.sourceType.label },
            format = prefs.recordingFormat,
            recordingType = prefs.recordingType,
            recordingEngine = prefs.recordingEngine
        )
        if (started) {
            val activeRoute = _activeCall.value.audioRoute
            val sourceType = _activeCall.value.sourceType
            _activeCall.update {
                it.copy(
                    isRecording = true,
                    recordingDurationSec = 0,
                    statusMessage = "Recording (${prefs.recordingType.shortLabel})..."
                )
            }
            if (activeRoute != AudioRouteType.EARPIECE) {
                lastExplicitAudioSelectMs = System.currentTimeMillis()
                applyAudioRouteToHardware(activeRoute, sourceType)
                scope.launch {
                    delay(180L)
                    val latest = _activeCall.value
                    if (latest.audioRoute != AudioRouteType.EARPIECE && latest.lifecycle != CallLifecycleState.IDLE) {
                        applyAudioRouteToHardware(latest.audioRoute, latest.sourceType)
                    }
                }
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
    }

    fun endActiveCall() {
        val current = _activeCall.value
        if (current.lifecycle == CallLifecycleState.IDLE) return

        if (current.sourceType != CallSourceType.SIP) {
            nativeInCallRef?.get()?.disconnect()
            onNativeSimCallEnded()
            return
        }

        pendingSipFallbackQueue.clear()
        activeSipFallbackAccount = null
        scope.launch {
            sipClientEngine.terminateCall(current.callId)
            finalizeAndClearCall("Call ended", holdStatusMs = 0L)
        }
    }

    /**
     * Populates a zero-latency provisional ringing state synchronously from cached metadata
     * before launching [IncomingCallActivity] so there is zero cold-start delay.
     */
    fun primeFastIncomingSimCall(
        remoteHandle: String,
        displayName: String,
        accountHandleId: String?
    ) {
        val existing = _activeCall.value
        val cachedSims = simDetector.getCachedSimsNonBlocking()
        val matchedSim = cachedSims.firstOrNull { it.phoneAccountHandleId == accountHandleId }
            ?: cachedSims.firstOrNull()
        val sourceType = if (matchedSim?.slotIndex == 1) CallSourceType.SIM_2 else CallSourceType.SIM_1
        val routeBadge = matchedSim?.fullLabel ?: sourceType.label

        currentCallLogged = false
        _isCallUiMinimized.value = false
        activeCallDirection = CallDirection.INCOMING
        _activeCall.value = existing.copy(
            callId = existing.callId.ifBlank { UUID.randomUUID().toString() },
            remoteHandle = remoteHandle,
            displayName = displayName.ifBlank { remoteHandle },
            sourceType = sourceType,
            routeBadge = routeBadge,
            lifecycle = CallLifecycleState.RINGING_INCOMING,
            isIncoming = true,
            telemetry = existing.telemetry.copy(
                codecName = "Cellular HD",
                transportLabel = matchedSim?.shortLabel ?: sourceType.label
            )
        )
    }

    fun onNativeSimCallUpdated(
        remoteHandle: String,
        displayName: String,
        accountHandleId: String?,
        lifecycle: CallLifecycleState,
        isIncoming: Boolean
    ) {
        val existing = _activeCall.value
        val nonBlockingSims = simDetector.getCachedSimsNonBlocking()
        val matchedSim = nonBlockingSims.firstOrNull { it.phoneAccountHandleId == accountHandleId }
            ?: nonBlockingSims.firstOrNull()

        val sourceType = when {
            existing.sourceType == CallSourceType.SIM_2 || matchedSim?.slotIndex == 1 -> CallSourceType.SIM_2
            else -> CallSourceType.SIM_1
        }
        val routeBadge = matchedSim?.fullLabel ?: existing.routeBadge.ifBlank { sourceType.label }

        if (existing.lifecycle == CallLifecycleState.IDLE) {
            currentCallLogged = false
            _isCallUiMinimized.value = false
            activeCallDirection = if (isIncoming) CallDirection.INCOMING else CallDirection.OUTGOING
        }

        val wasConnected = existing.connectedAtEpochMs
        val connectedAt = if (lifecycle == CallLifecycleState.ACTIVE && wasConnected == null) {
            System.currentTimeMillis()
        } else wasConnected

        val resolvedDisplayName = existing.displayName.takeIf {
            it.isNotBlank() && it != remoteHandle && it != "Unknown"
        } ?: displayName

        val availableRoutes = if (existing.availableAudioRoutes.size > 2) {
            existing.availableAudioRoutes
        } else {
            rtpAudioEngine.getAvailableAudioRoutes()
        }

        _activeCall.value = existing.copy(
            callId = existing.callId.ifBlank { UUID.randomUUID().toString() },
            remoteHandle = remoteHandle,
            displayName = resolvedDisplayName,
            remotePhotoUri = existing.remotePhotoUri,
            sourceType = sourceType,
            routeBadge = routeBadge,
            lifecycle = lifecycle,
            isIncoming = isIncoming,
            isOnHold = lifecycle == CallLifecycleState.ON_HOLD,
            availableAudioRoutes = availableRoutes,
            telemetry = existing.telemetry.copy(
                codecName = "Cellular HD",
                transportLabel = matchedSim?.shortLabel ?: sourceType.label
            ),
            connectedAtEpochMs = connectedAt
        )

        if (nonBlockingSims.isEmpty()) {
            scope.launch(Dispatchers.IO) {
                val sims = simDetector.detectActiveSims()
                val detected = sims.firstOrNull { it.phoneAccountHandleId == accountHandleId } ?: sims.firstOrNull()
                if (detected != null) {
                    val detectedSource = if (detected.slotIndex == 1) CallSourceType.SIM_2 else CallSourceType.SIM_1
                    _activeCall.update { cur ->
                        if (cur.lifecycle == CallLifecycleState.IDLE) cur else cur.copy(
                            sourceType = detectedSource,
                            routeBadge = detected.fullLabel,
                            telemetry = cur.telemetry.copy(transportLabel = detected.shortLabel)
                        )
                    }
                    if (_activeCall.value.lifecycle == CallLifecycleState.RINGING_INCOMING) {
                        notificationManager.showIncomingCallNotification(
                            callerName = _activeCall.value.displayName,
                            callerHandle = _activeCall.value.remoteHandle,
                            lineBadge = detected.fullLabel,
                            callerPhotoUri = _activeCall.value.remotePhotoUri,
                            isCellularTelecom = true
                        )
                    }
                }
            }
        }

        if (existing.remotePhotoUri == null || existing.displayName == remoteHandle || existing.remoteHandle != remoteHandle) {
            resolveContactInfoAsync(remoteHandle)
        }

        if (lifecycle == CallLifecycleState.RINGING_INCOMING) {
            scope.launch(Dispatchers.IO) {
                notificationManager.showIncomingCallNotification(
                    callerName = resolvedDisplayName,
                    callerHandle = remoteHandle,
                    lineBadge = routeBadge,
                    callerPhotoUri = existing.remotePhotoUri,
                    isCellularTelecom = true
                )
            }
        } else {
            notificationManager.cancelIncomingNotification()
        }

        if (lifecycle == CallLifecycleState.ACTIVE) {
            if (timerJob == null) {
                startTimersAndTelemetry()
            }
            scope.launch {
                val prefs = prefsRepository.settingsFlow.first()
                VoipCallForegroundService.startOrUpdate(
                    context = context,
                    title = _activeCall.value.displayName.ifBlank { remoteHandle },
                    subtitle = routeBadge,
                    isRecording = prefs.autoCallRecording || _activeCall.value.isRecording
                )
                if (prefs.autoCallRecording) {
                    triggerAutoRecordingIfNeeded()
                }
            }
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

    private suspend fun finalizeAndClearCall(
        reason: String,
        holdStatusMs: Long = 200L
    ) = finalizeMutex.withLock {
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

        rtpAudioEngine.stopLocalRingback()
        rtpAudioEngine.stopMediaSession()
        proximityWakeLockManager.release()
        notificationManager.cancelIncomingNotification()
        VoipCallForegroundService.stop(context)

        clearAllVoipConnections()

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

        _activeCall.value = finished.copy(
            lifecycle = CallLifecycleState.ENDED,
            isRecording = false,
            statusMessage = reason
        )
        if (holdStatusMs > 0L) {
            delay(holdStatusMs)
        }
        _activeCall.value = ActiveCallState()
        _isCallUiMinimized.value = false
        _elapsedSeconds.value = 0
        autoRecordingTriggeredForCallId = null
        pendingSipFallbackQueue.clear()
        activeSipFallbackAccount = null
    }

    data class SipFailureOutcome(
        val userMessage: String,
        val isRetryableFormatAmbiguity: Boolean,
        val showErrorBanner: Boolean
    )

    companion object {
        // Strictly retry alternative BD/E.164 format variants ONLY on number/address format ambiguity (404/484).
        // Never retry when callee is busy (486), declines (603), or does not answer (480/408).
        private val RETRYABLE_PRE_RING_CODES = setOf(404, 484)
        private val voipConnections = ConcurrentHashMap<String, WeakReference<VoipConnectionService.OmniVoipConnection>>()
        private var nativeInCallServiceRef: WeakReference<OmniCallInCallService>? = null
        private var nativeInCallRef: WeakReference<Call>? = null

        fun classifySipFailure(
            statusCode: Int,
            rawReason: String,
            beforeEarlyRinging: Boolean
        ): SipFailureOutcome = when (statusCode) {
            480, 408 -> SipFailureOutcome(
                userMessage = "No answer",
                isRetryableFormatAmbiguity = false,
                showErrorBanner = false
            )
            486, 600 -> SipFailureOutcome(
                userMessage = "User busy",
                isRetryableFormatAmbiguity = false,
                showErrorBanner = false
            )
            603 -> SipFailureOutcome(
                userMessage = "Call declined",
                isRetryableFormatAmbiguity = false,
                showErrorBanner = false
            )
            487 -> SipFailureOutcome(
                userMessage = "Call ended",
                isRetryableFormatAmbiguity = false,
                showErrorBanner = false
            )
            in RETRYABLE_PRE_RING_CODES -> SipFailureOutcome(
                userMessage = rawReason,
                isRetryableFormatAmbiguity = beforeEarlyRinging,
                showErrorBanner = true
            )
            else -> SipFailureOutcome(
                userMessage = rawReason,
                isRetryableFormatAmbiguity = false,
                showErrorBanner = true
            )
        }

        fun registerVoipConnection(callId: String, connection: VoipConnectionService.OmniVoipConnection) {
            if (callId.isNotBlank()) {
                voipConnections[callId] = WeakReference(connection)
            }
        }

        fun unregisterVoipConnection(callId: String) {
            voipConnections.remove(callId)
        }

        fun clearAllVoipConnections() {
            voipConnections.values.forEach { ref ->
                ref.get()?.apply {
                    runCatching { setDisconnected(DisconnectCause(DisconnectCause.LOCAL)) }
                    runCatching { destroy() }
                }
            }
            voipConnections.clear()
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
