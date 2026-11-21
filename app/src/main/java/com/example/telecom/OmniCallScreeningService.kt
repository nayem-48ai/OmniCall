package com.example.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import com.example.OmniCallApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Native Android [CallScreeningService] that blocks incoming calls matching OmniCall's Blocklist
 * cleanly without ringing or interrupting the user (like Phone by Google), while guaranteeing
 * non-blocking instant screening response even on cold background launch.
 */
class OmniCallScreeningService : CallScreeningService() {

    private val screeningScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        if (repo == null || handleNumber.isBlank()) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        if (repo.hasFastCacheReady()) {
            val isBlocked = repo.isBlockedFast(handleNumber)
            respondWithBlockDecision(callDetails, isBlocked)
            return
        }

        screeningScope.launch {
            val isBlocked = withTimeoutOrNull(120L) {
                runCatching { repo.isBlocked(handleNumber) }.getOrDefault(false)
            } ?: false
            respondWithBlockDecision(callDetails, isBlocked)
        }
    }

    private fun respondWithBlockDecision(callDetails: Call.Details, isBlocked: Boolean) {
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
