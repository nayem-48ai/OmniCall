package com.example.domain.ai

import com.example.domain.model.CallDirection
import com.example.domain.model.CallLifecycleState
import com.example.domain.model.CallSourceType
import com.example.domain.model.SipRegistrationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Future-Proof Extensibility (Phase 2 AI Agent Readiness)
 * -------------------------------------------------------
 * All telephony, SIP signaling, audio streaming, and recording subsystems publish
 * structured events onto [CallEventBus] and expose raw audio frames through [AudioStreamTap].
 *
 * In Phase 2, an AI Agent module can be plugged in without modifying core SIP/Telecom classes by:
 * 1. Collecting [CallEventBus.events] to intercept incoming calls or log conversation completions.
 * 2. Implementing [CallInterceptor] to decide whether an incoming call should ring normally or be
 *    auto-answered by the AI assistant.
 * 3. Subscribing to [AudioStreamTap.downlinkFrames] for real-time ASR transcription and calling
 *    [AudioStreamTap.injectUplinkAudio] to stream synthesized TTS audio directly into RTP.
 * 4. Writing structured call summaries and leads into [ConversationTranscriptSink].
 */
sealed interface OmniCallEvent {
    val timestampMs: Long

    data class IncomingCallDetected(
        val callId: String,
        val remoteHandle: String,
        val sourceType: CallSourceType,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : OmniCallEvent

    data class CallStateChanged(
        val callId: String,
        val remoteHandle: String,
        val sourceType: CallSourceType,
        val direction: CallDirection,
        val newState: CallLifecycleState,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : OmniCallEvent

    data class DtmfDigitReceived(
        val callId: String,
        val digit: Char,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : OmniCallEvent

    data class SipRegistrationChanged(
        val accountId: Long,
        val sipUri: String,
        val status: SipRegistrationStatus,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : OmniCallEvent

    data class RecordingCompleted(
        val callId: String,
        val filePath: String,
        val durationMs: Long,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : OmniCallEvent
}

interface CallEventBus {
    val events: SharedFlow<OmniCallEvent>
    fun publish(event: OmniCallEvent)
}

class DefaultCallEventBus : CallEventBus {
    private val _events = MutableSharedFlow<OmniCallEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    override val events: SharedFlow<OmniCallEvent> = _events.asSharedFlow()

    override fun publish(event: OmniCallEvent) {
        _events.tryEmit(event)
    }
}

enum class StreamDirection {
    UPLINK_MIC,
    DOWNLINK_REMOTE
}

data class AudioFrame(
    val pcm16: ShortArray,
    val sampleRateHz: Int,
    val direction: StreamDirection,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return sampleRateHz == other.sampleRateHz &&
            direction == other.direction &&
            timestampMs == other.timestampMs &&
            pcm16.contentEquals(other.pcm16)
    }

    override fun hashCode(): Int {
        var result = pcm16.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + direction.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/**
 * Exposes synchronized bidirectional PCM audio frames for recorders and Phase 2 AI Agents.
 */
interface AudioStreamTap {
    val uplinkFrames: Flow<AudioFrame>
    val downlinkFrames: Flow<AudioFrame>
    suspend fun injectUplinkAudio(pcm16: ShortArray, sampleRateHz: Int)
}

sealed interface InterceptDecision {
    data object RingNormally : InterceptDecision
    data class AutoAnswerByAgent(val greetingPrompt: String) : InterceptDecision
    data class RejectWithReason(val reason: String) : InterceptDecision
}

/**
 * Phase 2 hook allowing an AI Agent or spam shield to intercept incoming calls before ringing.
 */
interface CallInterceptor {
    suspend fun evaluateIncomingCall(
        callId: String,
        remoteHandle: String,
        sourceType: CallSourceType
    ): InterceptDecision
}

data class ConversationTurn(
    val speaker: String,
    val text: String,
    val timestampMs: Long
)

data class ConversationSummary(
    val callId: String,
    val remoteHandle: String,
    val summaryText: String,
    val extractedLeadNotes: String?,
    val turns: List<ConversationTurn>
)

/**
 * Phase 2 sink for persisting AI-generated live transcriptions and lead summaries.
 */
interface ConversationTranscriptSink {
    suspend fun onTranscriptTurn(callId: String, turn: ConversationTurn)
    suspend fun onConversationSummarized(summary: ConversationSummary)
}
