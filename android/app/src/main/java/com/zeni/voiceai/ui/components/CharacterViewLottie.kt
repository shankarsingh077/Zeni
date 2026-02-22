package com.zeni.voiceai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.airbnb.lottie.compose.*
import com.zeni.voiceai.R
import com.zeni.voiceai.protocol.SessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Lottie-based Character View - Modern animated avatar
 * Inspired by CollegeBot UI style with smooth Lottie animations
 * 
 * Character is ALWAYS VISIBLE:
 * - IDLE/DEFAULT: Shows avatar in REST state (animation paused)
 * - SPEAKING: Shows talking animation (animation playing)
 */
@Composable
fun CharacterViewLottie(
    sessionState: SessionState,
    audioAmplitude: StateFlow<Float>,
    modifier: Modifier = Modifier
) {
    val amplitude by audioAmplitude.collectAsState(initial = 0f)
    
    // Determine if animation should play based on session state
    // ONLY animate when speaking, otherwise stay still (rest state)
    val isSpeaking = sessionState == SessionState.SPEAKING
    
    // Character is ALWAYS visible - fits screen nicely without extra black background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent),  // Transparent background - no black!
        contentAlignment = Alignment.Center
    ) {
        // Using Lottie Compose API
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.boy_avatar))
        
        // Control animation playback based on state
        val progress by animateLottieCompositionAsState(
            composition = composition,
            iterations = if (isSpeaking) LottieConstants.IterateForever else 1,
            isPlaying = isSpeaking,  // Only play when speaking
            restartOnPlay = false,   // Don't restart from beginning
            speed = if (isSpeaking) 1f else 0f  // Freeze at current frame when not speaking
        )
        
        LottieAnimation(
            composition = composition,
            progress = { if (isSpeaking) progress else 0f },  // Show first frame when idle (rest state)
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)  // Keep avatar square - fills width, maintains proportion
        )
    }
}
