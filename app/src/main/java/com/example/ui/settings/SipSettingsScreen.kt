package com.example.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PermDeviceInformation
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiCalling3
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.model.SipAccountModel
import com.example.domain.model.SipBalancePreset
import com.example.domain.model.SipRegistrationStatus
import com.example.sip.SipNetworkHealthResult
import com.example.telecom.PhoneInfoLaunchTarget
import com.example.telecom.PhoneInfoLauncher
import com.example.ui.theme.AppColorPalette
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
    onOpenHiFiAudioSettings: () -> Unit,
    onOpenBlocklist: () -> Unit,
    onOpenTelephonySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val env = uiState.environment
    val availableModes = env.availableModes()
    val effectiveMode = env.effectiveSelectedMode()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var phoneInfoNotice by remember { mutableStateOf<String?>(null) }
    var copiedDialCode by remember { mutableStateOf(false) }

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

            // Hi-Fi Audio & Recording Tab Entry Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenHiFiAudioSettings)
                        .testTag("settings_open_hifi_audio_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.HighQuality,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hi-Fi Audio & Recording",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${uiState.settings.recordingType.label} • ${uiState.settings.recordingFormat.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open Hi-Fi Audio & Recording"
                        )
                    }
                }
            }

            // Blocklist Entry Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(onClick = onOpenBlocklist)
                        .testTag("settings_open_blocklist_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Blocklist",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${uiState.blockedCount} blocked number${if (uiState.blockedCount == 1) "" else "s"} • SIM & SIP",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = "Open Blocklist"
                        )
                    }
                }
            }

            // Phone Info (RadioInfo / Network Band Testing) Entry Card
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                copiedDialCode = false
                                val res = PhoneInfoLauncher.launch(context)
                                phoneInfoNotice = if (res.target == PhoneInfoLaunchTarget.RADIO_INFO) {
                                    null
                                } else {
                                    res.statusMessage
                                }
                            }
                            .testTag("settings_open_phone_info_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PermDeviceInformation,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Phone info",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Radio network mode, 4G/5G band lock & IMS status",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "Open Phone info"
                            )
                        }
                    }

                    AnimatedVisibility(visible = !phoneInfoNotice.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = phoneInfoNotice.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { phoneInfoNotice = null },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Dismiss Phone info notice",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(PhoneInfoLauncher.TESTING_DIALER_CODE))
                                        copiedDialCode = true
                                    },
                                    modifier = Modifier.testTag("copy_phone_info_code_button")
                                ) {
                                    Icon(
                                        imageVector = if (copiedDialCode) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (copiedDialCode) {
                                            "Copied ${PhoneInfoLauncher.TESTING_DIALER_CODE}"
                                        } else {
                                            "Copy ${PhoneInfoLauncher.TESTING_DIALER_CODE}"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Hardware SIM Detection & Default Dialer Card (clickable -> opens TelephonySettingsScreen with Auto Recall & Make Calls With)
            item {
                SettingsSectionCard(
                    title = "Telephony & SIM Slots",
                    onClick = onOpenTelephonySettings,
                    cardTestTag = "settings_open_telephony_card",
                    trailingAction = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = effectiveMode.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = "Open Telephony & SIM Slots",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                ) {
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

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap to open Auto Recall & Make Calls With (${availableModes.size} mode${if (availableModes.size == 1) "" else "s"} detected)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 2. SIP Accounts & Smart Auto-Configuration
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
                        val primarySip = uiState.accounts.firstOrNull { it.isEnabled && it.isDefault }
                            ?: uiState.accounts.firstOrNull { it.isEnabled }
                            ?: uiState.accounts.first()

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            uiState.accounts.forEach { acc ->
                                SipAccountRow(
                                    account = acc,
                                    onManageAccount = { viewModel.openManageAccount(acc) },
                                    onReRegister = { viewModel.reRegisterAccount(acc) },
                                    onSetDefault = { viewModel.setDefaultSipAccount(acc.id) },
                                    onToggleEnabled = { viewModel.toggleAccountEnabled(acc, it) },
                                    onDelete = { viewModel.deleteAccount(acc.id) }
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WifiCalling3,
                                        contentDescription = null,
                                        tint = if (uiState.settings.receiveIncomingSipCalls) {
                                            SignalConnected
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Receive Incoming SIP/VoIP Calls",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (uiState.settings.receiveIncomingSipCalls) {
                                                "Active in background • Turn off here or via notification Stop"
                                            } else {
                                                "Background listening paused • Outgoing SIP calls still work"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = uiState.settings.receiveIncomingSipCalls,
                                        onCheckedChange = { viewModel.setReceiveIncomingSipCalls(it) },
                                        modifier = Modifier.testTag("toggle_receive_incoming_sip_calls")
                                    )
                                }
                            }

                            SipNetworkHealthBanner(
                                targetAccount = primarySip,
                                health = uiState.networkHealth,
                                isChecking = uiState.isCheckingNetwork,
                                onTestNetwork = { viewModel.checkSipNetworkHealth(primarySip) }
                            )

                            BackgroundSipKeepAliveBanner(
                                isBatteryUnrestricted = uiState.isBatteryOptimizationIgnored,
                                onOpenBatterySettings = {
                                    val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    runCatching { context.startActivity(appInfoIntent) }
                                        .onFailure {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                                }
                                            }
                                        }
                                }
                            )
                        }
                    }
                }
            }

            // 4. Theme & Appearance
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

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Color Theme",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AppColorPalette.entries, key = { it.name }) { palette ->
                            val selected = !uiState.settings.dynamicColorEnabled &&
                                uiState.settings.colorPalette == palette
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) {
                                    palette.swatchHex.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) palette.swatchHex else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable { viewModel.setColorPalette(palette) }
                                    .testTag("color_palette_${palette.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(palette.swatchHex),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "${palette.label} selected",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = palette.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
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

    uiState.managedAccount?.let { managed ->
        SipAccountManageSheet(
            account = managed,
            isCheckingBalance = uiState.isCheckingBalance,
            networkHealth = uiState.networkHealth,
            isCheckingNetwork = uiState.isCheckingNetwork,
            onSaveBalanceSettings = { preset, url, showInKeypad, checkNow ->
                viewModel.saveAccountBalanceSettings(
                    accountId = managed.id,
                    preset = preset,
                    customUrl = url,
                    showInKeypad = showInKeypad,
                    checkNow = checkNow
                )
            },
            onCheckBalanceNow = { viewModel.checkAccountBalance(managed) },
            onTestNetwork = { viewModel.checkSipNetworkHealth(managed) },
            onReRegister = { viewModel.reRegisterAccount(managed) },
            onSetDefault = { viewModel.setDefaultSipAccount(managed.id) },
            onDelete = {
                viewModel.closeManageAccount()
                viewModel.deleteAccount(managed.id)
            },
            onDismiss = viewModel::closeManageAccount
        )
    }
}

