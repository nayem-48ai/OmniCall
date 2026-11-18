package com.example.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.example.OmniCallApplication
import kotlinx.coroutines.runBlocking

/**
 * Native Android [CallScreeningService] that blocks incoming calls matching OmniCall's Blocklist
 * cleanly without ringing or interrupting the user (like Phone by Google).
 */
class OmniCallScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        } else {
            true
        }

        if (!isIncoming) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        val handleNumber = callDetails.handle?.schemeSpecificPart.orEmpty()
        val app = applicationContext as? OmniCallApplication
        val isBlocked = if (app != null && handleNumber.isNotBlank()) {
            runBlocking {
                runCatching { app.container.blocklistRepository.isBlocked(handleNumber) }
                    .getOrDefault(false)
            }
        } else {
            false
        }

        val response = if (isBlocked) {
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSilenceCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(true)
                .build()
        } else {
            CallResponse.Builder().build()
        }

        respondToCall(callDetails, response)
    }
}
