package com.example.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.example.domain.model.SimSlotInfo

/**
 * Auto-detects active cellular SIM slots (Single SIM vs Dual SIM) using Android's
 * [SubscriptionManager] and matches them to Telecom [PhoneAccountHandle]s.
 */
class SimSubscriptionDetector(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun detectActiveSims(): List<SimSlotInfo> {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhoneState) {
            return emptyList()
        }

        val subscriptionManager =
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                ?: return emptyList()

        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

        val phoneAccounts: List<PhoneAccountHandle> = runCatching {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                telecomManager?.callCapablePhoneAccounts
                    ?.filter { it.componentName.packageName != context.packageName }
                    .orEmpty()
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val subscriptions = runCatching {
            subscriptionManager.activeSubscriptionInfoList.orEmpty()
        }.getOrDefault(emptyList())

        return subscriptions.sortedBy { it.simSlotIndex }.mapIndexed { idx, sub ->
            val matchedHandle = phoneAccounts.getOrNull(idx)?.id
            val phoneNum = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching { subscriptionManager.getPhoneNumber(sub.subscriptionId) }.getOrDefault("")
            } else {
                @Suppress("DEPRECATION")
                sub.number.orEmpty()
            }

            SimSlotInfo(
                slotIndex = sub.simSlotIndex.coerceAtLeast(0),
                subscriptionId = sub.subscriptionId,
                displayName = sub.displayName?.toString().orEmpty().ifBlank { "SIM ${idx + 1}" },
                carrierName = sub.carrierName?.toString().orEmpty(),
                phoneNumber = phoneNum,
                phoneAccountHandleId = matchedHandle
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun findPhoneAccountHandleForSim(sim: SimSlotInfo): PhoneAccountHandle? {
        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneState) return null

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
        val handles = runCatching {
            telecomManager.callCapablePhoneAccounts
                .filter { it.componentName.packageName != context.packageName }
        }.getOrDefault(emptyList())

        return handles.firstOrNull { it.id == sim.phoneAccountHandleId }
            ?: handles.getOrNull(sim.slotIndex)
    }
}
