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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.OutgoingRoutingMode
import com.example.telecom.ActiveCallCoordinator
import com.example.ui.viewmodel.SipSettingsUiState
import com.example.ui.viewmodel.SipSettingsViewModel

@Composable
fun TelephonySettingsScreen(
    uiState: SipSettingsUiState,
    viewModel: SipSettingsViewModel,
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
                // 1. Auto Recall Section (moved inside Telephony & SIM Slots)
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
                                    text = "Auto Recall",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Switch(
                                    checked = uiState.settings.autoRecallEnabled,
                                    onCheckedChange = { viewModel.setAutoRecallEnabled(it) },
                                    modifier = Modifier.testTag("toggle_auto_recall")
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay,
                                    contentDescription = null,
                                    tint = if (uiState.settings.autoRecallEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = "Automatically redial unanswered, declined, or busy outgoing calls after a short countdown.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Countdown timer: ${ActiveCallCoordinator.formatAutoRecallPreset(uiState.settings.autoRecallDelaySeconds)}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(ActiveCallCoordinator.AUTO_RECALL_PRESET_SECONDS, key = { it }) { sec ->
                                    FilterChip(
                                        selected = uiState.settings.autoRecallDelaySeconds == sec,
                                        onClick = { viewModel.setAutoRecallDelaySeconds(sec) },
                                        label = { Text(ActiveCallCoordinator.formatAutoRecallPreset(sec)) },
                                        modifier = Modifier.testTag("auto_recall_delay_$sec")
                                    )
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
