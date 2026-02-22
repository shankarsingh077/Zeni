package com.zeni.voiceai.ui.motion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Manages autonomous behaviors like blinking and random gaze shifts.
 */
class RandomActor(
    private val scope: CoroutineScope,
    private val onBlink: (Float) -> Unit,
    private val onGazeShift: (Float, Float) -> Unit,
    private val onHeadMove: (Float, Float) -> Unit
) {
    private var blinkJob: Job? = null
    private var saccadeJob: Job? = null
    private var microMoveJob: Job? = null

    fun start() {
        startBlinking()
        startSaccades() // Eye darts
        startMicroMovements() // Subtle head drift
    }

    fun stop() {
        blinkJob?.cancel()
        saccadeJob?.cancel()
        microMoveJob?.cancel()
    }

    private fun startBlinking() {
        blinkJob = scope.launch {
            while (isActive) {
                // Random time between blinks (human average 2-6s, sometimes double blinks)
                delay(Random.nextLong(2000, 6000))

                // Perform blink
                val duration = 150L // Fast blink
                val steps = 10
                
                // Close
                for (i in 0..steps) {
                    onBlink(i / steps.toFloat())
                    delay(duration / (steps * 2))
                }
                // Open
                for (i in steps downTo 0) {
                    onBlink(i / steps.toFloat())
                    delay(duration / (steps * 2))
                }
                
                // Occasional double blink (10% chance)
                if (Random.nextFloat() < 0.1f) {
                    delay(50)
                    // Quick second blink
                     for (i in 0..steps) {
                        onBlink(i / steps.toFloat())
                        delay(5)
                    }
                    for (i in steps downTo 0) {
                        onBlink(i / steps.toFloat())
                        delay(5)
                    }
                }
            }
        }
    }

    private fun startSaccades() {
        saccadeJob = scope.launch {
            while (isActive) {
                // Eyes don't stay perfectly still
                delay(Random.nextLong(500, 3000))
                
                // Small random movements around the current focal point
                val driftX = Random.nextFloat() * 0.2f - 0.1f
                val driftY = Random.nextFloat() * 0.2f - 0.1f
                
                onGazeShift(driftX, driftY)
            }
        }
    }
    
    private fun startMicroMovements() {
        microMoveJob = scope.launch {
            while (isActive) {
                delay(16) // ~60fps update
                val time = System.currentTimeMillis() / 1000f
                
                // Perlin-like noise for head drift (using simple sine composition)
                val driftX = (Math.sin(time * 0.5) * 0.05 + Math.sin(time * 0.3) * 0.03).toFloat()
                val driftY = (Math.cos(time * 0.4) * 0.05 + Math.cos(time * 0.2) * 0.03).toFloat()
                
                onHeadMove(driftX, driftY)
            }
        }
    }
}
