package com.example.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiCalling3
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.model.OutgoingRoutingMode
import com.example.domain.model.RecordingFormat
import com.example.domain.model.SipAccountModel
import com.example.domain.model.SipRegistrationStatus
import com.example.ui.theme.SignalConnected
import com.example.ui.theme.SignalWarning
import com.example.ui.theme.ThemePreference
import com.example.ui.viewmodel.SipFormState
import com.example.ui.viewmodel.SipSettingsUiState
import com.example.ui.viewmodel.SipSettingsViewModel

@Composable
fun SipSettingsScreen(
    uiState: SipSettingsUiState,
    viewModel: SipSettingsViewModel,
    onRequestDefaultDialer: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenRecordings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val env = uiState.environment
    val availableModes = env.availableModes()
    val effectiveMode = env.effectiveSelectedMode()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // Call Recordings Entry Card (moved into Settings)
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenRecordings)
                        .testTag("settings_open_recordings_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Call Recordings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${uiState.recordingsCount} saved • ${uiState.settings.recordingFormat.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open Call Recordings"
                        )
                    }
                }
            }

            // 1. Hardware SIM Detection & Default Dialer Card
            item {
                SettingsSectionCard(title = "Telephony & SIM Slots") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (uiState.isDefaultDialer) {
                                        "Default Phone App Active"
                                    } else {
                                        "Set as Default Phone App"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (uiState.isDefaultDialer) {
                                        "OmniCall handles native SIM & VoIP call UI"
                                    } else {
                                        "Enable native in-call screen for cellular SIM calls"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!uiState.isDefaultDialer) {
                            FilledTonalButton(
                                onClick = onRequestDefaultDialer,
                                modifier = Modifier.testTag("set_default_dialer_button")
                            ) {
                                Text("Enable")
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Active",
                                tint = SignalConnected
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (uiState.simSlots.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "No SIM slots detected (or Phone permission pending)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = onRequestPermissions,
                                modifier = Modifier.testTag("grant_telephony_permission_button")
                            ) {
                                Text("Detect")
                            }
                        }
                    } else {
                        uiState.simSlots.forEach { sim ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SimCard,
                                    contentDescription = sim.shortLabel,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = sim.fullLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // 2. Dynamic Outgoing Call Routing Modes (1..4 modes auto-detected)
            item {
                SettingsSectionCard(
                    title = "Make Calls With (${availableModes.size} mode${if (availableModes.size == 1) "" else "s"} detected)"
                ) {
                    if (availableModes.isEmpty()) {
                        Text(
                            text = "Add a SIP account or grant SIM permissions to choose your default outgoing line.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        availableModes.forEach { mode ->
                            val subtitle = when (mode) {
                                OutgoingRoutingMode.SIM_1 ->
                                    uiState.simSlots.getOrNull(0)?.carrierName?.ifBlank { "Cellular Slot 1" }
                                        ?: "Cellular Slot 1"
                                OutgoingRoutingMode.SIM_2 ->
                                    uiState.simSlots.getOrNull(1)?.carrierName?.ifBlank { "Cellular Slot 2" }
                                        ?: "Cellular Slot 2"
                                OutgoingRoutingMode.SIP ->
                                    uiState.accounts.firstOrNull { it.isEnabled }?.sipUri ?: "Configured VoIP line"
                                OutgoingRoutingMode.ASK_EVERY_TIME ->
                                    "Show line selector whenever placing a call"
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.setOutgoingRoutingMode(mode) }
                                    .padding(vertical = 6.dp)
                                    .testTag("routing_mode_option_${mode.name.lowercase()}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = effectiveMode == mode,
                                    onClick = { viewModel.setOutgoingRoutingMode(mode) }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. SIP Accounts & Smart Auto-Configuration
            item {
                SettingsSectionCard(
                    title = "SIP / VoIP Accounts",
                    trailingAction = {
                        FilledTonalButton(
                            onClick = { viewModel.openAddAccountSheet() },
                            modifier = Modifier.testTag("add_sip_account_button")
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add SIP")
                        }
                    }
                ) {
                    if (uiState.accounts.isEmpty()) {
                        Text(
                            text = "No SIP accounts yet. Add your domain, username, and password—OmniCall auto-detects DNS SRV, UDP, TCP, or TLS automatically.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            uiState.accounts.forEach { acc ->
                                SipAccountRow(
                                    account = acc,
                                    onReRegister = { viewModel.reRegisterAccount(acc) },
                                    onSetDefault = { viewModel.setDefaultSipAccount(acc.id) },
                                    onToggleEnabled = { viewModel.toggleAccountEnabled(acc, it) },
                                    onDelete = { viewModel.deleteAccount(acc.id) }
                                )
                            }
                        }
                    }
                }
            }

            // 4. Hi-Fi Audio DSP & Local Call Recording Format
            item {
                SettingsSectionCard(title = "Hi-Fi Audio & Recording") {
                    AudioToggleRow(
                        label = "Wideband Codecs (G.722 / HD)",
                        description = "Prioritize 16 kHz wideband SDP negotiation",
                        checked = uiState.settings.preferWidebandCodecs,
                        onCheckedChange = { viewModel.setAudioProcessing(wideband = it) },
                        testTag = "toggle_wideband_codecs"
                    )
                    AudioToggleRow(
                        label = "Hardware Echo Cancellation (AEC)",
                        description = "AcousticEchoCanceler on VOICE_COMMUNICATION",
                        checked = uiState.settings.enableHardwareAec,
                        onCheckedChange = { viewModel.setAudioProcessing(aec = it) },
                        testTag = "toggle_hardware_aec"
                    )
                    AudioToggleRow(
                        label = "Hardware Noise Suppression (NS)",
                        description = "Suppresses ambient background noise on uplink",
                        checked = uiState.settings.enableHardwareNs,
                        onCheckedChange = { viewModel.setAudioProcessing(ns = it) },
                        testTag = "toggle_hardware_ns"
                    )
                    AudioToggleRow(
                        label = "Automatic Gain Control (AGC)",
                        description = "Normalizes microphone voice loudness",
                        checked = uiState.settings.enableHardwareAgc,
                        onCheckedChange = { viewModel.setAudioProcessing(agc = it) },
                        testTag = "toggle_hardware_agc"
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Manual Call Recording Format",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        RecordingFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = uiState.settings.recordingFormat == fmt,
                                onClick = { viewModel.setRecordingFormat(fmt) },
                                label = { Text(fmt.label) },
                                modifier = Modifier.testTag("recording_format_${fmt.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            // 5. Theme & Appearance
            item {
                SettingsSectionCard(title = "Appearance") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemePreference.entries.forEach { pref ->
                            FilterChip(
                                selected = uiState.settings.themePreference == pref,
                                onClick = { viewModel.setThemePreference(pref) },
                                label = { Text(pref.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.testTag("theme_pref_${pref.name.lowercase()}")
                            )
                        }
                    }
                    AudioToggleRow(
                        label = "Material You Dynamic Color",
                        description = "Adapt palette to device wallpaper (Android 12+)",
                        checked = uiState.settings.dynamicColorEnabled,
                        onCheckedChange = { viewModel.setDynamicColor(it) },
                        testTag = "toggle_dynamic_color"
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (uiState.showAddAccountSheet) {
        SipAutoConfigBottomSheet(
            form = uiState.form,
            onUpdateForm = viewModel::updateForm,
            onStartAutoConfig = viewModel::runSmartAutoConfigureAndSave,
            onSaveOffline = viewModel::saveOfflineWithDefaults,
            onDismiss = viewModel::closeAccountSheet
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                trailingAction?.invoke()
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SipAccountRow(
    account: SipAccountModel,
    onReRegister: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (account.status) {
        SipRegistrationStatus.REGISTERED -> SignalConnected
        SipRegistrationStatus.REGISTERING,
        SipRegistrationStatus.DISCOVERING -> SignalWarning
        SipRegistrationStatus.ERROR -> MaterialTheme.colorScheme.error
        SipRegistrationStatus.UNREGISTERED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSetDefault() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.WifiCalling3,
                contentDescription = null,
                tint = statusColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.displayName + if (account.isDefault) " (Default)" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${account.sipUri} • ${account.transport.label}:${account.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = account.lastError?.takeIf { account.status == SipRegistrationStatus.ERROR }
                        ?: account.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            IconButton(onClick = onReRegister) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Re-register SIP account")
            }
            Switch(
                checked = account.isEnabled,
                onCheckedChange = onToggleEnabled
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete SIP account",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AudioToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SipAutoConfigBottomSheet(
    form: SipFormState,
    onUpdateForm: (String?, String?, String?, String?, String?) -> Unit,
    onStartAutoConfig: () -> Unit,
    onSaveOffline: () -> Unit,
    onDismiss: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Smart SIP Auto-Configuration",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Text(
                text = "Enter your account credentials. OmniCall automatically probes DNS SRV, UDP, TCP, and TLS to lock in the optimal transport and port.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = form.displayName,
                onValueChange = { onUpdateForm(it, null, null, null, null) },
                label = { Text("Account Label (Optional)") },
                placeholder = { Text("Office PBX") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sip_input_display_name")
            )

            OutlinedTextField(
                value = form.domain,
                onValueChange = { onUpdateForm(null, it, null, null, null) },
                label = { Text("SIP Domain / Host *") },
                placeholder = { Text("sip.example.com") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sip_input_domain")
            )

            OutlinedTextField(
                value = form.username,
                onValueChange = { onUpdateForm(null, null, it, null, null) },
                label = { Text("Username / Extension *") },
                placeholder = { Text("1001") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sip_input_username")
            )

            OutlinedTextField(
                value = form.password,
                onValueChange = { onUpdateForm(null, null, null, it, null) },
                label = { Text("Password *") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sip_input_password")
            )

            OutlinedTextField(
                value = form.optionalPort,
                onValueChange = { onUpdateForm(null, null, null, null, it) },
                label = { Text("Port Override (Optional — auto-detected)") },
                placeholder = { Text("Leave blank for auto-discovery") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sip_input_port")
            )

            AnimatedVisibility(visible = form.isDiscovering) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { (form.discoveryStep.toFloat() / form.discoveryTotalSteps.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = form.discoveryStatusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            form.errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (form.discoveryLogs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            form.discoveryLogs.forEach { line ->
                                Text(
                                    text = "• $line",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (form.errorMessage != null) {
                    OutlinedButton(
                        onClick = onSaveOffline,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sip_save_offline_button")
                    ) {
                        Text("Save Offline")
                    }
                }
                Button(
                    onClick = onStartAutoConfig,
                    enabled = !form.isDiscovering,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("sip_auto_configure_button")
                ) {
                    Text(if (form.isDiscovering) "Probing..." else "Save & Auto-Configure")
                }
            }
        }
    }
}
