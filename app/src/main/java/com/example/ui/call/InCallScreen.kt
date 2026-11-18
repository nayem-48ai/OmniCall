package com.example.ui.call

import android.net.Uri
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.domain.model.ActiveCallState
import com.example.domain.model.AudioRouteType
import com.example.domain.model.CallLifecycleState
import com.example.ui.components.ContactAvatar
import com.example.ui.theme.InCallGradientBottom
import com.example.ui.theme.InCallGradientTop
import com.example.ui.theme.MonoTelemetryStyle
import com.example.ui.theme.SignalConnected
import com.example.ui.theme.SignalRecording

private data class DtmfKey(val digit: Char, val letters: String)

private val DTMF_DIALPAD_ROWS = listOf(
    listOf(DtmfKey('1', "∞"), DtmfKey('2', "ABC"), DtmfKey('3', "DEF")),
    listOf(DtmfKey('4', "GHI"), DtmfKey('5', "JKL"), DtmfKey('6', "MNO")),
    listOf(DtmfKey('7', "PQRS"), DtmfKey('8', "TUV"), DtmfKey('9', "WXYZ")),
    listOf(DtmfKey('*', ""), DtmfKey('0', "+"), DtmfKey('#', ""))
)

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
    isNearEar: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showDtmfPad by remember { mutableStateOf(false) }
    var enteredDtmfDigits by remember { mutableStateOf("") }
    var showAudioMenu by remember { mutableStateOf(false) }

    val pulseTransition = rememberInfiniteTransition(label = "call_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    val hasPhoto = !callState.remotePhotoUri.isNullOrBlank()
    val parsedBackdropUri = remember(callState.remotePhotoUri) {
        callState.remotePhotoUri?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(InCallGradientTop, InCallGradientBottom))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (parsedBackdropUri != null) {
            AsyncImage(
                model = parsedBackdropUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.32f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                InCallGradientTop.copy(alpha = 0.56f),
                                Color.Black.copy(alpha = 0.52f),
                                InCallGradientBottom.copy(alpha = 0.94f)
                            )
                        )
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 14.dp)
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
                        color = SignalRecording.copy(alpha = 0.25f)
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
                                style = MonoTelemetryStyle,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // Caller Info (compact when Google Phone / Zoiper style DTMF pad is open)
            if (showDtmfPad) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (hasPhoto) {
                        ContactAvatar(
                            displayName = callState.displayName.ifBlank { callState.remoteHandle },
                            photoUri = callState.remotePhotoUri,
                            size = 44.dp,
                            fontSize = 16.sp,
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White,
                            borderColor = Color.White.copy(alpha = 0.35f)
                        )
                    }
                    Column(
                        horizontalAlignment = if (hasPhoto) Alignment.Start else Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = callState.displayName.ifBlank { callState.remoteHandle },
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when (callState.lifecycle) {
                                CallLifecycleState.DIALING -> "Dialing..."
                                CallLifecycleState.RINGING_OUTGOING -> "Ringing..."
                                CallLifecycleState.ON_HOLD -> "On Hold • ${formatElapsed(elapsedSeconds)}"
                                CallLifecycleState.ACTIVE -> formatElapsed(elapsedSeconds)
                                else -> ""
                            },
                            style = MonoTelemetryStyle,
                            color = SignalConnected,
                            modifier = Modifier.testTag("in_call_timer_text")
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContactAvatar(
                        displayName = callState.displayName.ifBlank { callState.remoteHandle },
                        photoUri = callState.remotePhotoUri,
                        size = if (hasPhoto) 136.dp else 104.dp,
                        fontSize = if (hasPhoto) 40.sp else 34.sp,
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                        borderColor = if (hasPhoto) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.28f)
                    )

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
                            style = MonoTelemetryStyle.copy(fontSize = 11.sp),
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    callState.statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                }
            }

            // Bottom Action Controls (Incoming vs Keypad vs Standard 6-tile Grid)
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
            } else if (showDtmfPad) {
                // Google Phone / Zoiper-style full regular In-Call Dialpad
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (enteredDtmfDigits.isEmpty()) "Tap keys to send tones" else enteredDtmfDigits.takeLast(18),
                        style = if (enteredDtmfDigits.isEmpty()) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.headlineMedium
                        },
                        color = if (enteredDtmfDigits.isEmpty()) {
                            Color.White.copy(alpha = 0.55f)
                        } else {
                            Color.White
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("in_call_dtmf_display")
                    )

                    InCallRegularDialpad(
                        onDigit = { digit ->
                            enteredDtmfDigits += digit
                            onSendDtmf(digit)
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(88.dp))

                        FilledIconButton(
                            onClick = onEndCall,
                            modifier = Modifier
                                .size(width = 84.dp, height = 64.dp)
                                .testTag("in_call_end_button"),
                            shape = RoundedCornerShape(32.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SignalRecording,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = "End Call",
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.14f),
                            modifier = Modifier
                                .width(88.dp)
                                .height(48.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { showDtmfPad = false }
                                .testTag("in_call_hide_keypad_button")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = "Hide keypad",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Hide",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
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
                                active = false,
                                onClick = { showDtmfPad = true },
                                testTag = "in_call_keypad_button"
                            )
                            val hasMultipleOutputs = callState.availableAudioRoutes.size > 2
                            if (!hasMultipleOutputs) {
                                CallControlTile(
                                    icon = Icons.Rounded.VolumeUp,
                                    label = "Speaker",
                                    active = callState.audioRoute == AudioRouteType.SPEAKER,
                                    onClick = {
                                        val nextRoute = if (callState.audioRoute == AudioRouteType.SPEAKER) {
                                            AudioRouteType.EARPIECE
                                        } else {
                                            AudioRouteType.SPEAKER
                                        }
                                        onSelectAudioRoute(nextRoute)
                                    },
                                    testTag = "in_call_audio_route_button"
                                )
                            } else {
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
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
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
                            .size(width = 84.dp, height = 64.dp)
                            .testTag("in_call_end_button"),
                        shape = RoundedCornerShape(32.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = SignalRecording,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallEnd,
                            contentDescription = "End Call",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }

        // Software proximity touch guard + black screen overlay backing PROXIMITY_SCREEN_OFF_WAKE_LOCK
        val proximityGuardActive = isNearEar &&
            callState.audioRoute == AudioRouteType.EARPIECE &&
            (callState.lifecycle == CallLifecycleState.DIALING ||
                callState.lifecycle == CallLifecycleState.RINGING_OUTGOING ||
                callState.lifecycle == CallLifecycleState.ACTIVE ||
                callState.lifecycle == CallLifecycleState.ON_HOLD)
        if (proximityGuardActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .testTag("proximity_screen_off_guard")
            )
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
                tint = if (active && activeColor == Color.White) InCallGradientTop else Color.White,
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
private fun InCallRegularDialpad(onDigit: (Char) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DTMF_DIALPAD_ROWS.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    Surface(
                        shape = RoundedCornerShape(31.dp),
                        color = Color.White.copy(alpha = 0.14f),
                        modifier = Modifier
                            .size(width = 88.dp, height = 62.dp)
                            .clip(RoundedCornerShape(31.dp))
                            .clickable { onDigit(key.digit) }
                            .testTag("dtmf_key_${key.digit}")
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = key.digit.toString(),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            if (key.letters.isNotEmpty()) {
                                Text(
                                    text = key.letters,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = Color.White.copy(alpha = 0.72f)
                                )
                            }
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
