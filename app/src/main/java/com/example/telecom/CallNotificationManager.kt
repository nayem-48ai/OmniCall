package com.example.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ui.call.IncomingCallActivity

class CallNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_INCOMING = "omnicall_incoming_calls"
        const val CHANNEL_ONGOING = "omnicall_ongoing_calls"
        const val NOTIF_ID_INCOMING = 4101
        const val NOTIF_ID_ONGOING = 4102

        const val ACTION_ANSWER_CALL = "com.example.omnicall.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.example.omnicall.ACTION_DECLINE_CALL"
        const val ACTION_HANGUP_CALL = "com.example.omnicall.ACTION_HANGUP_CALL"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING,
                context.getString(R.string.notification_channel_incoming_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen incoming cellular and SIP call alerts"
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val ongoingChannel = NotificationChannel(
                CHANNEL_ONGOING,
                context.getString(R.string.notification_channel_ongoing_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing VoIP calls and active call recording indicator"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(incomingChannel)
            notificationManager.createNotificationChannel(ongoingChannel)
        }
    }

    fun showIncomingCallNotification(callerName: String, callerHandle: String, lineBadge: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels()

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val fullScreenPi = PendingIntent.getActivity(
            context,
            100,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerPi = PendingIntent.getService(
            context,
            101,
            Intent(context, VoipCallForegroundService::class.java).setAction(ACTION_ANSWER_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePi = PendingIntent.getService(
            context,
            102,
            Intent(context, VoipCallForegroundService::class.java).setAction(ACTION_DECLINE_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName.ifBlank { callerHandle })
            .setContentText("Incoming $lineBadge call • $callerHandle")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .addAction(android.R.drawable.ic_menu_call, context.getString(R.string.incoming_answer), answerPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.incoming_decline), declinePi)
            .build()

        notificationManager.notify(NOTIF_ID_INCOMING, notification)
    }

    fun buildOngoingCallNotification(
        callerName: String,
        subtitle: String,
        isRecording: Boolean
    ): Notification {
        ensureChannels()
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            103,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangupPi = PendingIntent.getService(
            context,
            104,
            Intent(context, VoipCallForegroundService::class.java).setAction(ACTION_HANGUP_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val recPrefix = if (isRecording) "● REC • " else ""
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(callerName)
            .setContentText("$recPrefix$subtitle")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.in_call_end), hangupPi)
            .build()
    }

    fun cancelIncomingNotification() {
        notificationManager.cancel(NOTIF_ID_INCOMING)
    }
}
