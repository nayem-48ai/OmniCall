package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.OutgoingRoutingMode
import com.example.domain.model.RecordingEngine
import com.example.domain.model.RecordingFormat
import com.example.domain.model.RecordingType
import com.example.ui.theme.AppColorPalette
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "omnicall_settings"
)

data class AppSettingsState(
    val outgoingRoutingMode: OutgoingRoutingMode = OutgoingRoutingMode.ASK_EVERY_TIME,
    val recordingFormat: RecordingFormat = RecordingFormat.AAC_M4A,
    val recordingType: RecordingType = RecordingType.INTERNAL_AND_EXTERNAL,
    val recordingEngine: RecordingEngine = RecordingEngine.AUTO,
    val autoCallRecording: Boolean = false,
    val autoRecallEnabled: Boolean = false,
    val autoRecallDelaySeconds: Int = 5,
    val receiveIncomingSipCalls: Boolean = true,
    val preferWidebandCodecs: Boolean = true,
    val enableHardwareAec: Boolean = true,
    val enableHardwareNs: Boolean = true,
    val enableHardwareAgc: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val colorPalette: AppColorPalette = AppColorPalette.SAPPHIRE,
    val dynamicColorEnabled: Boolean = false,
    val doodleThemeEnabled: Boolean = false
)

class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val ROUTING_MODE = stringPreferencesKey("outgoing_routing_mode")
        val RECORDING_FORMAT = stringPreferencesKey("recording_format")
        val RECORDING_TYPE = stringPreferencesKey("recording_type")
        val RECORDING_ENGINE = stringPreferencesKey("recording_engine")
        val AUTO_CALL_RECORDING = booleanPreferencesKey("auto_call_recording")
        val AUTO_RECALL_ENABLED = booleanPreferencesKey("auto_recall_enabled")
        val AUTO_RECALL_DELAY_SECONDS = intPreferencesKey("auto_recall_delay_seconds")
        val RECEIVE_INCOMING_SIP_CALLS = booleanPreferencesKey("receive_incoming_sip_calls")
        val WIDEBAND_CODECS = booleanPreferencesKey("prefer_wideband_codecs")
        val HARDWARE_AEC = booleanPreferencesKey("enable_hardware_aec")
        val HARDWARE_NS = booleanPreferencesKey("enable_hardware_ns")
        val HARDWARE_AGC = booleanPreferencesKey("enable_hardware_agc")
        val THEME_PREF = stringPreferencesKey("theme_preference")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val DOODLE_THEME = booleanPreferencesKey("doodle_theme_enabled")
    }

    val settingsFlow: Flow<AppSettingsState> = context.appPrefsDataStore.data.map { prefs ->
        AppSettingsState(
            outgoingRoutingMode = prefs[Keys.ROUTING_MODE]?.let {
                runCatching { OutgoingRoutingMode.valueOf(it) }.getOrNull()
            } ?: OutgoingRoutingMode.ASK_EVERY_TIME,
            recordingFormat = prefs[Keys.RECORDING_FORMAT]?.let {
                runCatching { RecordingFormat.valueOf(it) }.getOrNull()
            } ?: RecordingFormat.AAC_M4A,
            recordingType = prefs[Keys.RECORDING_TYPE]?.let {
                runCatching { RecordingType.valueOf(it) }.getOrNull()
            } ?: RecordingType.INTERNAL_AND_EXTERNAL,
            recordingEngine = prefs[Keys.RECORDING_ENGINE]?.let {
                runCatching { RecordingEngine.valueOf(it) }.getOrNull()
            } ?: RecordingEngine.AUTO,
            autoCallRecording = prefs[Keys.AUTO_CALL_RECORDING] ?: false,
            autoRecallEnabled = prefs[Keys.AUTO_RECALL_ENABLED] ?: false,
            autoRecallDelaySeconds = (prefs[Keys.AUTO_RECALL_DELAY_SECONDS] ?: 5).coerceIn(3, 300),
            receiveIncomingSipCalls = prefs[Keys.RECEIVE_INCOMING_SIP_CALLS] ?: true,
            preferWidebandCodecs = prefs[Keys.WIDEBAND_CODECS] ?: true,
            enableHardwareAec = prefs[Keys.HARDWARE_AEC] ?: true,
            enableHardwareNs = prefs[Keys.HARDWARE_NS] ?: true,
            enableHardwareAgc = prefs[Keys.HARDWARE_AGC] ?: true,
            themePreference = prefs[Keys.THEME_PREF]?.let {
                runCatching { ThemePreference.valueOf(it) }.getOrNull()
            } ?: ThemePreference.SYSTEM,
            colorPalette = prefs[Keys.COLOR_PALETTE]?.let {
                runCatching { AppColorPalette.valueOf(it) }.getOrNull()
            } ?: AppColorPalette.SAPPHIRE,
            dynamicColorEnabled = prefs[Keys.DYNAMIC_COLOR] ?: false,
            doodleThemeEnabled = prefs[Keys.DOODLE_THEME] ?: false
        )
    }

    suspend fun setOutgoingRoutingMode(mode: OutgoingRoutingMode) {
        context.appPrefsDataStore.edit { it[Keys.ROUTING_MODE] = mode.name }
    }

    suspend fun setRecordingFormat(format: RecordingFormat) {
        context.appPrefsDataStore.edit { it[Keys.RECORDING_FORMAT] = format.name }
    }

    suspend fun setRecordingType(type: RecordingType) {
        context.appPrefsDataStore.edit { it[Keys.RECORDING_TYPE] = type.name }
    }

    suspend fun setRecordingEngine(engine: RecordingEngine) {
        context.appPrefsDataStore.edit { it[Keys.RECORDING_ENGINE] = engine.name }
    }

    suspend fun setAutoCallRecording(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.AUTO_CALL_RECORDING] = enabled }
    }

    suspend fun setAutoRecallEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.AUTO_RECALL_ENABLED] = enabled }
    }

    suspend fun setAutoRecallDelaySeconds(seconds: Int) {
        context.appPrefsDataStore.edit { it[Keys.AUTO_RECALL_DELAY_SECONDS] = seconds.coerceIn(3, 300) }
    }

    suspend fun setReceiveIncomingSipCalls(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.RECEIVE_INCOMING_SIP_CALLS] = enabled }
    }

    suspend fun setAudioProcessing(
        widebandCodecs: Boolean? = null,
        aec: Boolean? = null,
        ns: Boolean? = null,
        agc: Boolean? = null
    ) {
        context.appPrefsDataStore.edit { prefs ->
            widebandCodecs?.let { prefs[Keys.WIDEBAND_CODECS] = it }
            aec?.let { prefs[Keys.HARDWARE_AEC] = it }
            ns?.let { prefs[Keys.HARDWARE_NS] = it }
            agc?.let { prefs[Keys.HARDWARE_AGC] = it }
        }
    }

    suspend fun setThemePreference(theme: ThemePreference) {
        context.appPrefsDataStore.edit { it[Keys.THEME_PREF] = theme.name }
    }

    suspend fun setColorPalette(palette: AppColorPalette) {
        context.appPrefsDataStore.edit {
            it[Keys.COLOR_PALETTE] = palette.name
            it[Keys.DYNAMIC_COLOR] = false
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setDoodleThemeEnabled(enabled: Boolean) {
        context.appPrefsDataStore.edit { it[Keys.DOODLE_THEME] = enabled }
    }
}
