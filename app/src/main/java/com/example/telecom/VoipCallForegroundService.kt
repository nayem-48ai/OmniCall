package com.example.telecom

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.OmniCallApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground Service declared with `phoneCall|microphone` types to keep active calls
 * and call recording uninterrupted when the screen turns off via proximity sensor or backgrounding.
 */
class VoipCallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as OmniCallApplication
        val coordinator = app.container.activeCallCoordinator
        val notifManager = app.container.callNotificationManager

        when (intent?.action) {
            CallNotificationManager.ACTION_ANSWER_CALL -> {
                if (!isForegroundRunning.value) {
                    val active = coordinator.activeCall.value
                    val title = active.displayName.ifBlank { active.remoteHandle }.ifBlank { "OmniCall" }
                    val subtitle = active.routeBadge.ifBlank { "Active call" }
                    val notif = notifManager.buildOngoingCallNotification(title, subtitle, active.isRecording)
                    val hasMicPerm = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    startForegroundSafely(notif, wantMicrophone = hasMicPerm)
                    isForegroundMicReady.value = hasMicPerm
                    isForegroundRunning.value = true
                }
                coordinator.answerIncomingCall()
            }
            CallNotificationManager.ACTION_DECLINE_CALL -> {
                coordinator.declineIncomingCall()
                markInactiveAndStop()
                return START_NOT_STICKY
            }
            CallNotificationManager.ACTION_HANGUP_CALL -> {
                coordinator.endActiveCall()
                markInactiveAndStop()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                markInactiveAndStop()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "OmniCall"
                val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Active call"
                val isRec = intent?.getBooleanExtra(EXTRA_RECORDING, false) ?: false
                val notification = notifManager.buildOngoingCallNotification(title, subtitle, isRec)
                val hasMicPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                startForegroundSafely(notification, wantMicrophone = hasMicPerm || isRec)
                isForegroundMicReady.value = hasMicPerm
                isForegroundRunning.value = true
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isForegroundRunning.value = false
        isForegroundMicReady.value = false
        super.onDestroy()
    }

    private fun markInactiveAndStop() {
        isForegroundRunning.value = false
        isForegroundMicReady.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundSafely(notification: android.app.Notification, wantMicrophone: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val attempts = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (wantMicrophone) {
                        add(
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                        add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    }
                    add(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } else {
                    add(ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                }
            }
            for (fgsType in attempts) {
                val ok = runCatching {
                    startForeground(CallNotificationManager.NOTIF_ID_ONGOING, notification, fgsType)
                }.isSuccess
                if (ok) return
            }
        }
        runCatching {
            startForeground(CallNotificationManager.NOTIF_ID_ONGOING, notification)
        }
    }

    companion object {
        private const val ACTION_START_OR_UPDATE = "com.example.omnicall.START_FGS"
        private const val ACTION_STOP_SERVICE = "com.example.omnicall.STOP_FGS"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"
        private const val EXTRA_RECORDING = "extra_recording"

        val isForegroundRunning = MutableStateFlow(false)
        val isForegroundMicReady = MutableStateFlow(false)

        fun startOrUpdate(context: Context, title: String, subtitle: String, isRecording: Boolean) {
            val intent = Intent(context, VoipCallForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_RECORDING, isRecording)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        suspend fun startAndAwaitReady(
            context: Context,
            title: String,
            subtitle: String,
            isRecording: Boolean
        ) {
            startOrUpdate(context, title, subtitle, isRecording)
            withTimeoutOrNull(700L) {
                isForegroundRunning.first { it }
            }
        }

        fun stop(context: Context) {
            isForegroundRunning.value = false
            isForegroundMicReady.value = false
            val intent = Intent(context, VoipCallForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            runCatching { context.startService(intent) }
        }
    }
}
