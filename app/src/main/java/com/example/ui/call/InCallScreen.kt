package com.example.ui.call

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.domain.model.ActiveCallState
import com.example.domain.model.AudioRouteType
import com.example.domain.model.CallLifecycleState
import com.example.ui.components.ContactAvatar
import kotlin.math.abs
import kotlin.math.roundToInt
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
    onCancelAutoRecall: () -> Unit = {},
    onRecallNow: () -> Unit = {},
    onMinimize: (() -> Unit)? = null,
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
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Line / Hi-Fi Telemetry Header with Minimize button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (onMinimize != null) {
                        IconButton(
                            onClick = onMinimize,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .size(48.dp)
                                .testTag("in_call_minimize_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Minimize call window",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.align(Alignment.Center)
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
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
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
                                CallLifecycleState.AUTO_RECALL_COUNTDOWN ->
                                    "Recalling in ${com.example.telecom.ActiveCallCoordinator.formatAutoRecallRemaining(callState.autoRecallRemainingSec)}"
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
                    val avatarSize = if (hasPhoto) 136.dp else 108.dp
                    if (callState.lifecycle == CallLifecycleState.RINGING_INCOMING) {
                        val ringTransition = rememberInfiniteTransition(label = "incoming_rings")
                        val ring1Scale by ringTransition.animateFloat(
                            initialValue = 1.0f,
                            targetValue = 1.52f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1700, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "ring1_scale"
                        )
                        val ring1Alpha by ringTransition.animateFloat(
                            initialValue = 0.48f,
                            targetValue = 0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1700, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "ring1_alpha"
                        )
                        val ring2Scale by ringTransition.animateFloat(
                            initialValue = 1.0f,
                            targetValue = 1.52f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1700, delayMillis = 650, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "ring2_scale"
                        )
                        val ring2Alpha by ringTransition.animateFloat(
                            initialValue = 0.42f,
                            targetValue = 0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1700, delayMillis = 650, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "ring2_alpha"
                        )

                        Box(
                            modifier = Modifier.size(avatarSize + 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(avatarSize)
                                    .scale(ring1Scale)
                                    .clip(CircleShape)
                                    .border(2.dp, SignalConnected.copy(alpha = ring1Alpha), CircleShape)
                                    .background(SignalConnected.copy(alpha = ring1Alpha * 0.25f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(avatarSize)
                                    .scale(ring2Scale)
                                    .clip(CircleShape)
                                    .border(2.dp, Color.White.copy(alpha = ring2Alpha), CircleShape)
                                    .background(Color.White.copy(alpha = ring2Alpha * 0.18f))
                            )
                            ContactAvatar(
                                displayName = callState.displayName.ifBlank { callState.remoteHandle },
                                photoUri = callState.remotePhotoUri,
                                size = avatarSize,
                                fontSize = if (hasPhoto) 40.sp else 36.sp,
                                containerColor = Color.White.copy(alpha = 0.16f),
                                contentColor = Color.White,
                                borderColor = Color.White.copy(alpha = 0.52f)
                            )
                        }
                    } else {
                        ContactAvatar(
                            displayName = callState.displayName.ifBlank { callState.remoteHandle },
                            photoUri = callState.remotePhotoUri,
                            size = avatarSize,
                            fontSize = if (hasPhoto) 40.sp else 34.sp,
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White,
                            borderColor = if (hasPhoto) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.28f)
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
                            CallLifecycleState.AUTO_RECALL_COUNTDOWN ->
                                "${callState.autoRecallReason.ifBlank { "No answer" }} • Recalling in ${com.example.telecom.ActiveCallCoordinator.formatAutoRecallRemaining(callState.autoRecallRemainingSec)}"
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

            // Bottom Action Controls (Phone by Google Swipe Pill vs Auto Recall Countdown vs Keypad vs 6-tile Grid)
            if (callState.lifecycle == CallLifecycleState.RINGING_INCOMING) {
                IncomingSwipeAnswerBar(
                    onAnswer = onAnswer,
                    onDecline = onDecline,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            } else if (callState.lifecycle == CallLifecycleState.AUTO_RECALL_COUNTDOWN) {
                AutoRecallCountdownDock(
                    remainingSec = callState.autoRecallRemainingSec,
                    totalSec = callState.autoRecallTotalSec,
                    reason = callState.autoRecallReason,
                    attempt = callState.autoRecallAttempt,
                    onCancel = onCancelAutoRecall,
                    onRecallNow = onRecallNow,
                    modifier = Modifier.padding(bottom = 18.dp)
                )
            } else if (showDtmfPad) {
                // Google Phone / Zoiper-style full regular In-Call Dialpad inside elevated dock
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = lerp(InCallGradientBottom, Color.White, 0.08f).copy(alpha = 0.90f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(32.dp)
                        )
                        .testTag("in_call_dtmf_dock")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 18.dp)
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
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.width(88.dp))

                            FilledIconButton(
                                onClick = onEndCall,
                                modifier = Modifier
                                    .size(width = 96.dp, height = 64.dp)
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
                }
            } else {
                // Google Phone inspired rounded dark control dock with comfortably raised End Call pill
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = lerp(InCallGradientBottom, Color.White, 0.08f).copy(alpha = 0.90f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp)
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(32.dp)
                        )
                        .testTag("in_call_controls_dock")
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 20.dp)
                    ) {
                        // 6-Tile Google Phone Style Control Grid
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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

                        // Raised Hangup Pill matching Google Phone ergonomics
                        FilledIconButton(
                            onClick = onEndCall,
                            modifier = Modifier
                                .size(width = 96.dp, height = 64.dp)
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

@Composable
private fun IncomingSwipeAnswerBar(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var triggered by remember { mutableStateOf(false) }

    val animatedOffsetPx by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = if (isDragging) {
            spring(stiffness = Spring.StiffnessHigh)
        } else {
            spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium)
        },
        label = "swipe_pill_offset"
    )

    val idleTransition = rememberInfiniteTransition(label = "swipe_idle_pulse")
    val idleArrowAlpha by idleTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "swipe_arrow_alpha"
    )
    val thumbBreathScale by idleTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "thumb_breath"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .height(78.dp),
            contentAlignment = Alignment.Center
        ) {
            val thumbSize = 62.dp
            val edgePadding = 8.dp
            val maxDragPx = with(density) {
                ((maxWidth - thumbSize) / 2f - edgePadding).coerceAtLeast(60.dp).toPx()
            }
            val triggerThresholdPx = maxDragPx * 0.68f
            val progress = (animatedOffsetPx / maxDragPx).coerceIn(-1f, 1f)

            val trackColor = when {
                progress > 0.05f -> lerp(
                    Color.White.copy(alpha = 0.14f),
                    SignalConnected.copy(alpha = 0.92f),
                    progress
                )
                progress < -0.05f -> lerp(
                    Color.White.copy(alpha = 0.14f),
                    SignalRecording.copy(alpha = 0.92f),
                    abs(progress)
                )
                else -> Color.White.copy(alpha = 0.14f)
            }

            Surface(
                shape = RoundedCornerShape(39.dp),
                color = trackColor,
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(39.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Left Endpoint (Swipe Left or Tap to Decline)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clip(CircleShape)
                            .clickable(enabled = !triggered) {
                                triggered = true
                                onDecline()
                            }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .testTag("incoming_decline_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(SignalRecording.copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CallEnd,
                                contentDescription = "Decline call",
                                tint = if (progress < -0.5f) Color.White else Color(0xFFFF8A80),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = idleArrowAlpha),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Right Endpoint (Swipe Right or Tap to Answer)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clip(CircleShape)
                            .clickable(enabled = !triggered) {
                                triggered = true
                                onAnswer()
                            }
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .testTag("incoming_answer_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = idleArrowAlpha),
                            modifier = Modifier.size(20.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(SignalConnected.copy(alpha = 0.28f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Call,
                                contentDescription = "Answer call",
                                tint = if (progress > 0.5f) Color.White else Color(0xFF81C995),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // Draggable Center Handle
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                            .scale(if (isDragging) 1.08f else thumbBreathScale)
                            .size(thumbSize)
                            .pointerInput(maxDragPx) {
                                detectHorizontalDragGestures(
                                    onDragStart = { isDragging = true },
                                    onDragCancel = {
                                        isDragging = false
                                        dragOffsetPx = 0f
                                    },
                                    onDragEnd = {
                                        isDragging = false
                                        if (!triggered) {
                                            when {
                                                dragOffsetPx >= triggerThresholdPx -> {
                                                    triggered = true
                                                    dragOffsetPx = maxDragPx
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onAnswer()
                                                }
                                                dragOffsetPx <= -triggerThresholdPx -> {
                                                    triggered = true
                                                    dragOffsetPx = -maxDragPx
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onDecline()
                                                }
                                                else -> dragOffsetPx = 0f
                                            }
                                        }
                                    },
                                    onHorizontalDrag = { _, dragAmount ->
                                        if (!triggered) {
                                            val prev = dragOffsetPx
                                            val next = (prev + dragAmount).coerceIn(-maxDragPx, maxDragPx)
                                            if (abs(prev) < triggerThresholdPx && abs(next) >= triggerThresholdPx) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                            dragOffsetPx = next
                                        }
                                    }
                                )
                            }
                            .testTag("incoming_swipe_handle")
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                imageVector = if (progress < -0.35f) Icons.Rounded.CallEnd else Icons.Rounded.Call,
                                contentDescription = "Swipe right to answer or left to decline",
                                tint = when {
                                    progress > 0.25f -> SignalConnected
                                    progress < -0.25f -> SignalRecording
                                    else -> InCallGradientTop
                                },
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Swipe left to decline",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = "Swipe right to answer",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

/**
 * Elevated Google-style Auto Recall countdown dock shown after an unanswered/declined/busy outgoing call.
 */
@Composable
private fun AutoRecallCountdownDock(
    remainingSec: Int,
    totalSec: Int,
    reason: String,
    attempt: Int,
    onCancel: () -> Unit,
    onRecallNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeTotal = totalSec.coerceAtLeast(1)
    val targetFraction = (remainingSec.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "auto_recall_progress"
    )

    Surface(
        shape = RoundedCornerShape(32.dp),
        color = lerp(InCallGradientBottom, Color.White, 0.08f).copy(alpha = 0.94f),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(32.dp)
            )
            .testTag("in_call_auto_recall_dock")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = SignalConnected.copy(alpha = 0.18f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = null,
                        tint = SignalConnected,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Auto Recall • Attempt $attempt/${com.example.telecom.ActiveCallCoordinator.MAX_AUTO_RECALL_ATTEMPTS}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = SignalConnected
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(92.dp)
            ) {
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 6.dp,
                    color = Color.White.copy(alpha = 0.14f)
                )
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 6.dp,
                    strokeCap = StrokeCap.Round,
                    color = SignalConnected
                )
                Text(
                    text = com.example.telecom.ActiveCallCoordinator.formatAutoRecallRemaining(remainingSec),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.testTag("auto_recall_countdown_seconds")
                )
            }

            Text(
                text = "${reason.ifBlank { "No answer" }} • Calling again in ${com.example.telecom.ActiveCallCoordinator.formatAutoRecallRemaining(remainingSec)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(28.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.28f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("in_call_cancel_recall_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onRecallNow,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SignalConnected,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .testTag("in_call_recall_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Call Now", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Phone by Google style floating call mini window shown inside the app when a call is minimized.
 */
@Composable
fun CallMiniWindowCard(
    callState: ActiveCallState,
    elapsedSeconds: Int,
    onExpand: () -> Unit,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit,
    onCancelAutoRecall: () -> Unit = {},
    onRecallNow: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isIncoming = callState.lifecycle == CallLifecycleState.RINGING_INCOMING
    val isAutoRecall = callState.lifecycle == CallLifecycleState.AUTO_RECALL_COUNTDOWN
    val pulseTransition = rememberInfiniteTransition(label = "mini_window_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "mini_pulse_alpha"
    )

    val conciseBadge = com.example.telecom.CallNotificationManager
        .extractShortLineLabel(callState.routeBadge.ifBlank { callState.sourceType.label })
        .ifBlank { callState.sourceType.label }

    val statusText = when (callState.lifecycle) {
        CallLifecycleState.RINGING_INCOMING ->
            if (callState.remoteHandle.isNotBlank() && callState.remoteHandle != callState.displayName) {
                "Incoming Via $conciseBadge, ${callState.remoteHandle}"
            } else {
                "Incoming Via $conciseBadge"
            }
        CallLifecycleState.DIALING -> "Dialing • $conciseBadge"
        CallLifecycleState.RINGING_OUTGOING -> "Ringing • $conciseBadge"
        CallLifecycleState.ON_HOLD -> "On Hold • ${formatElapsed(elapsedSeconds)}"
        CallLifecycleState.ACTIVE -> "${formatElapsed(elapsedSeconds)} • $conciseBadge"
        CallLifecycleState.AUTO_RECALL_COUNTDOWN ->
            "${callState.autoRecallReason.ifBlank { "Auto Recall" }} • ${com.example.telecom.ActiveCallCoordinator.formatAutoRecallRemaining(callState.autoRecallRemainingSec)}"
        else -> conciseBadge
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = InCallGradientTop.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
        modifier = modifier
            .widthIn(max = 540.dp)
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onExpand)
            .testTag("call_mini_window")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                ContactAvatar(
                    displayName = callState.displayName.ifBlank { callState.remoteHandle },
                    photoUri = callState.remotePhotoUri,
                    size = 44.dp,
                    fontSize = 16.sp,
                    containerColor = Color.White.copy(alpha = 0.16f),
                    contentColor = Color.White,
                    borderColor = SignalConnected.copy(alpha = 0.65f)
                )
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(if (isIncoming || isAutoRecall) SignalConnected.copy(alpha = pulseAlpha) else SignalConnected)
                        .border(1.5.dp, InCallGradientTop, CircleShape)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = callState.displayName.ifBlank { callState.remoteHandle },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isIncoming || isAutoRecall) SignalConnected else Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isIncoming) {
                FilledIconButton(
                    onClick = onDecline,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_window_decline_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SignalRecording,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = "Decline call",
                        modifier = Modifier.size(22.dp)
                    )
                }
                FilledIconButton(
                    onClick = onAnswer,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_window_answer_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SignalConnected,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = "Answer call",
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else if (isAutoRecall) {
                FilledIconButton(
                    onClick = onCancelAutoRecall,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_window_cancel_recall_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SignalRecording,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Cancel Auto Recall",
                        modifier = Modifier.size(22.dp)
                    )
                }
                FilledIconButton(
                    onClick = onRecallNow,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_window_recall_now_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SignalConnected,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = "Recall now",
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                FilledIconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_window_mute_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (callState.isMuted) Color.White else Color.White.copy(alpha = 0.16f),
                        contentColor = if (callState.isMuted) InCallGradientTop else Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (callState.isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        contentDescription = if (callState.isMuted) "Unmute" else "Mute",
                        modifier = Modifier.size(20.dp)
                    )
                }
                FilledIconButton(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("mini_window_end_button"),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = SignalRecording,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = "End call",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
