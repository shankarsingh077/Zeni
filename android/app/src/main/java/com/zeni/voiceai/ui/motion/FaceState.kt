package com.zeni.voiceai.ui.motion

data class FaceState(
    // Eyes
    val eyeOpennessLeft: Float = 1f,   // 0f (closed) to 1f (open)
    val eyeOpennessRight: Float = 1f,
    val gazeX: Float = 0f,             // -1f (left) to 1f (right)
    val gazeY: Float = 0f,             // -1f (up) to 1f (down)
    val blinkProgress: Float = 0f,     // 0f (open) to 1f (closed)
    
    // Head
    val headRotationX: Float = 0f,     // Standard coordinate system
    val headRotationY: Float = 0f,
    val headTilt: Float = 0f,
    
    // Mouth
    val mouthWidth: Float = 1f,        // Scale factor
    val mouthOpenness: Float = 0f,     // 0f to 1f based on amplitude
    val smile: Float = 0f              // 0f (neutral) to 1f (smile)
)

enum class Emotion {
    NEUTRAL,
    HAPPY,
    THINKING,
    LISTENING,
    SPEAKING,
    CONFUSED,
    SURPRISED
}
