package com.zeni.voiceai.audio

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Simple Voice Activity Detection (VAD) for detecting speech.
 * Uses energy-based detection with smoothing.
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 320
) {
    
    companion object {
        private const val TAG = "VAD"
        
        // VAD parameters
        private const val SILENCE_THRESHOLD_DB = -40.0f
        private const val SPEECH_THRESHOLD_DB = -35.0f
        private const val SILENCE_DURATION_MS = 500L
        private const val MIN_SPEECH_DURATION_MS = 200L
        
        // Smoothing
        private const val SMOOTHING_FACTOR = 0.3f
    }
    
    /**
     * VAD state.
     */
    enum class State {
        SILENCE,
        SPEECH,
        SPEECH_END
    }
    
    private var currentState = State.SILENCE
    private var smoothedEnergy = 0.0f
    private var speechStartTime = 0L
    private var silenceStartTime = 0L
    
    /**
     * Process audio frame and return VAD state.
     */
    fun processFrame(audioData: ByteArray): State {
        val energy = calculateEnergy(audioData)
        val energyDb = 20 * kotlin.math.log10(energy.coerceAtLeast(1e-10f))
        
        // Smooth energy
        smoothedEnergy = smoothedEnergy * (1 - SMOOTHING_FACTOR) + energy * SMOOTHING_FACTOR
        
        val currentTime = System.currentTimeMillis()
        
        return when (currentState) {
            State.SILENCE -> {
                if (energyDb > SPEECH_THRESHOLD_DB) {
                    speechStartTime = currentTime
                    currentState = State.SPEECH
                    Log.d(TAG, "Speech detected")
                }
                currentState
            }
            
            State.SPEECH -> {
                if (energyDb < SILENCE_THRESHOLD_DB) {
                    if (silenceStartTime == 0L) {
                        silenceStartTime = currentTime
                    } else if (currentTime - silenceStartTime > SILENCE_DURATION_MS) {
                        // Check minimum speech duration
                        if (currentTime - speechStartTime > MIN_SPEECH_DURATION_MS) {
                            currentState = State.SPEECH_END
                            Log.d(TAG, "Speech ended")
                        } else {
                            // Too short, treat as noise
                            currentState = State.SILENCE
                            Log.d(TAG, "Short noise ignored")
                        }
                        silenceStartTime = 0L
                    }
                } else {
                    silenceStartTime = 0L
                }
                currentState
            }
            
            State.SPEECH_END -> {
                // Reset to silence after speech end is processed
                currentState = State.SILENCE
                silenceStartTime = 0L
                speechStartTime = 0L
                State.SPEECH_END
            }
        }
    }
    
    /**
     * Reset VAD state.
     */
    fun reset() {
        currentState = State.SILENCE
        smoothedEnergy = 0.0f
        speechStartTime = 0L
        silenceStartTime = 0L
    }
    
    /**
     * Check if currently in speech.
     */
    fun isSpeaking(): Boolean = currentState == State.SPEECH
    
    /**
     * Calculate RMS energy of audio samples.
     */
    private fun calculateEnergy(audioData: ByteArray): Float {
        if (audioData.size < 2) return 0f
        
        var sumSquares = 0.0
        val numSamples = audioData.size / 2
        
        for (i in 0 until numSamples) {
            // Convert bytes to 16-bit sample (little-endian)
            val low = audioData[i * 2].toInt() and 0xFF
            val high = audioData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            
            // Normalize to [-1, 1]
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
        }
        
        return sqrt(sumSquares / numSamples).toFloat()
    }
    
    /**
     * Get current VAD state.
     */
    fun getCurrentState(): State = currentState
}
