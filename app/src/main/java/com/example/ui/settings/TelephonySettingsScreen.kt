package com.example.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.OutgoingRoutingMode
import com.example.ui.theme.SignalConnected
import com.example.ui.viewmodel.SipSettingsUiState
import com.example.ui.viewmodel.SipSettingsViewModel

@Composable
fun TelephonySettingsScreen(
    uiState: SipSettingsUiState,
    viewModel: SipSettingsViewModel,
    onRequestDefaultDialer: () -> Unit,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("telephony_settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Settings"
                    )
                }
                Text(
                    text = "Telephony & SIM Slots",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Hardware SIM Detection & Default Dialer Card
                item {
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
                                    text = "SIM Slots & Default Phone App",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedButton(
                                    onClick = {
                                        viewModel.refreshEnvironment()
                                        if (uiState.simSlots.isEmpty()) {
                                            onRequestPermissions()
                                        }
                                    },
                                    modifier = Modifier.testTag("telephony_detect_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Detect")
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

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
                                        modifier = Modifier.testTag("telephony_set_default_dialer_button")
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

                            Spacer(modifier = Modifier.height(12.dp))

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
                                        modifier = Modifier.testTag("telephony_grant_permission_button")
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
                                        Column {
                                            Text(
                                                text = sim.fullLabel,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (sim.phoneNumber.isNotBlank()) {
                                                Text(
                                                    text = sim.phoneNumber,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Dynamic Outgoing Call Routing Modes (1..4 modes auto-detected)
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Make Calls With (${availableModes.size} mode${if (availableModes.size == 1) "" else "s"} detected)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(10.dp))

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
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
