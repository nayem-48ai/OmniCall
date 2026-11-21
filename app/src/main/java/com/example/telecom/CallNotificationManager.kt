package com.example.telecom

import android.Manifest
import android.app.KeyguardManager
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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import com.example.data.repository.BlocklistRepository
import com.example.ui.call.IncomingCallActivity

data class MissedCallRecord(
    val callerName: String,
    val callerHandle: String,
    val lineBadge: String,
    val callerPhotoUri: String?,
    val timestampMs: Long,
    val isSip: Boolean
)

class CallNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_INCOMING = "omnicall_incoming_calls_v3"
        const val CHANNEL_INCOMING_CELLULAR = "omnicall_incoming_cellular_v2"
        const val CHANNEL_ONGOING = "omnicall_ongoing_calls"
        const val CHANNEL_SIP_STATUS = "omnicall_sip_background_status"
        const val CHANNEL_MISSED = "omnicall_missed_calls_v1"

        const val NOTIF_ID_INCOMING = 4101
        const val NOTIF_ID_ONGOING = 4102
        const val NOTIF_ID_SIP_STATUS = 4103
        const val NOTIF_ID_MISSED = 4104

        const val ACTION_ANSWER_CALL = "com.example.omnicall.ACTION_ANSWER_CALL"
        const val ACTION_DECLINE_CALL = "com.example.omnicall.ACTION_DECLINE_CALL"
        const val ACTION_HANGUP_CALL = "com.example.omnicall.ACTION_HANGUP_CALL"
        const val ACTION_RECONNECT_SIP = "com.example.omnicall.ACTION_RECONNECT_SIP"
        const val ACTION_STOP_SIP_BACKGROUND = "com.example.omnicall.ACTION_STOP_SIP_BACKGROUND"
        const val ACTION_DISMISS_MISSED = "com.example.omnicall.ACTION_DISMISS_MISSED"

        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val EXTRA_CALLBACK_NUMBER = "extra_callback_number"
        const val EXTRA_CALLBACK_IS_SIP = "extra_callback_is_sip"
        const val EXTRA_EXPAND_ACTIVE_CALL = "extra_expand_active_call"
        const val TAB_RECENTS = "recents"

        fun shouldLaunchFullScreenIncomingUi(
            isScreenInteractive: Boolean,
            isKeyguardLocked: Boolean,
            hasPostNotificationsPermission: Boolean,
            notificationsEnabled: Boolean,
            channelBlocked: Boolean
        ): Boolean {
            return !isScreenInteractive ||
                isKeyguardLocked ||
                !hasPostNotificationsPermission ||
                !notificationsEnabled ||
                channelBlocked
        }

        fun shouldLaunchFullScreenIncomingUi(
            context: Context,
            isCellularTelecom: Boolean = false
        ): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val isScreenInteractive = pm?.isInteractive ?: true
            val isKeyguardLocked = km?.isKeyguardLocked ?: false
            val hasNotifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            val notificationsEnabled = runCatching {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }.getOrDefault(true)
            val channelId = if (isCellularTelecom) CHANNEL_INCOMING_CELLULAR else CHANNEL_INCOMING
            val channelBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                val ch = nm.getNotificationChannel(channelId)
                ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE
            } else {
                false
            }
            return shouldLaunchFullScreenIncomingUi(
                isScreenInteractive = isScreenInteractive,
                isKeyguardLocked = isKeyguardLocked,
                hasPostNotificationsPermission = hasNotifPerm,
                notificationsEnabled = notificationsEnabled,
                channelBlocked = channelBlocked
            )
        }

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

        fun shouldDeduplicateMissedCall(
            lastHandle: String?,
            lastTimeMs: Long,
            newHandle: String?,
            newTimeMs: Long,
            windowMs: Long = 5_000L
        ): Boolean {
            if (lastTimeMs <= 0L || (newTimeMs - lastTimeMs) !in 0L..windowMs) return false
            val a = lastHandle.orEmpty().trim()
            val b = newHandle.orEmpty().trim()
            if (a.isBlank() || b.isBlank()) return a == b
            val keyA = BlocklistRepository.normalizeKey(a)
            val keyB = BlocklistRepository.normalizeKey(b)
            return keyA.isNotEmpty() && keyA == keyB
        }

        fun formatMissedCallTitle(count: Int): String =
            if (count <= 1) "Missed call" else "$count missed calls"

        fun formatMissedCallText(
            entries: List<MissedCallRecord>,
            totalCount: Int,
            fallbackNumber: String? = null
        ): String {
            val latest = entries.firstOrNull()
            if (latest == null) {
                return fallbackNumber?.takeIf { it.isNotBlank() } ?: "Tap to view call history"
            }
            val latestLabel = latest.callerName.ifBlank { latest.callerHandle }.ifBlank { "Unknown" }
            val lineLabel = extractShortLineLabel(latest.lineBadge)
            return if (totalCount <= 1) {
                val showNumber = latest.callerHandle.isNotBlank() &&
                    latest.callerHandle != latestLabel &&
                    !latest.callerHandle.equals("Unknown", ignoreCase = true)
                buildString {
                    append(latestLabel)
                    if (showNumber) append(" (${latest.callerHandle})")
                    if (lineLabel.isNotBlank()) append(" • $lineLabel")
                }
            } else {
                val distinctNames = entries
                    .map { it.callerName.ifBlank { it.callerHandle } }
                    .filter { it.isNotBlank() }
                    .distinct()
                when {
                    distinctNames.size >= 2 -> {
                        val extra = (totalCount - 2).coerceAtLeast(0)
                        if (extra > 0) "${distinctNames[0]}, ${distinctNames[1]} (+$extra more)"
                        else "${distinctNames[0]}, ${distinctNames[1]}"
                    }
                    else -> {
                        if (lineLabel.isNotBlank()) "$latestLabel ($totalCount) • $lineLabel"
                        else "$latestLabel ($totalCount)"
                    }
                }
            }
        }
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Volatile
    private var lastIncomingSignature: String? = null

    private val missedLock = Any()
    private val missedRecords = mutableListOf<MissedCallRecord>()
    private var missedNotificationCount = 0
    private var lastRecordedMissedHandle: String? = null
    private var lastRecordedMissedTimeMs: Long = 0L

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Clean up legacy channel IDs if present so updated high-priority heads-up rules apply immediately
            runCatching {
                notificationManager.deleteNotificationChannel("omnicall_incoming_calls_v2")
                notificationManager.deleteNotificationChannel("omnicall_incoming_cellular_v1")
            }

            // SIP ringtone & vibration are played by IncomingRingerController respecting device audio profile,
            // while high-priority channel ensures CallStyle heads-up mini pop-up always peeks cleanly.
            val incomingChannel = NotificationChannel(
                CHANNEL_INCOMING,
                context.getString(R.string.notification_channel_incoming_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen and heads-up incoming SIP call alerts"
                setSound(null, null)
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 25L)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            // Cellular SIM calls play native ringtone & vibration via Android Telecom.
            // High-priority alerting channel ensures instant floating CallStyle heads-up mini pop-up
            // over active apps without double-ringing.
            val cellularChannel = NotificationChannel(
                CHANNEL_INCOMING_CELLULAR,
                "Incoming Cellular Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen and heads-up incoming SIM call display"
                setSound(null, null)
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 25L)
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

            val sipStatusChannel = NotificationChannel(
                CHANNEL_SIP_STATUS,
                "SIP Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SIP VoIP registration active in background for incoming calls"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }

            val missedChannel = NotificationChannel(
                CHANNEL_MISSED,
                context.getString(R.string.notification_channel_missed_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Missed cellular SIM and SIP VoIP call alerts"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(incomingChannel)
            notificationManager.createNotificationChannel(cellularChannel)
            notificationManager.createNotificationChannel(ongoingChannel)
            notificationManager.createNotificationChannel(sipStatusChannel)
            notificationManager.createNotificationChannel(missedChannel)
        }
    }

    fun showIncomingCallNotification(
        callerName: String,
        callerHandle: String,
        lineBadge: String,
        callerPhotoUri: String? = null,
        isCellularTelecom: Boolean = false
    ) {
        if (!hasNotificationPermission()) return
        ensureChannels()

        val displayTitle = callerName.ifBlank { callerHandle.ifBlank { "Incoming Call" } }
        val signature = "$displayTitle|$callerHandle|$lineBadge|${callerPhotoUri.orEmpty()}|$isCellularTelecom"
        val isUpdate = lastIncomingSignature != null
        if (signature == lastIncomingSignature) return
        lastIncomingSignature = signature

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

        val answerPi = PendingIntent.getBroadcast(
            context,
            101,
            Intent(context, CallNotificationActionReceiver::class.java).setAction(ACTION_ANSWER_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePi = PendingIntent.getBroadcast(
            context,
            102,
            Intent(context, CallNotificationActionReceiver::class.java).setAction(ACTION_DECLINE_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
            .setOnlyAlertOnce(isUpdate)
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .addPerson(callerPerson)
            .setStyle(callStyle)

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

    fun recordAndShowMissedCall(
        callerName: String,
        callerHandle: String,
        lineBadge: String,
        callerPhotoUri: String? = null,
        isSip: Boolean
    ) {
        val now = System.currentTimeMillis()
        val displayTitle = callerName.ifBlank { callerHandle.ifBlank { "Unknown" } }
        val record = MissedCallRecord(
            callerName = displayTitle,
            callerHandle = callerHandle,
            lineBadge = lineBadge,
            callerPhotoUri = callerPhotoUri,
            timestampMs = now,
            isSip = isSip
        )

        val (snapshot, count) = synchronized(missedLock) {
            val isDuplicate = shouldDeduplicateMissedCall(
                lastHandle = lastRecordedMissedHandle,
                lastTimeMs = lastRecordedMissedTimeMs,
                newHandle = callerHandle,
                newTimeMs = now
            )
            if (isDuplicate && missedRecords.isNotEmpty()) {
                missedRecords[0] = record
            } else {
                missedRecords.add(0, record)
                if (missedRecords.size > 20) missedRecords.removeAt(missedRecords.lastIndex)
                missedNotificationCount = (missedNotificationCount + 1).coerceAtLeast(1)
            }
            lastRecordedMissedHandle = callerHandle
            lastRecordedMissedTimeMs = now
            missedRecords.toList() to missedNotificationCount
        }

        postMissedCallNotification(
            entries = snapshot,
            totalCount = count,
            fallbackNumber = callerHandle
        )
    }

    fun onTelecomMissedCallBroadcast(
        count: Int,
        phoneNumber: String?,
        resolvedName: String?,
        resolvedPhotoUri: String?
    ) {
        if (count <= 0) {
            cancelMissedCallNotifications()
            return
        }
        val now = System.currentTimeMillis()
        val cleanHandle = phoneNumber.orEmpty().trim()
        val displayTitle = resolvedName?.takeIf { it.isNotBlank() }
            ?: cleanHandle.ifBlank { "Unknown" }

        val (snapshot, effectiveCount) = synchronized(missedLock) {
            val existingLatest = missedRecords.firstOrNull()
            val alreadyRecorded = existingLatest != null && !existingLatest.isSip &&
                shouldDeduplicateMissedCall(
                    lastHandle = lastRecordedMissedHandle,
                    lastTimeMs = lastRecordedMissedTimeMs,
                    newHandle = cleanHandle.ifBlank { existingLatest.callerHandle },
                    newTimeMs = now
                )
            if (alreadyRecorded && existingLatest != null) {
                missedRecords[0] = existingLatest.copy(
                    callerName = resolvedName?.takeIf { it.isNotBlank() } ?: existingLatest.callerName,
                    callerPhotoUri = resolvedPhotoUri ?: existingLatest.callerPhotoUri
                )
                if (count > missedNotificationCount) {
                    missedNotificationCount = count
                }
            } else {
                missedRecords.add(
                    0,
                    MissedCallRecord(
                        callerName = displayTitle,
                        callerHandle = cleanHandle,
                        lineBadge = "SIM",
                        callerPhotoUri = resolvedPhotoUri,
                        timestampMs = now,
                        isSip = false
                    )
                )
                if (missedRecords.size > 20) missedRecords.removeAt(missedRecords.lastIndex)
                missedNotificationCount = maxOf(count, missedNotificationCount + 1)
                lastRecordedMissedHandle = cleanHandle
                lastRecordedMissedTimeMs = now
            }
            missedRecords.toList() to missedNotificationCount
        }

        postMissedCallNotification(
            entries = snapshot,
            totalCount = effectiveCount,
            fallbackNumber = cleanHandle
        )
    }

    private fun postMissedCallNotification(
        entries: List<MissedCallRecord>,
        totalCount: Int,
        fallbackNumber: String?
    ) {
        if (!hasNotificationPermission()) return
        ensureChannels()

        val latest = entries.firstOrNull()
        val title = formatMissedCallTitle(totalCount)
        val body = formatMissedCallText(entries, totalCount, fallbackNumber)
        val latestHandle = latest?.callerHandle?.takeIf { it.isNotBlank() }
            ?: fallbackNumber.orEmpty().trim()
        val avatarBitmap = loadCircularCallerAvatar(latest?.callerPhotoUri)

        val openRecentsIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TAB, TAB_RECENTS)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            200,
            openRecentsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissPi = PendingIntent.getBroadcast(
            context,
            201,
            Intent(context, CallNotificationActionReceiver::class.java).setAction(ACTION_DISMISS_MISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setContentTitle(title)
            .setContentText(body)
            .setWhen(latest?.timestampMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setNumber(totalCount.coerceAtLeast(1))
            .setColor(0xFFD93025.toInt())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .setDeleteIntent(dismissPi)

        if (avatarBitmap != null) {
            builder.setLargeIcon(avatarBitmap)
        }

        if (totalCount > 1 && entries.size > 1) {
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
            entries.take(5).forEach { item ->
                val name = item.callerName.ifBlank { item.callerHandle }.ifBlank { "Unknown" }
                val line = extractShortLineLabel(item.lineBadge)
                inbox.addLine(if (line.isNotBlank()) "$name • $line" else name)
            }
            builder.setStyle(inbox)
        }

        val canCallBack = latestHandle.isNotBlank() && !latestHandle.equals("Unknown", ignoreCase = true)
        if (canCallBack) {
            val callbackIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALLBACK_NUMBER, latestHandle)
                putExtra(EXTRA_CALLBACK_IS_SIP, latest?.isSip == true)
            }
            val callbackPi = PendingIntent.getActivity(
                context,
                202,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.sym_action_call,
                context.getString(R.string.missed_call_callback),
                callbackPi
            )

            if (!latestHandle.contains("@") && latestHandle.any { it.isDigit() }) {
                val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(latestHandle)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val smsPi = PendingIntent.getActivity(
                    context,
                    203,
                    smsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.sym_action_chat,
                    context.getString(R.string.missed_call_message),
                    smsPi
                )
            }
        }

        notificationManager.notify(NOTIF_ID_MISSED, builder.build())
    }

    fun cancelMissedCallNotifications() {
        synchronized(missedLock) {
            missedRecords.clear()
            missedNotificationCount = 0
            lastRecordedMissedHandle = null
            lastRecordedMissedTimeMs = 0L
        }
        notificationManager.cancel(NOTIF_ID_MISSED)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
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
        val safeCallerName = callerName.ifBlank { "OmniCall" }
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_EXPAND_ACTIVE_CALL, true)
        }
        val openPi = PendingIntent.getActivity(
            context,
            103,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangupPi = PendingIntent.getBroadcast(
            context,
            104,
            Intent(context, CallNotificationActionReceiver::class.java).setAction(ACTION_HANGUP_CALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(safeCallerName)
            .setImportant(true)
            .build()

        val recPrefix = if (isRecording) "● REC • " else ""
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(safeCallerName)
            .setContentText("$recPrefix$subtitle")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setColor(0xFF1A73E8.toInt())
            .setContentIntent(openPi)
            .addPerson(callerPerson)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(callerPerson, hangupPi))
            .build()
    }

    fun buildSipBackgroundNotification(
        title: String,
        subtitle: String
    ): Notification {
        ensureChannels()
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            context,
            105,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val reconnectPi = PendingIntent.getService(
            context,
            106,
            Intent(context, SipBackgroundService::class.java).setAction(ACTION_RECONNECT_SIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            context,
            107,
            Intent(context, SipBackgroundService::class.java).setAction(ACTION_STOP_SIP_BACKGROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_SIP_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_rotate, "Refresh", reconnectPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    fun cancelIncomingNotification() {
        lastIncomingSignature = null
        notificationManager.cancel(NOTIF_ID_INCOMING)
    }
}
