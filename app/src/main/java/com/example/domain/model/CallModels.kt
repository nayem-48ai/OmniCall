package com.example.domain.model

/**
 * Supported SIP signaling transport protocols discovered automatically
 * by [com.example.sip.SipAutoConfigEngine].
 */
enum class SipTransport(val label: String, val defaultPort: Int) {
    UDP("UDP", 5060),
    TCP("TCP", 5060),
    TLS("TLS", 5061)
}

enum class SipRegistrationStatus(val label: String) {
    UNREGISTERED("Offline"),
    DISCOVERING("Auto-Discovering"),
    REGISTERING("Registering"),
    REGISTERED("Registered"),
    ERROR("Auth / Network Error")
}

/**
 * Represents an active cellular SIM slot detected on the device via SubscriptionManager.
 */
data class SimSlotInfo(
    val slotIndex: Int,
    val subscriptionId: Int,
    val displayName: String,
    val carrierName: String,
    val phoneNumber: String,
    val phoneAccountHandleId: String? = null
) {
    val shortLabel: String get() = "SIM ${slotIndex + 1}"
    val fullLabel: String
        get() = if (carrierName.isNotBlank()) "SIM ${slotIndex + 1} • $carrierName" else shortLabel
}

/**
 * Dynamic outgoing routing preferences.
 * When Dual SIM + SIP are active, all 4 modes are presented to the user.
 * When fewer lines exist, OmniCall dynamically filters available options.
 */
enum class OutgoingRoutingMode(val title: String) {
    SIM_1("SIM 1"),
    SIM_2("SIM 2"),
    SIP("SIP VoIP"),
    ASK_EVERY_TIME("Ask every time")
}

sealed interface ResolvedOutgoingRoute {
    data class CellularSim(val sim: SimSlotInfo) : ResolvedOutgoingRoute
    data class SipAccount(val account: SipAccountModel) : ResolvedOutgoingRoute
}

data class AvailableCallingEnvironment(
    val simSlots: List<SimSlotInfo> = emptyList(),
    val sipAccounts: List<SipAccountModel> = emptyList(),
    val selectedMode: OutgoingRoutingMode = OutgoingRoutingMode.ASK_EVERY_TIME,
    val isDefaultDialer: Boolean = false
) {
    val hasDualSim: Boolean get() = simSlots.size >= 2
    val hasAnySim: Boolean get() = simSlots.isNotEmpty()
    val hasConfiguredSip: Boolean get() = sipAccounts.any { it.isEnabled }

    /**
     * Computes the exact list of 1..4 routing modes applicable to the current hardware + SIP state.
     */
    fun availableModes(): List<OutgoingRoutingMode> {
        val modes = mutableListOf<OutgoingRoutingMode>()
        if (simSlots.isNotEmpty()) modes.add(OutgoingRoutingMode.SIM_1)
        if (simSlots.size >= 2) modes.add(OutgoingRoutingMode.SIM_2)
        if (hasConfiguredSip) modes.add(OutgoingRoutingMode.SIP)
        if (modes.size > 1) modes.add(OutgoingRoutingMode.ASK_EVERY_TIME)
        return modes
    }

    fun effectiveSelectedMode(): OutgoingRoutingMode {
        val valid = availableModes()
        return when {
            valid.isEmpty() -> OutgoingRoutingMode.ASK_EVERY_TIME
            selectedMode in valid -> selectedMode
            valid.contains(OutgoingRoutingMode.ASK_EVERY_TIME) -> OutgoingRoutingMode.ASK_EVERY_TIME
            else -> valid.first()
        }
    }
}

enum class SipBalancePreset(val label: String, val defaultUrl: String) {
    NONE("Disabled", ""),
    ICC_COMMUNICATION("ICC Communication", "https://icctalk.com:2020/OTTSSO/login"),
    CUSTOM("Custom URL", "")
}

