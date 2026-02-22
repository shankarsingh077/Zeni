package com.zeni.voiceai.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Audio capture engine for recording voice input.
 * Produces 16kHz, 16-bit mono PCM audio frames.
 * 
 * OPTIMIZED: 60ms frames instead of 20ms for:
 * - 3x fewer WebSocket messages (17 vs 50 per second)
 * - Lower network overhead
 * - Better battery efficiency
 * - Same audio quality
 */
class AudioCaptureEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // OPTIMIZED: 60ms frames (was 20ms) - 3x fewer messages
        const val FRAME_SIZE_MS = 60
        const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 960 samples
        const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 1920 bytes
    }
    
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    
    // Audio Effects
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    
    private val audioChannel = Channel<ByteArray>(Channel.BUFFERED)
    
    /**
     * Flow of audio frames.
     */
    val audioFrames: Flow<ByteArray> = audioChannel.receiveAsFlow()
    
    /**
     * Check if recording permission is granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Initialize the audio recorder.
     */
    fun initialize(): Boolean {
        if (!hasPermission()) {
            Log.e(TAG, "Recording permission not granted")
            return false
        }
        
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            
            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid audio parameters")
                return false
            }
            
            // Use larger buffer for reliability
            val bufferSize = maxOf(minBufferSize, FRAME_SIZE_BYTES * 4)
            
            // Use MIC for raw input, then apply effects explicitly
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            // Enable Noise Suppression if available
            val sessionId = audioRecord!!.audioSessionId
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.i(TAG, "NoiseSuppressor enabled")
            } else {
                Log.w(TAG, "NoiseSuppressor not available")
            }
            
            // Enable Acoustic Echo Cancellation if available
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
                Log.i(TAG, "AcousticEchoCanceler enabled")
            } else {
                Log.w(TAG, "AcousticEchoCanceler not available")
            }
            
            Log.d(TAG, "AudioRecord initialized: bufferSize=$bufferSize on session $sessionId")
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception initializing AudioRecord", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing AudioRecord", e)
            return false
        }
    }
    
    /**
     * Start recording audio.
     */
    fun startRecording(scope: CoroutineScope): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return true
        }
        
        if (audioRecord == null && !initialize()) {
            return false
        }
        
        try {
            audioRecord?.startRecording()
            isRecording = true
            
            recordingJob = scope.launch(Dispatchers.IO) {
                recordLoop()
            }
            
            Log.d(TAG, "Recording started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            isRecording = false
            return false
        }
    }
    
    /**
     * Stop recording audio.
     */
    fun stopRecording() {
        if (!isRecording) {
            return
        }
        
        isRecording = false
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        
        recordingJob?.cancel()
        recordingJob = null
        
        Log.d(TAG, "Recording stopped")
    }
    
    /**
     * Release resources.
     */
    fun release() {
        stopRecording()
        
        try {
            noiseSuppressor?.release()
            echoCanceler?.release()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        
        noiseSuppressor = null
        echoCanceler = null
        audioRecord = null
        audioChannel.close()
        
        Log.d(TAG, "AudioCapture released")
    }
    
    /**
     * Main recording loop.
     */
    private suspend fun recordLoop() {
        val buffer = ByteArray(FRAME_SIZE_BYTES)
        
        while (isRecording && !audioChannel.isClosedForSend) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, FRAME_SIZE_BYTES) ?: -1
                
                if (bytesRead > 0) {
                    // Copy buffer to avoid mutation
                    val frame = buffer.copyOf(bytesRead)
                    audioChannel.trySend(frame)
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Error reading audio: $bytesRead")
                    break
                }
            } catch (e: Exception) {
                if (isRecording) {
                    Log.e(TAG, "Error in record loop", e)
                }
                break
            }
        }
    }
}
