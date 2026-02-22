package com.zeni.voiceai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio playback engine for playing TTS responses.
 * Supports streaming playback and instant interruption.
 * 
 * OPTIMIZED for low latency:
 * - Uses PERFORMANCE_MODE_LOW_LATENCY when available
 * - Smaller buffer size (50ms instead of 100ms)
 * - Optimized audio attributes for voice assistant
 */
class AudioPlaybackEngine {
    
    companion object {
        private const val TAG = "AudioPlayback"
        const val DEFAULT_SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // OPTIMIZED: Target 50ms buffer for lower latency (was 100ms)
        private const val TARGET_BUFFER_MS = 50
    }
    
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = DEFAULT_SAMPLE_RATE
    private val isPlaying = AtomicBoolean(false)
    private var playbackJob: Job? = null
    
    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    
    // Audio amplitude tracking for animations
    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()
    
    // TRUE only when audio is actually being written to speakers
    // This is what the character video should observe for talking animation
    private val _isActuallyPlaying = MutableStateFlow(false)
    val isActuallyPlaying: StateFlow<Boolean> = _isActuallyPlaying.asStateFlow()
    
    /**
     * Initialize the audio track with specified sample rate.
     */
    fun initialize(sampleRate: Int = DEFAULT_SAMPLE_RATE): Boolean {
        // If sample rate changed, reinitialize
        if (audioTrack != null && currentSampleRate != sampleRate) {
            release()
        }
        
        if (audioTrack != null) {
            return true
        }
        
        currentSampleRate = sampleRate
        
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            
            if (minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid audio parameters")
                return false
            }
            
            // OPTIMIZED: Use smaller buffer for lower latency (50ms target)
            // bytes = sampleRate * bytesPerSample * (ms / 1000)
            val targetBufferSize = sampleRate * 2 * TARGET_BUFFER_MS / 1000
            val bufferSize = maxOf(minBufferSize, targetBufferSize)
            
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setFlags(AudioAttributes.FLAG_LOW_LATENCY) // Request low latency
                .build()
            
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AUDIO_FORMAT)
                .setChannelMask(CHANNEL_CONFIG)
                .build()
            
            // OPTIMIZED: Use low latency performance mode (API 26+)
            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
            
            // Enable low latency mode if available (API 26+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            
            audioTrack = trackBuilder.build()
            
            Log.d(TAG, "AudioTrack initialized: sampleRate=$sampleRate, bufferSize=$bufferSize, targetLatency=${TARGET_BUFFER_MS}ms")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioTrack", e)
            return false
        }
    }
    
    /**
     * Queue audio data for playback.
     */
    fun queueAudio(data: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        // Reinitialize if sample rate changed
        if (sampleRate != currentSampleRate) {
            initialize(sampleRate)
        }
        
        audioQueue.trySend(data)
    }
    
    /**
     * Start playback.
     */
    fun startPlayback(scope: CoroutineScope): Boolean {
        if (isPlaying.get()) {
            return true
        }
        
        if (audioTrack == null && !initialize()) {
            return false
        }
        
        try {
            audioTrack?.play()
            isPlaying.set(true)
            
            playbackJob = scope.launch(Dispatchers.IO) {
                playbackLoop()
            }
            
            Log.d(TAG, "Playback started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
            isPlaying.set(false)
            return false
        }
    }
    
    /**
     * Stop playback immediately.
     */
    fun stopPlayback() {
        if (!isPlaying.get()) {
            return
        }
        
        isPlaying.set(false)
        _isActuallyPlaying.value = false
        _audioAmplitude.value = 0f
        
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
        }
        
        playbackJob?.cancel()
        playbackJob = null
        
        // Clear audio queue
        while (audioQueue.tryReceive().isSuccess) {
            // Drain queue
        }
        
        Log.d(TAG, "Playback stopped")
    }
    
    /**
     * Release resources.
     */
    fun release() {
        stopPlayback()
        
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
        
        audioTrack = null
        audioQueue.close()
        
        Log.d(TAG, "AudioPlayback released")
    }
    
    /**
     * Main playback loop.
     */
    private suspend fun playbackLoop() {
        while (isPlaying.get()) {
            try {
                // Use withTimeoutOrNull to detect when queue is empty (playback ended)
                val result = withTimeoutOrNull(100) {
                    audioQueue.receiveCatching()
                }
                
                if (result == null) {
                    // No data for 100ms - audio has likely finished
                    if (_isActuallyPlaying.value) {
                        _isActuallyPlaying.value = false
                        _audioAmplitude.value = 0f
                        Log.d(TAG, "Audio playback finished (queue empty)")
                    }
                    continue
                }
                
                if (result.isSuccess) {
                    val data = result.getOrNull() ?: continue
                    
                    if (isPlaying.get() && audioTrack != null) {
                        // Mark as actually playing when we write audio
                        if (!_isActuallyPlaying.value) {
                            _isActuallyPlaying.value = true
                            Log.d(TAG, "Audio playback started (writing to speaker)")
                        }
                        
                        // Calculate RMS amplitude for animation
                        val amplitude = calculateRMSAmplitude(data)
                        _audioAmplitude.value = amplitude
                        
                        val written = audioTrack!!.write(data, 0, data.size)
                        if (written < 0) {
                            Log.e(TAG, "Error writing audio: $written")
                        }
                    }
                } else if (result.isClosed) {
                    _isActuallyPlaying.value = false
                    _audioAmplitude.value = 0f
                    break
                }
            } catch (e: CancellationException) {
                _isActuallyPlaying.value = false
                break
            } catch (e: Exception) {
                if (isPlaying.get()) {
                    Log.e(TAG, "Error in playback loop", e)
                }
            }
        }
        // Ensure we set to false when loop exits
        _isActuallyPlaying.value = false
        _audioAmplitude.value = 0f
    }
    
    /**
     * Check if currently playing.
     */
    fun isPlaying(): Boolean = isPlaying.get()
    
    /**
     * Calculate RMS amplitude from PCM audio data (normalized 0-1)
     */
    private fun calculateRMSAmplitude(data: ByteArray): Float {
        if (data.isEmpty()) return 0f
        
        var sum = 0.0
        var count = 0
        
        // Process 16-bit PCM data
        for (i in 0 until data.size - 1 step 2) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toDouble()
            count++
        }
        
        if (count == 0) return 0f
        
        // RMS calculation
        val rms = sqrt(sum / count)
        
        // Normalize to 0-1 range (assuming 16-bit PCM max is 32768)
        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        
        // Apply some gain to make movement more visible
        return (normalized * 2.5f).coerceIn(0f, 1f)
    }
}
