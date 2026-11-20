package com.example.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.example.OmniCallApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Native Android [CallScreeningService] that blocks incoming calls matching OmniCall's Blocklist
 * cleanly without ringing or interrupting the user (like Phone by Google), while guaranteeing
 * sub-150ms screening response so non-blocked calls ring instantly even on cold background launch.
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
        val repo = app?.container?.blocklistRepository

        val isBlocked = when {
            repo == null || handleNumber.isBlank() -> false
            repo.isBlockedFast(handleNumber) -> true
            repo.hasFastCacheReady() -> false
            else -> runBlocking {
                withTimeoutOrNull(80L) {
                    withContext(Dispatchers.IO) {
                        runCatching { repo.isBlocked(handleNumber) }.getOrDefault(false)
                    }
                } ?: false
            }
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
