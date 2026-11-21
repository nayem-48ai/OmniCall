package com.example.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

data class RingerPolicy(
    val playRingtone: Boolean,
    val vibrate: Boolean
)

/**
 * Plays audio-profile-aware ringtone & vibration for incoming VoIP/SIP calls
 * (while native Cellular SIM calls delegate directly to Android Telecom's system ringer).
 * Respects Silent, Vibrate Only, Ringtone Only, and Ringtone + Vibration modes.
 */
class IncomingRingerController(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var isRinging = false

    private val vibrator: Vibrator?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun startRinging() {
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        val vibrateWhenRinging = runCatching {
            Settings.System.getInt(context.contentResolver, "vibrate_when_ringing", 1) == 1
        }.getOrDefault(true)
        val policy = resolveRingerPolicy(ringerMode, vibrateWhenRinging)

        synchronized(lock) {
            stopRingingLocked()
            isRinging = true

            if (policy.playRingtone) {
                startRingtonePlayerLocked()
            }
            if (policy.vibrate) {
                startVibrationLocked()
            }
        }
    }

    fun stopRinging() {
        synchronized(lock) {
            stopRingingLocked()
        }
    }

    private fun startRingtonePlayerLocked() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        runCatching {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer = mp
        }.onFailure {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    @Suppress("MissingPermission")
    private fun startVibrationLocked() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val pattern = longArrayOf(0L, 900L, 650L)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = android.os.VibrationAttributes.Builder()
                        .setUsage(android.os.VibrationAttributes.USAGE_RINGTONE)
                        .build()
                    vib.vibrate(effect, attrs)
                } else {
                    @Suppress("DEPRECATION")
                    val audioAttrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    @Suppress("DEPRECATION")
                    vib.vibrate(effect, audioAttrs)
                }
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(pattern, 0)
            }
        }
    }

    private fun stopRingingLocked() {
        isRinging = false
        runCatching {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        mediaPlayer = null
        runCatching { vibrator?.cancel() }
    }

    companion object {
        fun resolveRingerPolicy(
            ringerMode: Int,
            vibrateWhenRinging: Boolean
        ): RingerPolicy = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerPolicy(playRingtone = false, vibrate = false)
            AudioManager.RINGER_MODE_VIBRATE -> RingerPolicy(playRingtone = false, vibrate = true)
            else -> RingerPolicy(playRingtone = true, vibrate = vibrateWhenRinging)
        }
    }
}
