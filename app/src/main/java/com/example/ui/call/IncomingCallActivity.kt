package com.example.ui.call

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
 * or direct [android.telecom.InCallService] launch when a call arrives.
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

            BackHandler {
                coordinator.minimizeCallUi()
                finish()
            }

            OmniCallTheme(
                themePreference = settings.themePreference,
                colorPalette = settings.colorPalette,
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
                    },
                    onMinimize = {
                        coordinator.minimizeCallUi()
                        finish()
                    }
                )
            }
        }
    }
}
