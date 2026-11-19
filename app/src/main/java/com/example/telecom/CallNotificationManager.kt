package com.example.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import com.example.ui.call.IncomingCallActivity

class CallNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_INCOMING = "omnicall_incoming_calls_v2"
        const val CHANNEL_INCOMING_CELLULAR = "omnicall_incoming_cellular_v1"
        const val CHANNEL_ONGOING = "omnicall_ongoing_calls"
        const val NOTIF_ID_INCOMING = 4101
        const val NOTIF_ID_ONGOING = 4102

        const val ACTION_ANSWER_CALL = "com.example.omnicall.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.example.omnicall.ACTION_DECLINE_CALL"
        const val ACTION_HANGUP_CALL = "com.example.omnicall.ACTION_HANGUP_CALL"

        fun extractShortLineLabel(rawLineBadge: String): String {
            val trimmed = rawLineBadge.trim()
            return when {
                trimmed.contains("SIM 2", ignoreCase = true) -> "SIM 2"
                trimmed.contains("SIM 1", ignoreCase = true) -> "SIM 1"
                trimmed.contains("SIP", ignoreCase = true) -> {
                    val beforeBullet = trimmed.substringBefore(" • ").trim()
                    if (beforeBullet.isBlank()) "SIP" else beforeBullet
                }
                trimmed.contains(" • ") -> trimmed.substringBefore(" • ").trim()
                else -> trimmed
            }
        }

        fun formatIncomingSubtitle(
            lineBadge: String,
            callerHandle: String,
            displayTitle: String
        ): String {
            val shortLine = extractShortLineLabel(lineBadge)
            val cleanHandle = callerHandle.trim()
            val showNumber = cleanHandle.isNotBlank() &&
                cleanHandle != displayTitle.trim() &&
                !cleanHandle.equals("Unknown", ignoreCase = true)
            return when {
                shortLine.isNotBlank() && showNumber -> "Incoming Via $shortLine, $cleanHandle"
                shortLine.isNotBlank() -> "Incoming Via $shortLine"
                showNumber -> "Incoming, $cleanHandle"
                else -> "Incoming call"
            }
        }
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
                description = "Full-screen and heads-up incoming SIP call alerts"
                setSound(
                    ringtoneUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 800L, 600L, 800L)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Cellular SIM calls already play native ringtone & vibration via Android Telecom.
            // Using a silent IMPORTANCE_HIGH channel triggers instant heads-up + FullScreenIntent
            // without double-ringing or audio-focus delay.
            val cellularChannel = NotificationChannel(
                CHANNEL_INCOMING_CELLULAR,
                "Incoming Cellular Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen and heads-up incoming SIM call display"
                setSound(null, null)
                enableVibration(false)
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
            notificationManager.createNotificationChannel(cellularChannel)
            notificationManager.createNotificationChannel(ongoingChannel)
        }
    }

    fun showIncomingCallNotification(
        callerName: String,
        callerHandle: String,
        lineBadge: String,
        callerPhotoUri: String? = null,
        isCellularTelecom: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannels()

        val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_USER_ACTION
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

        val displayTitle = callerName.ifBlank { callerHandle.ifBlank { "Incoming Call" } }
        val avatarBitmap = loadCircularCallerAvatar(callerPhotoUri)

        val personBuilder = Person.Builder()
            .setName(displayTitle)
            .setImportant(true)
        if (avatarBitmap != null) {
            personBuilder.setIcon(IconCompat.createWithBitmap(avatarBitmap))
        }
        val callerPerson = personBuilder.build()

        val callStyle = NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePi, answerPi)
            .setAnswerButtonColorHint(0xFF1E8E3E.toInt())
            .setDeclineButtonColorHint(0xFFD93025.toInt())

        val subtitle = formatIncomingSubtitle(
            lineBadge = lineBadge,
            callerHandle = callerHandle,
            displayTitle = displayTitle
        )
        val channelId = if (isCellularTelecom) CHANNEL_INCOMING_CELLULAR else CHANNEL_INCOMING

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(displayTitle)
            .setContentText(subtitle)
            .setColor(0xFF1A73E8.toInt())
            .setColorized(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setStyle(callStyle)

        if (isCellularTelecom) {
            builder.setSilent(true)
        }
        if (avatarBitmap != null) {
            builder.setLargeIcon(avatarBitmap)
        }

        val notification = builder.build().apply {
            if (!isCellularTelecom) {
                flags = flags or Notification.FLAG_INSISTENT
            }
        }

        notificationManager.notify(NOTIF_ID_INCOMING, notification)
    }

    private fun loadCircularCallerAvatar(photoUri: String?): Bitmap? {
        if (photoUri.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(photoUri)
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return@runCatching null
            val targetSize = 192
            val scaled = Bitmap.createScaledBitmap(decoded, targetSize, targetSize, true)
            if (scaled != decoded) decoded.recycle()
            val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val rect = Rect(0, 0, targetSize, targetSize)
            canvas.drawARGB(0, 0, 0, 0)
            canvas.drawCircle(targetSize / 2f, targetSize / 2f, targetSize / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(scaled, rect, rect, paint)
            if (scaled != output) scaled.recycle()
            output
        }.getOrNull()
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
            .setColor(0xFF1A73E8.toInt())
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.in_call_end), hangupPi)
            .build()
    }

    fun cancelIncomingNotification() {
        notificationManager.cancel(NOTIF_ID_INCOMING)
    }
}
