package com.example.telecom

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.OmniCallApplication

/**
 * Foreground Service declared with `phoneCall|microphone` types to keep active SIP VoIP calls
 * and manual dual-stream call recording uninterrupted when the screen locks or app backgrounds.
 */
class VoipCallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as OmniCallApplication
        val coordinator = app.container.activeCallCoordinator
        val notifManager = app.container.callNotificationManager

        when (intent?.action) {
            CallNotificationManager.ACTION_ANSWER_CALL -> {
                coordinator.answerIncomingCall()
            }
            CallNotificationManager.ACTION_DECLINE_CALL -> {
                coordinator.declineIncomingCall()
                stopSelf()
                return START_NOT_STICKY
            }
            CallNotificationManager.ACTION_HANGUP_CALL -> {
                coordinator.endActiveCall()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "OmniCall"
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Active call"
                val isRec = intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false
                val notification = notifManager.buildOngoingCallNotification(title, subtitle, isRec)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val fgsTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    }
                    startForeground(CallNotificationManager.NOTIF_ID_ONGOING, notification, fgsTypes)
                } else {
                    startForeground(CallNotificationManager.NOTIF_ID_ONGOING, notification)
                }
            }
        }
        return START_STICKY
    }

    companion object {
        private const val ACTION_START_OR_UPDATE = "com.example.omnicall.START_FGS"
        private const val ACTION_STOP_SERVICE = "com.example.omnicall.STOP_FGS"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_RECORDING = "extra_recording"

        fun startOrUpdate(context: Context, title: String, subtitle: String, isRecording: Boolean) {
            val intent = Intent(context, VoipCallForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_RECORDING, isRecording)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoipCallForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            runCatching { context.startService(intent) }
        }
    }
}
