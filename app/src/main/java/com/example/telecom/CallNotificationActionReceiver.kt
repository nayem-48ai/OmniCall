package com.example.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.OmniCallApplication

/**
 * Handles CallStyle heads-up notification action buttons (Answer, Decline, Hang up)
 * and missed-call notification dismissal without triggering background service restrictions.
 */
class CallNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext as? OmniCallApplication ?: return
        val container = app.container

        when (action) {
            CallNotificationManager.ACTION_ANSWER_CALL -> {
                container.activeCallCoordinator.answerIncomingCall()
            }
            CallNotificationManager.ACTION_DECLINE_CALL -> {
                container.activeCallCoordinator.declineIncomingCall()
            }
            CallNotificationManager.ACTION_HANGUP_CALL -> {
                container.activeCallCoordinator.endActiveCall()
            }
            CallNotificationManager.ACTION_DISMISS_MISSED -> {
                container.callNotificationManager.cancelMissedCallNotifications()
                container.telecomCallManager.cancelMissedCallsNotification()
            }
        }
    }
}
