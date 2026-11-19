package com.example.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

enum class PhoneInfoLaunchTarget {
    RADIO_INFO,
    MOBILE_NETWORK_SETTINGS,
    WIRELESS_SETTINGS,
    UNAVAILABLE
}

data class PhoneInfoLaunchResult(
    val target: PhoneInfoLaunchTarget,
    val statusMessage: String? = null,
    val showDialerCodeHint: Boolean = false
)

/**
 * Launches Android's hidden Phone Info (RadioInfo / LTE-5G band & preferred network menu).
 * If blocked by device OEM security policies on newer Android versions, falls back cleanly
 * to Mobile Network Settings and provides a *#*#4636#*#* dialer helper hint.
 */
object PhoneInfoLauncher {

    const val TESTING_DIALER_CODE = "*#*#4636#*#*"

    private val RADIO_INFO_COMPONENTS = listOf(
        ComponentName("com.android.phone", "com.android.phone.settings.RadioInfo"),
        ComponentName("com.android.settings", "com.android.settings.RadioInfo"),
        ComponentName("com.android.settings", "com.android.settings.Settings\$TestingSettingsActivity"),
        ComponentName("com.android.phone", "com.android.phone.RadioInfo")
    )

    fun launch(context: Context): PhoneInfoLaunchResult {
        for (component in RADIO_INFO_COMPONENTS) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setComponent(component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) {
                return PhoneInfoLaunchResult(target = PhoneInfoLaunchTarget.RADIO_INFO)
            }
        }

        val networkIntent = Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(networkIntent); true }.getOrDefault(false)) {
            return PhoneInfoLaunchResult(
                target = PhoneInfoLaunchTarget.MOBILE_NETWORK_SETTINGS,
                statusMessage = "Opened Mobile Network Settings (this device restricts direct Phone Info — use $TESTING_DIALER_CODE in Keypad if needed).",
                showDialerCodeHint = true
            )
        }

        val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(wirelessIntent); true }.getOrDefault(false)) {
            return PhoneInfoLaunchResult(
                target = PhoneInfoLaunchTarget.WIRELESS_SETTINGS,
                statusMessage = "Opened Wireless Settings (dial $TESTING_DIALER_CODE if Phone Info is hidden by OEM).",
                showDialerCodeHint = true
            )
        }

        return PhoneInfoLaunchResult(
            target = PhoneInfoLaunchTarget.UNAVAILABLE,
            statusMessage = "Phone Info is restricted on this firmware. Dial $TESTING_DIALER_CODE from the Keypad.",
            showDialerCodeHint = true
        )
    }
}
