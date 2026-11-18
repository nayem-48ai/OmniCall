package com.example.telecom

import android.os.Build
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import com.example.OmniCallApplication
import com.example.domain.model.AudioRouteType

/**
 * Self-Managed Telecom [ConnectionService] for OmniCall SIP/VoIP sessions.
 * Allows Android Telecom to arbitrate audio focus and handle GSM-vs-VoIP interruptions cleanly.
 */
class VoipConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callId = extras?.getString(TelecomCallManager.EXTRA_OMNICALL_CALL_ID) ?: ""
        return OmniVoipConnection(callId, isIncoming = false).apply {
            setAddress(request?.address, android.telecom.TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras
        val callId = extras?.getString(TelecomCallManager.EXTRA_OMNICALL_CALL_ID) ?: ""
        val displayName = extras?.getString(TelecomCallManager.EXTRA_CALLER_DISPLAY_NAME).orEmpty()
        return OmniVoipConnection(callId, isIncoming = true).apply {
            setAddress(request?.address, android.telecom.TelecomManager.PRESENTATION_ALLOWED)
            if (displayName.isNotBlank()) {
                setCallerDisplayName(displayName, android.telecom.TelecomManager.PRESENTATION_ALLOWED)
            }
            setRinging()
        }
    }

    inner class OmniVoipConnection(
        private val callId: String,
        private val isIncoming: Boolean
    ) : Connection() {

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connectionProperties = PROPERTY_SELF_MANAGED
            }
            connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
            audioModeIsVoip = true
            ActiveCallCoordinator.registerVoipConnection(callId, this)
        }

        override fun onAnswer() {
            setActive()
            coordinator()?.answerIncomingCall()
        }

        override fun onReject() {
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            coordinator()?.declineIncomingCall()
        }

        override fun onDisconnect() {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            coordinator()?.endActiveCall()
        }

        override fun onAbort() {
            setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
            destroy()
            coordinator()?.endActiveCall()
        }

        override fun onHold() {
            setOnHold()
            coordinator()?.setHold(true)
        }

        override fun onUnhold() {
            setActive()
            coordinator()?.setHold(false)
        }

        override fun onPlayDtmfTone(c: Char) {
            coordinator()?.sendDtmf(c)
        }

        @Suppress("DEPRECATION")
        override fun onCallAudioStateChanged(state: CallAudioState?) {
            if (state == null) return
            val route = when (state.route) {
                CallAudioState.ROUTE_SPEAKER -> AudioRouteType.SPEAKER
                CallAudioState.ROUTE_BLUETOOTH -> AudioRouteType.BLUETOOTH
                CallAudioState.ROUTE_WIRED_HEADSET -> AudioRouteType.WIRED_HEADSET
                else -> AudioRouteType.EARPIECE
            }
            val supported = buildList {
                add(AudioRouteType.EARPIECE)
                add(AudioRouteType.SPEAKER)
                if ((state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0) {
                    add(AudioRouteType.BLUETOOTH)
                }
                if ((state.supportedRouteMask and CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
                    add(AudioRouteType.WIRED_HEADSET)
                }
            }
            coordinator()?.onSystemAudioRouteChanged(route, supported)
        }

        private fun coordinator(): ActiveCallCoordinator? {
            return (applicationContext as? OmniCallApplication)?.container?.activeCallCoordinator
        }
    }
}
