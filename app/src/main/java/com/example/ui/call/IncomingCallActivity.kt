package com.example.ui.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.OmniCallApplication
import com.example.domain.model.CallLifecycleState
import com.example.ui.theme.OmniCallTheme

/**
 * Full-screen incoming/active call Activity triggered by [android.app.Notification.Builder.setFullScreenIntent]
 * when the device is locked or screen is off.
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OmniCallApplication
        val coordinator = app.container.activeCallCoordinator

        setContent {
            val settings by app.container.preferencesRepository.settingsFlow
                .collectAsStateWithLifecycle(initialValue = com.example.data.preferences.AppSettingsState())
            val callState by coordinator.activeCall.collectAsStateWithLifecycle()
            val elapsed by coordinator.elapsedSeconds.collectAsStateWithLifecycle()
            val isNearEar by coordinator.isNearEar.collectAsStateWithLifecycle()

            LaunchedEffect(callState.lifecycle) {
                if (callState.lifecycle == CallLifecycleState.IDLE ||
                    callState.lifecycle == CallLifecycleState.ENDED
                ) {
                    finish()
                }
            }

            OmniCallTheme(
                themePreference = settings.themePreference,
                dynamicColor = settings.dynamicColorEnabled
            ) {
                InCallScreen(
                    callState = callState,
                    elapsedSeconds = elapsed,
                    isNearEar = isNearEar,
                    onToggleMute = coordinator::toggleMute,
                    onToggleHold = coordinator::toggleHold,
                    onSelectAudioRoute = coordinator::selectAudioRoute,
                    onToggleRecord = coordinator::toggleManualRecording,
                    onSendDtmf = coordinator::sendDtmf,
                    onAnswer = coordinator::answerIncomingCall,
                    onDecline = {
                        coordinator.declineIncomingCall()
                        finish()
                    },
                    onEndCall = {
                        coordinator.endActiveCall()
                        finish()
                    }
                )
            }
        }
    }
}