@Composable
private fun SipNetworkHealthBanner(
    targetAccount: SipAccountModel,
    health: SipNetworkHealthResult?,
    isChecking: Boolean,
    onTestNetwork: () -> Unit
) {
    val containerColor = when {
        health == null -> MaterialTheme.colorScheme.surface
        health.isReachable -> SignalConnected.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
    }
    val accentColor = when {
        health == null -> MaterialTheme.colorScheme.primary
        health.isReachable -> SignalConnected
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.NetworkCheck,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (health == null) {
                        "ISP / Carrier SIP Compatibility"
                    } else if (health.isReachable) {
                        "Network Ready • ${health.networkLabel}"
                    } else {
                        "Network Timeout • ${health.networkLabel}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor
                )
                Text(
                    text = health?.summary
                        ?: "Test whether current Wi-Fi ISP or mobile SIM allows ${targetAccount.effectiveProxyHost()}:${targetAccount.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onTestNetwork,
                enabled = !isChecking,
                modifier = Modifier.testTag("sip_test_network_button")
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Test ISP", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun BackgroundSipKeepAliveBanner(
    isBatteryUnrestricted: Boolean,
    onOpenBatterySettings: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isBatteryUnrestricted) {
            SignalConnected.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isBatteryUnrestricted) Icons.Rounded.CheckCircle else Icons.Rounded.BatterySaver,
                contentDescription = null,
                tint = if (isBatteryUnrestricted) SignalConnected else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isBatteryUnrestricted) {
                        "Background Battery • Unrestricted"
                    } else {
                        "Background Incoming SIP"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isBatteryUnrestricted) SignalConnected else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (isBatteryUnrestricted) {
                        "Battery optimization is disabled so incoming SIP calls arrive reliably"
                    } else {
                        "Tap Allow → Battery usage → Enable 'Allow background activity' or 'Unrestricted'"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = onOpenBatterySettings,
                modifier = Modifier.testTag("sip_battery_settings_button")
            ) {
                Text(
                    text = if (isBatteryUnrestricted) "Manage" else "Allow",
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    onClick: (() -> Unit)? = null,
    cardTestTag: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m }
        .let { m -> if (cardTestTag != null) m.testTag(cardTestTag) else m }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = baseModifier
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
    onManageAccount: () -> Unit,
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
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onManageAccount)
            .testTag("sip_account_card_${account.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Icon, full-width Display Name + Default pill, and Enable Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusColor.copy(alpha = 0.14f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.WifiCalling3,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = account.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (account.isDefault) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "Default",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = account.sipUri,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (!account.lastBalance.isNullOrBlank()) {
                        Text(
                            text = "Balance: ${account.lastBalance}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SignalConnected,
                            maxLines = 1
                        )
                    } else {
                        Text(
                            text = "Tap to manage & check balance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = account.isEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            // Middle Badges & Bottom Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = account.lastError?.takeIf { account.status == SipRegistrationStatus.ERROR }
                                ?: account.status.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${account.transport.label}:${account.port}",
                            style = com.example.ui.theme.MonoTelemetryStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!account.isDefault) {
                        androidx.compose.material3.TextButton(onClick = onSetDefault) {
                            Text("Set Default", maxLines = 1, softWrap = false)
                        }
                    }
                    IconButton(onClick = onReRegister) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Re-register SIP account",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete SIP account",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SipAccountManageSheet(
    account: SipAccountModel,
    isCheckingBalance: Boolean,
    networkHealth: SipNetworkHealthResult?,
    isCheckingNetwork: Boolean,
    onSaveBalanceSettings: (SipBalancePreset, String, Boolean, Boolean) -> Unit,
    onCheckBalanceNow: () -> Unit,
    onTestNetwork: () -> Unit,
    onReRegister: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPreset by remember(account.id, account.balancePreset) {
        mutableStateOf(account.balancePreset)
    }
    var customUrl by remember(account.id, account.balanceCheckUrl) {
        mutableStateOf(account.balanceCheckUrl)
    }
    var showInKeypad by remember(account.id, account.showBalanceInKeypad) {
        mutableStateOf(account.showBalanceInKeypad)
    }

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
                    imageVector = Icons.Rounded.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "${account.sipUri} • ${account.transport.label}:${account.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Live Balance Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Account Balance",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = account.lastBalance ?: "Not checked yet",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (!account.lastBalance.isNullOrBlank()) {
                                SignalConnected
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        account.lastBalanceError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            if (selectedPreset != account.balancePreset ||
                                customUrl != account.balanceCheckUrl ||
                                showInKeypad != account.showBalanceInKeypad
                            ) {
                                onSaveBalanceSettings(selectedPreset, customUrl, showInKeypad, true)
                            } else {
                                onCheckBalanceNow()
                            }
                        },
                        enabled = !isCheckingBalance && selectedPreset != SipBalancePreset.NONE,
                        modifier = Modifier.testTag("sip_check_balance_button")
                    ) {
                        if (isCheckingBalance) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Checking...")
                        } else {
                            Text("Check Balance")
                        }
                    }
                }
            }

            SipNetworkHealthBanner(
                targetAccount = account,
                health = networkHealth,
                isChecking = isCheckingNetwork,
                onTestNetwork = onTestNetwork
            )

            Text(
                text = "Balance Check Provider",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            SipBalancePreset.entries.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selectedPreset = preset }
                        .padding(vertical = 4.dp)
                        .testTag("balance_preset_${preset.name.lowercase()}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedPreset == preset,
                        onClick = { selectedPreset = preset }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        if (preset == SipBalancePreset.ICC_COMMUNICATION) {
                            Text(
                                text = "Uses saved SIP username & password with ICC OTTSSO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (selectedPreset == SipBalancePreset.CUSTOM) {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("Balance Check URL") },
                    placeholder = { Text("https://provider.example.com/api/balance") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sip_balance_custom_url_input")
                )
            }

            if (selectedPreset != SipBalancePreset.NONE) {
                AudioToggleRow(
                    label = "Show in Keypad",
                    description = "Shows SIP • <Balance> in the Keypad top status pill",
                    checked = showInKeypad,
                    onCheckedChange = { showInKeypad = it },
                    testTag = "toggle_show_balance_in_keypad"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!account.isDefault) {
                    OutlinedButton(
                        onClick = onSetDefault,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set Default")
                    }
                } else {
                    OutlinedButton(
                        onClick = onReRegister,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Re-register")
                    }
                }
                Button(
                    onClick = {
                        onSaveBalanceSettings(selectedPreset, customUrl, showInKeypad, false)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sip_save_balance_settings_button")
                ) {
                    Text("Save")
                }
            }

            androidx.compose.material3.TextButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Delete Account", color = MaterialTheme.colorScheme.error)
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
