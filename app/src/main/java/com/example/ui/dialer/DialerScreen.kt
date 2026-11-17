package com.example.ui.dialer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.WifiCalling3
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.OutgoingRoutingMode
import com.example.ui.components.SipStatusPill
import com.example.ui.viewmodel.DialerUiState
import com.example.ui.viewmodel.DialerViewModel

private data class DialKey(val digit: Char, val letters: String)

private val DIAL_KEYS = listOf(
    listOf(DialKey('1', "∞"), DialKey('2', "ABC"), DialKey('3', "DEF")),
    listOf(DialKey('4', "GHI"), DialKey('5', "JKL"), DialKey('6', "MNO")),
    listOf(DialKey('7', "PQRS"), DialKey('8', "TUV"), DialKey('9', "WXYZ")),
    listOf(DialKey('*', ""), DialKey('0', "+"), DialKey('#', ""))
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    uiState: DialerUiState,
    viewModel: DialerViewModel,
    onOpenSipSettings: () -> Unit,
    onRequestDefaultDialer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val env = uiState.environment
    val availableModes = env.availableModes()
    val activeMode = env.effectiveSelectedMode()
    val defaultSip = env.sipAccounts.firstOrNull { it.isEnabled && it.isDefault }
        ?: env.sipAccounts.firstOrNull { it.isEnabled }

    var showLineMenu by remember { mutableStateOf(false) }
    var useTextKeyboardForUri by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 540.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Top Status & Dynamic Outgoing Route Switcher Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(enabled = availableModes.isNotEmpty()) {
                                    showLineMenu = true
                                }
                                .minimumInteractiveComponentSize()
                                .testTag("outgoing_route_switcher"),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = when (activeMode) {
                                        OutgoingRoutingMode.SIP -> Icons.Rounded.WifiCalling3
                                        else -> Icons.Rounded.SimCard
                                    },
                                    contentDescription = "Outgoing call line",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = when {
                                        availableModes.isEmpty() -> "Configure Line"
                                        activeMode == OutgoingRoutingMode.SIM_1 ->
                                            env.simSlots.getOrNull(0)?.shortLabel ?: "SIM 1"
                                        activeMode == OutgoingRoutingMode.SIM_2 ->
                                            env.simSlots.getOrNull(1)?.shortLabel ?: "SIM 2"
                                        activeMode == OutgoingRoutingMode.SIP ->
                                            defaultSip?.displayName ?: "SIP VoIP"
                                        else -> "Ask every time"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (availableModes.size > 1) {
                                    Icon(
                                        imageVector = Icons.Rounded.ExpandMore,
                                        contentDescription = "Switch calling route",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = showLineMenu,
                            onDismissRequest = { showLineMenu = false }
                        ) {
                            availableModes.forEach { mode ->
                                val subtitle = when (mode) {
                                    OutgoingRoutingMode.SIM_1 -> env.simSlots.getOrNull(0)?.carrierName.orEmpty()
                                    OutgoingRoutingMode.SIM_2 -> env.simSlots.getOrNull(1)?.carrierName.orEmpty()
                                    OutgoingRoutingMode.SIP -> defaultSip?.sipUri.orEmpty()
                                    OutgoingRoutingMode.ASK_EVERY_TIME -> "Prompt on each call"
                                }
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(mode.title, fontWeight = FontWeight.SemiBold)
                                            if (subtitle.isNotBlank()) {
                                                Text(
                                                    subtitle,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setRoutingMode(mode)
                                        showLineMenu = false
                                    }
                                )
                            }
                        }
                    }

                    SipStatusPill(
                        status = defaultSip?.status,
                        transportLabel = defaultSip?.transport?.label,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onOpenSipSettings() }
                            .testTag("dialer_sip_status_pill")
                    )
                }

                // Default Phone App prompt so SIM calls open OmniCall's own InCall UI + silent recorder
                if (!env.isDefaultDialer) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PhoneAndroid,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Set OmniCall as Default Phone App to use OmniCall's call screen & recording instead of Google Phone.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            FilledTonalButton(
                                onClick = onRequestDefaultDialer,
                                modifier = Modifier.testTag("dialer_set_default_button")
                            ) {
                                Text("Set Default")
                            }
                        }
                    }
                }
            }

            // Banner warning if no SIM & no SIP or permission missing
            uiState.bannerMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Dismiss",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable { viewModel.clearBanner() }
                                .padding(8.dp)
                        )
                    }
                }
            }

            // Smart Matched Recents Preview
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                items(uiState.matchingRecents, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { viewModel.initiateCall(item.remoteHandle) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${item.remoteHandle} • ${item.lineLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = "Call ${item.displayName}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Number / SIP URI Display Area
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { useTextKeyboardForUri = !useTextKeyboardForUri },
                        modifier = Modifier.testTag("toggle_sip_uri_input")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Keyboard,
                            contentDescription = "Toggle SIP URI text entry",
                            tint = if (useTextKeyboardForUri) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (useTextKeyboardForUri) {
                            BasicTextField(
                                value = uiState.inputNumber,
                                onValueChange = { viewModel.setNumberInput(it) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.headlineMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dialer_uri_input"),
                                decorationBox = { inner ->
                                    if (uiState.inputNumber.isEmpty()) {
                                        Text(
                                            text = "user@sip.domain",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    inner()
                                }
                            )
                        } else {
                            Text(
                                text = uiState.inputNumber.ifEmpty { "Enter number" },
                                style = if (uiState.inputNumber.length > 13) {
                                    MaterialTheme.typography.headlineMedium
                                } else {
                                    MaterialTheme.typography.displayMedium
                                },
                                color = if (uiState.inputNumber.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("dialer_number_display")
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                enabled = uiState.inputNumber.isNotEmpty(),
                                onClick = { viewModel.backspace() },
                                onLongClick = { viewModel.clearInput() }
                            )
                            .testTag("dialer_backspace_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.inputNumber.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Backspace,
                                contentDescription = "Backspace",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tactile 3x4 Keypad
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DIAL_KEYS.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEach { key ->
                                DialpadButton(
                                    digit = key.digit,
                                    letters = key.letters,
                                    onClick = { viewModel.appendDigit(key.digit) },
                                    onLongClick = {
                                        if (key.digit == '0') viewModel.appendDigit('+')
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Call Button
                FilledIconButton(
                    onClick = { viewModel.initiateCall() },
                    modifier = Modifier
                        .size(width = 92.dp, height = 60.dp)
                        .testTag("dialer_call_button"),
                    shape = RoundedCornerShape(30.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = "Place Call",
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialpadButton(
    digit: Char,
    letters: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(width = 88.dp, height = 62.dp)
            .clip(RoundedCornerShape(31.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .testTag("dial_key_$digit"),
        shape = RoundedCornerShape(31.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit.toString(),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
