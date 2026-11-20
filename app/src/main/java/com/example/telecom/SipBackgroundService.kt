package com.example.telecom

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.OmniCallApplication

/**
 * Persistent background Foreground Service keeping OmniCall's SIP registration, NAT keep-alive,
 * and UDP/TCP/TLS incoming INVITE socket active when the app is minimized or the screen is off,
 * matching Zoiper & PortSIP UC background call reception reliability.
 */
class SipBackgroundService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as OmniCallApplication
        val notifManager = app.container.callNotificationManager

        when (intent?.action) {
            ACTION_STOP -> {
                releaseWifiLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            CallNotificationManager.ACTION_RECONNECT_SIP -> {
                val sipEngine = app.container.sipClientEngine
                sipEngine.reconnectActiveAccountIfNeeded()
            }
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "OmniCall SIP Active"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Listening for incoming VoIP calls"
        val notification = notifManager.buildSipBackgroundNotification(title, subtitle)

        acquireWifiLock()
        startForegroundCompat(notification)
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWifiLock()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val started = runCatching {
                startForeground(
                    CallNotificationManager.NOTIF_ID_SIP_STATUS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            }.isSuccess
            if (started) return
        }
        runCatching {
            startForeground(CallNotificationManager.NOTIF_ID_SIP_STATUS, notification)
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        runCatching {
            wifiLock = wm.createWifiLock(mode, "OmniCall:SipBackgroundWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
        wifiLock = null
    }

    companion object {
        private const val ACTION_START_OR_UPDATE = "com.example.omnicall.SIP_BG_START"
        private const val ACTION_STOP = "com.example.omnicall.SIP_BG_STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"

        fun startOrUpdate(context: Context, title: String, subtitle: String) {
            val intent = Intent(context, SipBackgroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SipBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
