package com.zeni.voiceai.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Helper class for managing spring-based animations for natural movement.
 */
class SpringValue(
    initialValue: Float,
    private val stiffness: Float = Spring.StiffnessLow,
    private val dampingRatio: Float = Spring.DampingRatioLowBouncy,
    private val visibilityThreshold: Float = 0.001f
) {
    private val animatable = Animatable(initialValue)

    val value: Float
        get() = animatable.value

    suspend fun animateTo(target: Float) {
        animatable.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = dampingRatio,
                stiffness = stiffness,
                visibilityThreshold = visibilityThreshold
            )
        )
    }

    suspend fun snapTo(target: Float) {
        animatable.snapTo(target)
    }
}
