package com.zeni.voiceai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeni.voiceai.protocol.SessionState
import com.zeni.voiceai.ui.theme.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Main talk button with visual feedback based on session state.
 * Supports interrupt - pressing while speaking will interrupt and start recording.
 */
@Composable
fun TalkButton(
    sessionState: SessionState,
    isRecording: Boolean,
    isAudioPlaying: Boolean = false,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 120.dp
) {
    // Show interrupt-ready state when audio is playing
    val canInterrupt = isAudioPlaying || sessionState == SessionState.SPEAKING || sessionState == SessionState.GENERATING
    
    val backgroundColor = when {
        canInterrupt -> ZeniOrange.copy(alpha = 0.8f) // Orange when can interrupt
        sessionState == SessionState.IDLE -> Primary.copy(alpha = 0.6f)
        sessionState == SessionState.LISTENING -> Listening
        sessionState == SessionState.TRANSCRIBING -> Processing
        sessionState == SessionState.GENERATING -> Processing
        sessionState == SessionState.SPEAKING -> Speaking
        sessionState == SessionState.INTERRUPTED -> Primary
        sessionState == SessionState.ERROR -> Error
        sessionState == SessionState.CLOSED -> Disconnected
        else -> Primary.copy(alpha = 0.6f)
    }
    
    // Pulsing animation for listening state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    val scale = if (sessionState == SessionState.LISTENING || isRecording) pulseScale else 1f
    
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { 
                        try {
                            onPress()
                            awaitRelease()
                        } finally {
                            onRelease()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            canInterrupt && !isRecording -> {
                // Show stop/interrupt icon when can interrupt
                StopIcon(
                    color = Color.White,
                    modifier = Modifier.size(size * 0.35f)
                )
            }
            sessionState == SessionState.LISTENING || isRecording -> {
                WaveformAnimation(
                    color = Color.White,
                    modifier = Modifier.size(size * 0.6f)
                )
            }
            sessionState == SessionState.GENERATING || sessionState == SessionState.TRANSCRIBING -> {
                LoadingSpinner(
                    color = Color.White,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
            else -> {
                MicrophoneIcon(
                    color = Color.White,
                    modifier = Modifier.size(size * 0.4f)
                )
            }
        }
    }
}

/**
 * Waveform animation for listening state.
 */
@Composable
fun WaveformAnimation(
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 5
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    
    val phases = (0 until barCount).map { i ->
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2 * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 600 + i * 100,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase$i"
        )
    }
    
    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2)
        val maxHeight = size.height * 0.8f
        val minHeight = size.height * 0.2f
        
        for (i in 0 until barCount) {
            val phase = phases[i].value
            val height = minHeight + (maxHeight - minHeight) * ((sin(phase) + 1) / 2)
            
            val x = (i * 2 + 1) * barWidth
            val y = (size.height - height) / 2
            
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + height),
                strokeWidth = barWidth * 0.7f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Loading spinner for processing state.
 */
@Composable
fun LoadingSpinner(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.1f
        val radius = (size.minDimension - strokeWidth) / 2
        
        drawArc(
            color = color.copy(alpha = 0.3f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )
        
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2)
        )
    }
}

/**
 * Speaking animation (voice bars).
 */
@Composable
fun SpeakingAnimation(
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 4
) {
    val infiniteTransition = rememberInfiniteTransition(label = "speaking")
    
    val heights = (0 until barCount).map { i ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 300 + i * 100,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "height$i"
        )
    }
    
    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2)
        val maxHeight = size.height * 0.8f
        
        for (i in 0 until barCount) {
            val height = maxHeight * heights[i].value
            val x = (i * 2 + 1) * barWidth
            val y = (size.height - height) / 2
            
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + height),
                strokeWidth = barWidth * 0.8f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * Simple microphone icon.
 */
@Composable
fun MicrophoneIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val micWidth = size.width * 0.3f
        val micHeight = size.height * 0.5f
        val micTop = size.height * 0.1f
        
        // Microphone body
        drawRoundRect(
            color = color,
            topLeft = Offset(centerX - micWidth / 2, micTop),
            size = androidx.compose.ui.geometry.Size(micWidth, micHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(micWidth / 2)
        )
        
        // Stand arc
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            style = Stroke(width = micWidth * 0.3f, cap = StrokeCap.Round),
            topLeft = Offset(centerX - micWidth * 0.8f, micTop + micHeight * 0.5f),
            size = androidx.compose.ui.geometry.Size(micWidth * 1.6f, micHeight * 0.6f)
        )
        
        // Stand line
        drawLine(
            color = color,
            start = Offset(centerX, micTop + micHeight * 0.8f + micHeight * 0.3f),
            end = Offset(centerX, size.height * 0.85f),
            strokeWidth = micWidth * 0.3f,
            cap = StrokeCap.Round
        )
        
        // Base
        drawLine(
            color = color,
            start = Offset(centerX - micWidth * 0.6f, size.height * 0.85f),
            end = Offset(centerX + micWidth * 0.6f, size.height * 0.85f),
            strokeWidth = micWidth * 0.3f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Stop/Interrupt icon - square shape indicating tap to interrupt
 */
@Composable
fun StopIcon(
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val padding = size.width * 0.15f
        drawRoundRect(
            color = color,
            topLeft = Offset(padding, padding),
            size = androidx.compose.ui.geometry.Size(
                size.width - padding * 2,
                size.height - padding * 2
            ),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.15f)
        )
    }
}
