package com.example.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ActiveCallState
import com.example.domain.model.AudioRouteType
import com.example.domain.model.CallLifecycleState
import com.example.ui.theme.InCallGradientBottom
import com.example.ui.theme.InCallGradientTop
import com.example.ui.theme.SignalConnected
import com.example.ui.theme.SignalRecording

@Composable
fun InCallScreen(
    callState: ActiveCallState,
    elapsedSeconds: Int,
    onToggleMute: () -> Unit,
    onToggleHold: () -> Unit,
    onSelectAudioRoute: (AudioRouteType) -> Unit,
    onToggleRecord: () -> Unit,
    onSendDtmf: (Char) -> Unit,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDtmfPad by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }

    val pulseTransition = rememberInfiniteTransition(label = "call_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(InCallGradientTop, InCallGradientBottom))
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Line / Hi-Fi Telemetry Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = SignalConnected,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = callState.routeBadge.ifBlank { callState.sourceType.label },
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }

                // Recording Status Pill when manual record button is active
                AnimatedVisibility(visible = callState.isRecording) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SignalRecording.copy(alpha = 0.22f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FiberManualRecord,
                                contentDescription = "Recording active",
                                tint = SignalRecording,
                                modifier = Modifier
                                    .size(12.dp)
                                    .alpha(pulseAlpha)
                            )
                            Text(
                                text = "REC ${formatElapsed(callState.recordingDurationSec)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Center Caller Avatar & Status Timer
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val initials = callState.displayName.ifBlank { callState.remoteHandle }
                    .take(2).uppercase()

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Text(
                    text = callState.displayName.ifBlank { callState.remoteHandle },
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = callState.remoteHandle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f)
                )

                Text(
                    text = when (callState.lifecycle) {
                        CallLifecycleState.DIALING -> "Dialing..."
                        CallLifecycleState.RINGING_INCOMING -> "Incoming Call"
                        CallLifecycleState.RINGING_OUTGOING -> "Ringing..."
                        CallLifecycleState.ON_HOLD -> "On Hold • ${formatElapsed(elapsedSeconds)}"
                        CallLifecycleState.ACTIVE -> formatElapsed(elapsedSeconds)
                        CallLifecycleState.ENDED -> "Call Ended"
                        CallLifecycleState.IDLE -> ""
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = SignalConnected,
                    modifier = Modifier.testTag("in_call_timer_text")
                )

                // Hardware AEC/NS/Codec Telemetry Badge
                val t = callState.telemetry
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.08f)
                ) {
                    val dspFlags = buildList {
                        add(t.codecName)
                        if (t.aecActive) add("AEC")
                        if (t.nsActive) add("NS")
                        if (t.agcActive) add("AGC")
                        if (t.jitterMs > 0) add("${t.jitterMs}ms jitter")
                    }.joinToString(" • ")
                    Text(
                        text = dspFlags,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Bottom Action Controls (Incoming vs Active)
            if (callState.lifecycle == CallLifecycleState.RINGING_INCOMING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onDecline,
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("incoming_decline_button"),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SignalRecording,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Rounded.CallEnd, contentDescription = "Decline call", modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Decline", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledIconButton(
                            onClick = onAnswer,
                            modifier = Modifier
                                .size(72.dp)
                                .testTag("incoming_answer_button"),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SignalConnected,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = "Answer call", modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Answer", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AnimatedVisibility(visible = showDtmfPad) {
                        InCallDtmfPad(onDigit = onSendDtmf)
                    }

                    // 6-Tile Google Phone Style Control Grid
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            CallControlTile(
                                icon = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                label = if (callState.isMuted) "Unmute" else "Mute",
                                active = callState.isMuted,
                                onClick = onToggleMute,
                                testTag = "in_call_mute_button"
                            )
                            CallControlTile(
                                icon = Icons.Rounded.Dialpad,
                                label = "Keypad",
                                active = showDtmfPad,
                                onClick = { showDtmfPad = !showDtmfPad },
                                testTag = "in_call_keypad_button"
                            )
                            Box {
                                CallControlTile(
                                    icon = when (callState.audioRoute) {
                                        AudioRouteType.SPEAKER -> Icons.Rounded.VolumeUp
                                        AudioRouteType.BLUETOOTH -> Icons.Rounded.BluetoothAudio
                                        AudioRouteType.WIRED_HEADSET -> Icons.Rounded.Headset
                                        AudioRouteType.EARPIECE -> Icons.Rounded.PhoneInTalk
                                    },
                                    label = callState.audioRoute.label,
                                    active = callState.audioRoute != AudioRouteType.EARPIECE,
                                    onClick = { showAudioMenu = true },
                                    testTag = "in_call_audio_route_button"
                                )
                                DropdownMenu(
                                    expanded = showAudioMenu,
                                    onDismissRequest = { showAudioMenu = false }
                                ) {
                                    callState.availableAudioRoutes.forEach { route ->
                                        DropdownMenuItem(
                                            text = { Text(route.label) },
                                            onClick = {
                                                onSelectAudioRoute(route)
                                                showAudioMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Manual Call Recording Toggle (Google Phone Style)
                            CallControlTile(
                                icon = Icons.Rounded.FiberManualRecord,
                                label = if (callState.isRecording) "Stop Rec" else "Record",
                                active = callState.isRecording,
                                activeColor = SignalRecording,
                                onClick = onToggleRecord,
                                testTag = "in_call_record_button"
                            )
                            CallControlTile(
                                icon = if (callState.isOnHold) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                label = if (callState.isOnHold) "Resume" else "Hold",
                                active = callState.isOnHold,
                                onClick = onToggleHold,
                                testTag = "in_call_hold_button"
                            )
                            CallControlTile(
                                icon = Icons.Rounded.SmartToy,
                                label = "Tap Ready",
                                active = false,
                                onClick = { /* Phase 2 AI Agent Tap hook indicator */ },
                                testTag = "in_call_ai_hook_badge"
                            )
                        }
                    }

                    // Hangup FAB
                    FilledIconButton(
                        onClick = onEndCall,
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("in_call_end_button"),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SignalRecording,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallEnd,
                            contentDescription = "End Call",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControlTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String,
    activeColor: Color = Color.White
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (active) activeColor.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.14f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active && activeColor == Color.White) Color(0xFF0F172A) else Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 1
        )
    }
}

@Composable
private fun InCallDtmfPad(onDigit: (Char) -> Unit) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('*', '0', '#')
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { digit ->
                        Box(
                            modifier = Modifier
                                .size(width = 76.dp, height = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { onDigit(digit) }
                                .testTag("dtmf_key_$digit"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = digit.toString(),
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatElapsed(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
