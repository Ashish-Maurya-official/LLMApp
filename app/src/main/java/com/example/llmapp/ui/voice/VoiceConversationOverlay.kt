package com.example.llmapp.ui.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.ui.chat.state.VoiceState

@Composable
fun VoiceConversationOverlay(
    voiceState: VoiceState,
    partialTranscript: String,
    onInterrupt: () -> Unit,
    onDismiss: () -> Unit
) {
    // Orb color based on state
    val orbColor1 by animateColorAsState(
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> Color(0xFF10A37F)
            VoiceState.THINKING  -> Color(0xFFAB68FF)
            VoiceState.SPEAKING  -> Color(0xFF0EA5E9)
            VoiceState.IDLE      -> Color(0xFF374151)
        },
        animationSpec = tween(600), label = "orb_color1"
    )
    val orbColor2 by animateColorAsState(
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> Color(0xFF059669)
            VoiceState.THINKING  -> Color(0xFF7C3AED)
            VoiceState.SPEAKING  -> Color(0xFF0284C7)
            VoiceState.IDLE      -> Color(0xFF1F2937)
        },
        animationSpec = tween(600), label = "orb_color2"
    )

    // Pulsing scale animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> 1.18f
            VoiceState.THINKING  -> 1.08f
            VoiceState.SPEAKING  -> 1.22f
            VoiceState.IDLE      -> 1.0f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (voiceState) {
                    VoiceState.LISTENING -> 900
                    VoiceState.THINKING  -> 1400
                    VoiceState.SPEAKING  -> 700
                    VoiceState.IDLE      -> 2000
                },
                easing = EaseInOutSine
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Secondary ring scale
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1.3f,
        targetValue = if (voiceState != VoiceState.IDLE) 1.6f else 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring_scale"
    )

    val stateLabel = when (voiceState) {
        VoiceState.LISTENING -> "Listening..."
        VoiceState.THINKING  -> "Thinking..."
        VoiceState.SPEAKING  -> "Speaking..."
        VoiceState.IDLE      -> "Tap to speak"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // Barge-in on tap while speaking or thinking
                    if (voiceState == VoiceState.SPEAKING || voiceState == VoiceState.THINKING) {
                        onInterrupt()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(40.dp)
        ) {

            // Close button
            Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopEnd) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Exit voice mode",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Outer pulsing ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(220.dp)
            ) {
                // Outer ring
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .scale(ringScale)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    orbColor1.copy(alpha = 0.15f),
                                    Color.Transparent
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // Main orb
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(pulseScale)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(orbColor1, orbColor2)
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (voiceState) {
                            VoiceState.SPEAKING -> Icons.Default.Stop
                            else -> Icons.Default.Mic
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            // State label
            Text(
                text = stateLabel,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp
            )

            // Partial transcript from STT
            if (partialTranscript.isNotBlank()) {
                Text(
                    text = "\"$partialTranscript\"",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .widthIn(max = 340.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // Hint
            Text(
                text = when (voiceState) {
                    VoiceState.SPEAKING -> "Tap to interrupt"
                    VoiceState.LISTENING -> "Speak now"
                    VoiceState.THINKING -> "Tap to interrupt"
                    VoiceState.IDLE -> ""
                },
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 48.dp)
            )
        }
    }
}
