package com.zeni.voiceai.ui.components

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zeni.voiceai.R

/**
 * Video-based Character View - Real person avatar with two states
 * 
 * Uses ExoPlayer for smooth video playback and transitions:
 * - IDLE: Loops idle_state.mp4 when audio is NOT playing from speakers
 * - TALKING: Loops talking_state.mp4 when audio IS playing from speakers
 * - Crossfade transition between states (NO CONTROLS shown)
 * 
 * @param isAudioPlaying TRUE when audio is actually playing from speakers (not just streaming)
 */
@OptIn(UnstableApi::class)
@Composable
fun CharacterVideoView(
    isAudioPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Build URIs for raw resources
    val idleUri = remember {
        Uri.parse("android.resource://${context.packageName}/${R.raw.idle_state}")
    }
    val talkingUri = remember {
        Uri.parse("android.resource://${context.packageName}/${R.raw.talking_state}")
    }
    
    // Create ExoPlayer for idle video
    val idlePlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(idleUri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // Mute
            prepare()
            playWhenReady = true
        }
    }
    
    // Create ExoPlayer for talking video
    val talkingPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(talkingUri))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f // Mute
            prepare()
            playWhenReady = false
        }
    }
    
    // Remember PlayerViews to reuse them (avoids recreating and showing controls)
    val idlePlayerView = remember {
        PlayerView(context).apply {
            player = idlePlayer
            useController = false
            controllerAutoShow = false
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Hide controller completely
            hideController()
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { 
                // Force hide if it ever tries to show
                hideController()
            })
        }
    }
    
    val talkingPlayerView = remember {
        PlayerView(context).apply {
            player = talkingPlayer
            useController = false
            controllerAutoShow = false
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Hide controller completely
            hideController()
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { 
                // Force hide if it ever tries to show
                hideController()
            })
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            idlePlayer.release()
            talkingPlayer.release()
        }
    }
    
    // Handle state transitions - based on actual audio playback
    LaunchedEffect(isAudioPlaying) {
        if (isAudioPlaying) {
            // Start talking, pause idle
            talkingPlayer.seekTo(0)
            talkingPlayer.playWhenReady = true
            idlePlayer.playWhenReady = false
        } else {
            // Start idle, pause talking
            idlePlayer.playWhenReady = true
            talkingPlayer.playWhenReady = false
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        // Crossfade between the two videos - using pre-created PlayerViews
        Crossfade(
            targetState = isAudioPlaying,
            animationSpec = tween(300),
            label = "video_crossfade"
        ) { showTalking ->
            val playerView = if (showTalking) talkingPlayerView else idlePlayerView
            
            AndroidView(
                factory = { playerView },
                update = { view ->
                    // Ensure controller stays hidden
                    view.hideController()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

