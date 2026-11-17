package com.example.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.example.domain.model.SimSlotInfo
import com.example.domain.model.SipAccountModel

/**
 * Deliverable #3: TelecomManager Wrapper for Native SIM Calls & Self-Managed SIP Bindings
 * --------------------------------------------------------------------------------------
 * - Registers OmniCall's self-managed [PhoneAccount] (`CAPABILITY_SELF_MANAGED`) for VoIP audio arbitration.
 * - Places native cellular calls on a specific SIM slot via `TelecomManager.placeCall(...)` with
 *   `EXTRA_PHONE_ACCOUNT_HANDLE` (gracefully falling back to `ACTION_CALL` or `ACTION_DIAL`).
 * - Queries and requests Android's Default Dialer role (`RoleManager.ROLE_DIALER`).
 */
class TelecomCallManager(
    private val context: Context,
    private val simDetector: SimSubscriptionDetector
) {
    private val telecomManager: TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    val voipAccountHandle: PhoneAccountHandle by lazy {
        PhoneAccountHandle(
            ComponentName(context, VoipConnectionService::class.java),
            "omnicall_sip_voip_account"
        )
    }

    fun registerSelfManagedSipAccount() {
        val tm = telecomManager ?: return
        val account = PhoneAccount.builder(voipAccountHandle, "OmniCall SIP VoIP")
            .setCapabilities(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PhoneAccount.CAPABILITY_SELF_MANAGED
                } else {
                    PhoneAccount.CAPABILITY_CALL_PROVIDER
                }
            )
            .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
            .setShortDescription("OmniCall VoIP / SIP Line")
            .build()

        runCatching { tm.registerPhoneAccount(account) }
    }

    fun isDefaultDialer(): Boolean {
        val tm = telecomManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true ||
                tm.defaultDialerPackage == context.packageName
        } else {
            tm.defaultDialerPackage == context.packageName
        }
    }

    fun createRequestDefaultDialerIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            } else null
        } else {
            @Suppress("DEPRECATION")
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun placeNativeSimCall(phoneNumber: String, simSlot: SimSlotInfo?): Boolean {
        val sanitized = phoneNumber.trim()
        if (sanitized.isBlank()) return false

        val telUri = Uri.fromParts("tel", sanitized, null)
        val hasCallPhone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val tm = telecomManager
        if (tm != null && hasCallPhone) {
            val extras = Bundle()
            val handle = simSlot?.let { simDetector.findPhoneAccountHandleForSim(it) }
            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            val placed = runCatching {
                tm.placeCall(telUri, extras)
                true
            }.getOrDefault(false)
            if (placed) return true
        }

        // Safe fallback to ACTION_CALL or ACTION_DIAL
        val intent = Intent(if (hasCallPhone) Intent.ACTION_CALL else Intent.ACTION_DIAL, telUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun notifyTelecomOutgoingSipCall(sipUri: String, account: SipAccountModel, callId: String) {
        val tm = telecomManager ?: return
        registerSelfManagedSipAccount()
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, voipAccountHandle)
            putBundle(
                TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,
                Bundle().apply {
                    putString(EXTRA_OMNICALL_CALL_ID, callId)
                    putString(EXTRA_SIP_ACCOUNT_LABEL, account.displayName)
                }
            )
        }
        runCatching { tm.placeCall(Uri.parse(sipUri), extras) }
    }

    fun notifyTelecomIncomingSipCall(callerUri: String, displayName: String, callId: String) {
        val tm = telecomManager ?: return
        registerSelfManagedSipAccount()
        val extras = Bundle().apply {
            putString(EXTRA_OMNICALL_CALL_ID, callId)
            putString(EXTRA_CALLER_DISPLAY_NAME, displayName)
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.parse("sip:$callerUri"))
        }
        runCatching { tm.addNewIncomingCall(voipAccountHandle, extras) }
    }

    companion object {
        const val EXTRA_OMNICALL_CALL_ID = "extra_omnicall_call_id"
        const val EXTRA_SIP_ACCOUNT_LABEL = "extra_sip_account_label"
        const val EXTRA_CALLER_DISPLAY_NAME = "extra_caller_display_name"
    }
}