data class SipAccountModel(
    val id: Long = 0L,
    val displayName: String,
    val domain: String,
    val username: String,
    val port: Int,
    val transport: SipTransport,
    val autoDiscovered: Boolean = true,
    val preferredCodecs: List<String> = listOf("G722", "PCMU", "PCMA"),
    val isEnabled: Boolean = true,
    val isDefault: Boolean = true,
    val status: SipRegistrationStatus = SipRegistrationStatus.UNREGISTERED,
    val lastError: String? = null,
    val lastRegisteredAtMs: Long? = null,
    val balancePreset: SipBalancePreset = SipBalancePreset.NONE,
    val balanceCheckUrl: String = "",
    val showBalanceInKeypad: Boolean = false,
    val lastBalance: String? = null,
    val lastBalanceError: String? = null,
    val lastBalanceUpdatedMs: Long? = null
) {
    val sipUri: String get() = "sip:$username@$domain"

    fun resolvedBalanceUrl(): String = when (balancePreset) {
        SipBalancePreset.NONE -> ""
        SipBalancePreset.ICC_COMMUNICATION -> SipBalancePreset.ICC_COMMUNICATION.defaultUrl
        SipBalancePreset.CUSTOM -> balanceCheckUrl.trim()
    }
}

enum class CallDirection {
    OUTGOING,
    INCOMING,
    MISSED
}

enum class CallSourceType(val label: String) {
    SIM_1("SIM 1"),
    SIM_2("SIM 2"),
    SIP("SIP VoIP")
}

enum class AudioRouteType(val label: String) {
    EARPIECE("Earpiece"),
    SPEAKER("Speaker"),
    BLUETOOTH("Bluetooth"),
    WIRED_HEADSET("Headset")
}

enum class RecordingFormat(val label: String, val extension: String) {
    AAC_M4A("AAC (M4A)", "m4a"),
    WAV("PCM (WAV)", "wav")
}

enum class RecordingType(
    val label: String,
    val shortLabel: String,
    val description: String
) {
    INTERNAL_AND_EXTERNAL(
        label = "Internal and external Voice",
        shortLabel = "Both Sides",
        description = "Records both the other side's conversation and your microphone"
    ),
    ONLY_INTERNAL(
        label = "Only Internal Voice",
        shortLabel = "Internal Only",
        description = "Records only the remote party's voice (the other side of the call)"
    ),
    ONLY_EXTERNAL(
        label = "Only External Voice",
        shortLabel = "External Only",
        description = "Records only your device microphone voice"
    )
}

enum class RecordingEngine(val label: String, val shortLabel: String, val description: String) {
    AUTO(
        label = "Auto (Smart Fallback)",
        shortLabel = "Auto",
        description = "Verifies live signal across AudioRecord sources and auto-switches to MediaRecorder (MIC) if silenced"
    ),
    AUDIO_RECORD(
        label = "AudioRecord (HD WAV/AAC)",
        shortLabel = "AudioRecord",
        description = "Direct PCM capture with hardware voice gain"
    ),
    MEDIA_RECORDER_MIC(
        label = "MediaRecorder (MIC Mode)",
        shortLabel = "MediaRecorder MIC",
        description = "Standard Android MediaRecorder using AudioSource.MIC for maximum device compatibility"
    )
}

data class BlockedNumberItem(
    val id: Long = 0L,
    val rawNumber: String,
    val normalizedKey: String,
    val displayName: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class CallLifecycleState {
    IDLE,
    DIALING,
    RINGING_INCOMING,
    RINGING_OUTGOING,
    ACTIVE,
    ON_HOLD,
    ENDED
}

data class AudioQualityTelemetry(
    val codecName: String = "HD Wideband",
    val sampleRateHz: Int = 16000,
    val transportLabel: String = "Cellular",
    val aecActive: Boolean = false,
    val nsActive: Boolean = false,
    val agcActive: Boolean = false,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f
)

data class ActiveCallState(
    val callId: String = "",
    val remoteHandle: String = "",
    val displayName: String = "",
    val remotePhotoUri: String? = null,
    val sourceType: CallSourceType = CallSourceType.SIM_1,
    val routeBadge: String = "",
    val lifecycle: CallLifecycleState = CallLifecycleState.IDLE,
    val isIncoming: Boolean = false,
    val isMuted: Boolean = false,
    val isOnHold: Boolean = false,
    val audioRoute: AudioRouteType = AudioRouteType.EARPIECE,
    val availableAudioRoutes: List<AudioRouteType> = listOf(AudioRouteType.EARPIECE, AudioRouteType.SPEAKER),
    val isRecording: Boolean = false,
    val recordingDurationSec: Int = 0,
    val connectedAtEpochMs: Long? = null,
    val telemetry: AudioQualityTelemetry = AudioQualityTelemetry(),
    val statusMessage: String? = null
)
