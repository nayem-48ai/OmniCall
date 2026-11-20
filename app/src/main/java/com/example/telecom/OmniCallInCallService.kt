package com.example.telecom

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.OmniCallApplication
import com.example.domain.model.AudioRouteType
import com.example.domain.model.CallLifecycleState
import com.example.ui.call.IncomingCallActivity

/**
 * Native [InCallService] used when OmniCall is selected as the device's Default Dialer (`ROLE_DIALER`).
 * Surfaces cellular GSM/LTE/5G calls into OmniCall's unified [ActiveCallCoordinator] immediately and
 * manages Telecom [CallAudioState] / [CallEndpoint] audio routing.
 */
class OmniCallInCallService : InCallService() {

    private var currentCall: Call? = null
    private var availableEndpoints: List<CallEndpoint> = emptyList()
    private var lastDisconnectCode: Int? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            syncCallState(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            syncCallState(call, getCallStateCompat(call))
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val state = getCallStateCompat(call)
        val handle = call.details.handle?.schemeSpecificPart.orEmpty()
        val app = applicationContext as? OmniCallApplication

        // Zero-latency in-memory block check on main thread (deep screening runs in OmniCallScreeningService)
        if (state == Call.STATE_RINGING && handle.isNotBlank() &&
            app?.container?.blocklistRepository?.isBlockedFast(handle) == true
        ) {
            runCatching { call.reject(false, null) }
            runCatching { call.disconnect() }
            return
        }

        currentCall = call
        lastDisconnectCode = null
        call.registerCallback(callCallback)
        ActiveCallCoordinator.bindNativeInCallService(this, call)

        if (state == Call.STATE_RINGING) {
            val display = call.details.callerDisplayName ?: handle
            val accountHandleId = call.details.accountHandle?.id
            // 1. Populate lightweight RINGING state synchronously with zero blocking IPC
            coordinator()?.primeFastIncomingSimCall(
                remoteHandle = handle.ifBlank { "Unknown" },
                displayName = display.ifBlank { handle.ifBlank { "Unknown" } },
                accountHandleId = accountHandleId
            )
            // 2. Immediately present full-screen Incoming Call UI while BAL privilege is active
            runCatching {
                startActivity(
                    Intent(this, IncomingCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    }
                )
            }
            // 3. Enrich SIM carrier slot, contacts avatar, and FullScreenIntent heads-up notification
            syncCallState(call, state)
        } else {
            syncCallState(call, state)
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    }
                )
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val disconnectCode = call.details.disconnectCause?.code ?: lastDisconnectCode
        call.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
        }
        lastDisconnectCode = null
        coordinator()?.onNativeSimCallEnded(disconnectCode)
        ActiveCallCoordinator.unbindNativeInCallService()
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        super.onCallEndpointChanged(callEndpoint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val route = mapEndpointType(callEndpoint.endpointType)
            coordinator()?.onSystemAudioRouteChanged(route, mapEndpointsToRoutes(availableEndpoints))
        }
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            this.availableEndpoints = availableEndpoints.toList()
            val mapped = mapEndpointsToRoutes(this.availableEndpoints)
            if (mapped.isNotEmpty()) {
                coordinator()?.onAvailableAudioRoutesChanged(mapped)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        if (audioState == null) return
        val route = when (audioState.route) {
            CallAudioState.ROUTE_SPEAKER -> AudioRouteType.SPEAKER
            CallAudioState.ROUTE_BLUETOOTH -> AudioRouteType.BLUETOOTH
            CallAudioState.ROUTE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
            else -> AudioRouteType.EARPIECE
        }
        val supported = buildList {
            add(AudioRouteType.EARPIECE)
            add(AudioRouteType.SPEAKER)
            if ((audioState.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0) {
                add(AudioRouteType.BLUETOOTH)
            }
            if ((audioState.supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
                add(AudioRouteType.WIRED_HEADSET)
            }
        }
        coordinator()?.onSystemAudioRouteChanged(route, supported)
    }

    @Suppress("DEPRECATION")
    private fun getCallStateCompat(call: Call): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            call.state
        }
    }

    private fun syncCallState(call: Call, state: Int) {
        val handle = call.details.handle?.schemeSpecificPart ?: "Unknown"
        val display = call.details.callerDisplayName ?: handle
        val accountHandleId = call.details.accountHandle?.id
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            call.details.callDirection == Call.Details.DIRECTION_INCOMING
        } else {
            state == Call.STATE_RINGING
        }
        val lifecycle = when (state) {
            Call.STATE_DIALING, Call.STATE_CONNECTING -> CallLifecycleState.DIALING
            Call.STATE_RINGING -> CallLifecycleState.RINGING_INCOMING
            Call.STATE_ACTIVE -> CallLifecycleState.ACTIVE
            Call.STATE_HOLDING -> CallLifecycleState.ON_HOLD
            Call.STATE_DISCONNECTED -> {
                lastDisconnectCode = call.details.disconnectCause?.code ?: lastDisconnectCode
                CallLifecycleState.ENDED
            }
            else -> CallLifecycleState.DIALING
        }
        coordinator()?.onNativeSimCallUpdated(
            remoteHandle = handle,
            displayName = display,
            accountHandleId = accountHandleId,
            lifecycle = lifecycle,
            isIncoming = isIncoming
        )
    }

    fun applyAudioRoute(route: AudioRouteType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && availableEndpoints.isNotEmpty()) {
            val targetType = when (route) {
                AudioRouteType.SPEAKER -> CallEndpoint.TYPE_SPEAKER
                AudioRouteType.BLUETOOTH -> CallEndpoint.TYPE_BLUETOOTH
                AudioRouteType.WIRED_HEADSET -> CallEndpoint.TYPE_WIRED_HEADSET
                AudioRouteType.EARPIECE -> CallEndpoint.TYPE_EARPIECE
            }
            val match = availableEndpoints.firstOrNull { it.endpointType == targetType }
            if (match != null) {
                runCatching {
                    requestCallEndpointChange(match, ContextCompat.getMainExecutor(this)) {}
                }
            }
        }
        @Suppress("DEPRECATION")
        val telecomRoute = when (route) {
            AudioRouteType.SPEAKER -> CallAudioState.ROUTE_SPEAKER
            AudioRouteType.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
            AudioRouteType.WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
            AudioRouteType.EARPIECE -> CallAudioState.ROUTE_EARPIECE
        }
        @Suppress("DEPRECATION")
        runCatching { setAudioRoute(telecomRoute) }
    }

    private fun mapEndpointType(type: Int): AudioRouteType = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && type == CallEndpoint.TYPE_SPEAKER -> AudioRouteType.SPEAKER
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && type == CallEndpoint.TYPE_BLUETOOTH -> AudioRouteType.BLUETOOTH
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && type == CallEndpoint.TYPE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
        else -> AudioRouteType.EARPIECE
    }

    private fun mapEndpointsToRoutes(endpoints: List<CallEndpoint>): List<AudioRouteType> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return emptyList()
        return buildList {
            add(AudioRouteType.EARPIECE)
            add(AudioRouteType.SPEAKER)
            if (endpoints.any { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }) {
                add(AudioRouteType.BLUETOOTH)
            }
            if (endpoints.any { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET }) {
                add(AudioRouteType.WIRED_HEADSET)
            }
        }
    }

    private fun coordinator(): ActiveCallCoordinator? {
        return (applicationContext as? OmniCallApplication)?.container?.activeCallCoordinator
    }
}
