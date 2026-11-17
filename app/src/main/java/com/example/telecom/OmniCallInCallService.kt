package com.example.telecom

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.example.MainActivity
import com.example.OmniCallApplication
import com.example.domain.model.AudioRouteType
import com.example.domain.model.CallLifecycleState
import com.example.ui.call.IncomingCallActivity

/**
 * Native [InCallService] used when OmniCall is selected as the device's Default Dialer (`ROLE_DIALER`).
 * Surfaces cellular GSM/LTE/5G calls into OmniCall's unified [ActiveCallCoordinator] and brings
 * OmniCall's own in-call UI to the foreground so Google Phone UI is not used.
 */
class OmniCallInCallService : InCallService() {

    private var currentCall: Call? = null

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
        currentCall = call
        call.registerCallback(callCallback)
        ActiveCallCoordinator.bindNativeInCallService(this, call)

        val state = getCallStateCompat(call)
        syncCallState(call, state)

        // Bring OmniCall's own InCallScreen UI to foreground immediately
        val targetActivity = if (state == Call.STATE_RINGING) {
            IncomingCallActivity::class.java
        } else {
            MainActivity::class.java
        }
        runCatching {
            startActivity(
                Intent(this, targetActivity).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                }
            )
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
        }
        coordinator()?.onNativeSimCallEnded()
        ActiveCallCoordinator.unbindNativeInCallService()
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        val route = when (audioState?.route) {
            CallAudioState.ROUTE_SPEAKER -> AudioRouteType.SPEAKER
            CallAudioState.ROUTE_BLUETOOTH -> AudioRouteType.BLUETOOTH
            CallAudioState.ROUTE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
            else -> AudioRouteType.EARPIECE
        }
        coordinator()?.onSystemAudioRouteChanged(route)
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
            Call.STATE_DISCONNECTED -> CallLifecycleState.ENDED
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

    @Suppress("DEPRECATION")
    fun applyAudioRoute(route: AudioRouteType) {
        val telecomRoute = when (route) {
            AudioRouteType.SPEAKER -> CallAudioState.ROUTE_SPEAKER
            AudioRouteType.BLUETOOTH -> CallAudioState.ROUTE_BLUETOOTH
            AudioRouteType.WIRED_HEADSET -> CallAudioState.ROUTE_WIRED_HEADSET
            AudioRouteType.EARPIECE -> CallAudioState.ROUTE_EARPIECE
        }
        setAudioRoute(telecomRoute)
    }

    private fun coordinator(): ActiveCallCoordinator? {
        return (applicationContext as? OmniCallApplication)?.container?.activeCallCoordinator
    }
}
