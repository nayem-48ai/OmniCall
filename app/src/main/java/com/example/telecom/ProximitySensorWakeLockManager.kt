package com.example.telecom

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import com.example.domain.model.AudioRouteType
import com.example.domain.model.CallLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

/**
 * Manages [PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK] + [Sensor.TYPE_PROXIMITY] during calls.
 *
 * Matches Google Phone / WhatsApp / Messenger / Zoiper behavior:
 * - When call is Dialing / Ringing Outgoing / Active / On Hold AND audio route is [AudioRouteType.EARPIECE],
 *   acquires Android's proximity screen-off WakeLock so holding the device near the ear turns off
 *   the screen cleanly without locking the device (preventing cheek/ear mistouches).
 * - Automatically releases (with [PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY]) when switching
 *   to Speaker / Bluetooth / Wired Headset or when the call ends.
 */
class ProximitySensorWakeLockManager(context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val isProximityWakeLockSupported: Boolean =
        powerManager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true

    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorRegistered = false

    private val _isNearEar = MutableStateFlow(false)
    val isNearEar: StateFlow<Boolean> = _isNearEar.asStateFlow()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val threshold = min(maxRange, 5f)
            val dist = event.values.firstOrNull() ?: maxRange
            _isNearEar.value = dist < threshold
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @Synchronized
    fun updateForCallState(lifecycle: CallLifecycleState, audioRoute: AudioRouteType) {
        val inOngoingVoiceState = lifecycle == CallLifecycleState.DIALING ||
            lifecycle == CallLifecycleState.RINGING_OUTGOING ||
            lifecycle == CallLifecycleState.ACTIVE ||
            lifecycle == CallLifecycleState.ON_HOLD

        val shouldEnableProximity = inOngoingVoiceState && audioRoute == AudioRouteType.EARPIECE
        if (shouldEnableProximity) {
            acquire()
        } else {
            release()
        }
    }

    @Synchronized
    fun acquire() {
        if (isProximityWakeLockSupported) {
            if (wakeLock == null) {
                wakeLock = runCatching {
                    powerManager?.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "OmniCall:ProximityCallLock"
                    )?.apply { setReferenceCounted(false) }
                }.getOrNull()
            }
            runCatching {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(4 * 60 * 60 * 1000L) // safety timeout 4h max
                }
            }
        }

        if (!sensorRegistered && proximitySensor != null) {
            runCatching {
                sensorManager?.registerListener(
                    sensorListener,
                    proximitySensor,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                sensorRegistered = true
            }
        }
    }

    @Synchronized
    fun release() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }
        if (sensorRegistered) {
            runCatching { sensorManager?.unregisterListener(sensorListener) }
            sensorRegistered = false
        }
        _isNearEar.value = false
    }
}
