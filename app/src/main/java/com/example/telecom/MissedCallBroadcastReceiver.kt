package com.example.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import com.example.OmniCallApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Claims Android Telecom's `ACTION_SHOW_MISSED_CALLS_NOTIFICATION` broadcast when OmniCall
 * is the Default Dialer (`ROLE_DIALER`), so OmniCall posts its own missed-call notification
 * instead of the stock system "Phone calls" fallback notification.
 */
class MissedCallBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val app = context.applicationContext as? OmniCallApplication ?: return
        val container = app.container

        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val phoneNumber = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)

        if (count <= 0) {
            container.callNotificationManager.cancelMissedCallNotifications()
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contactInfo = if (!phoneNumber.isNullOrBlank()) {
                    runCatching { container.contactsRepository.lookupContactInfo(phoneNumber) }.getOrNull()
                } else {
                    null
                }
                container.callNotificationManager.onTelecomMissedCallBroadcast(
                    count = count,
                    phoneNumber = phoneNumber,
                    resolvedName = contactInfo?.displayName,
                    resolvedPhotoUri = contactInfo?.photoUri
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
