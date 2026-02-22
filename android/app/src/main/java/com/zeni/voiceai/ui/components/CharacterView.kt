package com.zeni.voiceai.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.dp
import com.zeni.voiceai.protocol.SessionState
import com.zeni.voiceai.ui.motion.*
import com.zeni.voiceai.ui.theme.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * Handsome Full-Screen Character.
 * 
 * Features:
 * - Full Screen Portrait (Shoulders off-screen)
 * - Diamond Face Shape
 * - "Flow Middle Part" Hairstyle (Curtain Bangs)
 * - Detailed "Handsome" Features
 */
@Composable
fun CharacterView(
    sessionState: SessionState,
    audioAmplitude: StateFlow<Float>,
    modifier: Modifier = Modifier
) {
    val amplitude by audioAmplitude.collectAsState(initial = 0f)
    val scope = rememberCoroutineScope()
    
    // Physics
    val headRotationX = remember { SpringValue(0f) }
    val headRotationY = remember { SpringValue(0f) }
    val eyeGazeX = remember { SpringValue(0f) }
    val eyeGazeY = remember { SpringValue(0f) }
    val eyeOpenness = remember { SpringValue(1f, Spring.StiffnessHigh) }
    val mouthOpenness = remember { SpringValue(0f, Spring.StiffnessMedium) }
    val breathScale = remember { Animatable(1f) }
    
    // Autonomous Behavior
    val randomActor = remember { 
        RandomActor(
            scope = scope,
            onBlink = { progress -> scope.launch { eyeOpenness.snapTo(1f - progress) } },
            onGazeShift = { x, y ->
                if (sessionState != SessionState.LISTENING) {
                    scope.launch {
                        eyeGazeX.animateTo(x)
                        eyeGazeY.animateTo(y)
                    }
                }
            },
            onHeadMove = { x, y ->
                scope.launch {
                    headRotationX.animateTo(x) // Tilt
                    headRotationY.animateTo(y) // Turn
                }
            }
        )
    }
    
    LaunchedEffect(Unit) { 
        randomActor.start() 
        // Breathing animation
        while(true) {
            breathScale.animateTo(1.02f, animationSpec = tween(3000, easing = LinearEasing))
            breathScale.animateTo(1.0f, animationSpec = tween(3000, easing = LinearEasing))
        }
    }
    
    // Reactions
    LaunchedEffect(sessionState) {
        when (sessionState) {
            SessionState.LISTENING -> {
                launch { eyeGazeX.animateTo(0f); eyeGazeY.animateTo(0f) }
                launch { headRotationX.animateTo(0.05f) }
            }
            SessionState.GENERATING -> {
                launch { eyeGazeX.animateTo(0.2f); eyeGazeY.animateTo(-0.2f) }
                launch { headRotationY.animateTo(0.1f) }
            }
            SessionState.SPEAKING -> {
                launch { eyeGazeX.animateTo(0f); eyeGazeY.animateTo(0f) }
            }
            else -> {}
        }
    }
    
    LaunchedEffect(amplitude) {
        val targetOpen = if (amplitude.compareTo(0.02f) > 0) amplitude.coerceIn(0f, 1f) * 0.6f else 0f
        mouthOpenness.animateTo(targetOpen)
    }
    
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // MAXIMIZE SCALE: Fit width to screen to ensure full immersion
            val scale = (w / 320f) * breathScale.value
            
            val cx = w / 2f
            val cy = h / 2f - 40f * scale // Shift face up slightly to fit shoulders
            
            // Physics offsets
            val turnX = headRotationY.value * 30f * scale
            val tiltY = headRotationX.value * 20f * scale
            val faceCX = cx + turnX
            val faceCY = cy + tiltY
            
            // 0. Dynamic Background (No Black Void)
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF1A237E), Color.Black),
                    center = Offset(cx, cy),
                    radius = w * 1.5f
                )
            )
            
            // 1. Shoulders / Body (Filling bottom)
            drawShoulders(cx, faceCY, scale, w, h)
            
            // 2. Neck
            drawNeck(cx, faceCY, scale)
            
            // 3. Rear Hair (Volume)
            drawHairBack(cx, faceCY, scale)
            
            // 4. Ears
            drawEars(faceCX, faceCY, scale)
            
            // 5. Diamond Face Shape
            drawDiamondFace(faceCX, faceCY, scale)
            
            // 6. Features
            val featureParallax = turnX * 1.1f
            
            // Brows
            drawBrows(faceCX + featureParallax, faceCY - 50f * scale, scale)
            
            // Eyes
            val eyeSp = 50f * scale
            val eyeY = faceCY - 20f * scale
            drawHunterEye(faceCX - eyeSp + featureParallax, eyeY, scale, eyeOpenness.value, eyeGazeX.value, eyeGazeY.value, true)
            drawHunterEye(faceCX + eyeSp + featureParallax, eyeY, scale, eyeOpenness.value, eyeGazeX.value, eyeGazeY.value, false)
            
            // Nose (Strong Bridge)
            drawNose(faceCX + featureParallax * 0.6f, faceCY + 40f * scale, scale)
            
            // Lips
            drawLips(faceCX + featureParallax * 0.8f, faceCY + 95f * scale, scale, mouthOpenness.value)
            
            // 7. Hair: Flow Middle Part (Curtain Bangs)
            drawMiddlePartHair(faceCX, faceCY - 120f * scale, scale)
        }
    }
}

// ================= Drawing Functions =================

private fun DrawScope.drawShoulders(cx: Float, faceCY: Float, scale: Float, w: Float, h: Float) {
    // Broad shoulders extending off-screen
    val shoulderTop = faceCY + 220f * scale
    
    val path = Path().apply {
        moveTo(cx, shoulderTop)
        // Slope down to edges
        lineTo(0f, h)
        lineTo(w, h)
        lineTo(cx, shoulderTop)
    }
    
    // Shirt/Body Color
    drawPath(path, Color(0xFF263238)) // Dark premium slate
    
    // Neck Collar shadow
    drawArc(
        color = Color.Black.copy(alpha=0.3f),
        startAngle = 0f, 
        sweepAngle = 180f, 
        useCenter = true,
        topLeft = Offset(cx - 70f*scale, shoulderTop - 10f*scale),
        size = Size(140f*scale, 40f*scale)
    )
}

private fun DrawScope.drawNeck(cx: Float, cy: Float, scale: Float) {
    val w = 110f * scale
    drawRect(
        color = SkinShadow,
        topLeft = Offset(cx - w/2, cy + 140f * scale),
        size = Size(w, 120f * scale)
    )
    // Adams apple shadow
    drawCircle(
        SkinDeepShadow.copy(alpha=0.2f),
        radius = 15f*scale,
        center = Offset(cx, cy + 190f*scale)
    )
}

private fun DrawScope.drawDiamondFace(cx: Float, cy: Float, scale: Float) {
    // Diamond: Narrow chin, Wide Cheekbones, Narrow Forehead
    val path = Path().apply {
        moveTo(cx - 130f * scale, cy - 80f * scale) // Temple L
        cubicTo(
            cx - 150f * scale, cy + 30f * scale, // Cheekbone L (Wide)
            cx - 100f * scale, cy + 180f * scale, // Jaw L (Sharp)
            cx, cy + 240f * scale // Chin (Pointed)
        )
        cubicTo(
            cx + 100f * scale, cy + 180f * scale, // Jaw R
            cx + 150f * scale, cy + 30f * scale, // Cheekbone R
            cx + 130f * scale, cy - 80f * scale // Temple R
        )
        // Forehead
        cubicTo(cx + 80f * scale, cy - 200f * scale, cx - 80f * scale, cy - 200f * scale, cx - 130f * scale, cy - 80f * scale)
        close()
    }
    
    drawPath(
        path = path,
        brush = Brush.radialGradient(
            colors = listOf(SkinHighlight, SkinBase, SkinShadow),
            center = Offset(cx, cy),
            radius = 300f * scale
        )
    )
}

private fun DrawScope.drawHunterEye(cx: Float, cy: Float, scale: Float, open: Float, gx: Float, gy: Float, isLeft: Boolean) {
    val w = 60f * scale
    val h = 25f * scale * open
    
    if (h < 2f) { // Blink
        drawLine(Color(0xFF212121), Offset(cx - w/2, cy), Offset(cx + w/2, cy + 4f*scale), strokeWidth = 3f * scale)
        return
    }
    
    // Sharp Almond Shape
    val eyePath = Path().apply {
        moveTo(cx - w/2, cy + 2f * scale) // Inner
        quadraticBezierTo(cx, cy - h, cx + w/2 + 5f*scale, cy - 2f * scale) // Outer (higher)
        quadraticBezierTo(cx, cy + h, cx - w/2, cy + 2f * scale)
    }
    
    clipPath(eyePath) {
        drawRect(Color(0xFFF0F0F0), topLeft = Offset(cx - w, cy - h), size = Size(w*2, h*2))
        
        // Iris
        val px = cx + gx * 12f * scale
        val py = cy + gy * 12f * scale
        val ir = 14f * scale
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF42A5F5), Color(0xFF1565C0), Color.Black), // Blue eyes
                center = Offset(px, py),
                radius = ir
            ),
            radius = ir, center = Offset(px, py)
        )
        drawCircle(Color.Black, radius = 5f*scale, center = Offset(px, py))
        drawCircle(Color.White, radius = 3f*scale, center = Offset(px - 4f*scale, py - 4f*scale))
    }
    
    // Thick Lash Line
    drawPath(eyePath, Color(0xFF101010), style = Stroke(width=3f*scale))
}

private fun DrawScope.drawBrows(cx: Float, cy: Float, scale: Float) {
    val w = 70f * scale
    // Left Brow (Straight & Angled)
    val leftPath = Path().apply {
        moveTo(cx - 30f*scale, cy)
        lineTo(cx - 30f*scale - w, cy - 10f*scale)
        lineTo(cx - 30f*scale - w, cy - 25f*scale)
        lineTo(cx - 30f*scale, cy - 12f*scale)
        close()
    }
    drawPath(leftPath, Color(0xFF263238))

    // Right Brow
    val rightPath = Path().apply {
        moveTo(cx + 30f*scale, cy)
        lineTo(cx + 30f*scale + w, cy - 10f*scale)
        lineTo(cx + 30f*scale + w, cy - 25f*scale)
        lineTo(cx + 30f*scale, cy - 12f*scale)
        close()
    }
    drawPath(rightPath, Color(0xFF263238))
}

private fun DrawScope.drawNose(cx: Float, cy: Float, scale: Float) {
    // Strong, straight nose
    val h = 60f * scale
    
    // Bridge shadow
    drawLine(
        SkinShadow,
        Offset(cx - 8f*scale, cy - h),
        Offset(cx - 10f*scale, cy + h - 10f*scale),
        strokeWidth = 4f * scale,
        cap = StrokeCap.Round
    )
    
    // Tip
    drawCircle(SkinMid, radius = 12f*scale, center = Offset(cx, cy + h))
    
    // Nostrils
    drawCircle(SkinDeepShadow, radius = 4f*scale, center = Offset(cx - 12f*scale, cy + h + 5f*scale))
    drawCircle(SkinDeepShadow, radius = 4f*scale, center = Offset(cx + 12f*scale, cy + h + 5f*scale))
}

private fun DrawScope.drawLips(cx: Float, cy: Float, scale: Float, open: Float) {
    val w = 50f * scale
    val h = 15f * scale + open * 15f * scale
    
    // Upper lip (Defined Cupid's Bow)
    val upper = Path().apply {
        moveTo(cx - w, cy)
        quadraticBezierTo(cx - w/2, cy - 10f*scale, cx, cy + 2f*scale)
        quadraticBezierTo(cx + w/2, cy - 10f*scale, cx + w, cy)
        lineTo(cx, cy + 5f*scale)
        close()
    }
    drawPath(upper, LipShadow)
    
    // Lower
    val lower = Path().apply {
        moveTo(cx - w, cy)
        quadraticBezierTo(cx, cy + h + 15f*scale, cx + w, cy)
        close()
    }
    drawPath(lower, LipBase)
}

private fun DrawScope.drawHairBack(cx: Float, cy: Float, scale: Float) {
    drawCircle(Color(0xFF212121), radius = 190f*scale, center = Offset(cx, cy - 50f*scale))
}

private fun DrawScope.drawMiddlePartHair(cx: Float, cy: Float, scale: Float) {
    val color = Color(0xFF212121)
    
    // Left Curtain
    val leftPath = Path().apply {
        moveTo(cx, cy - 80f*scale) // Part start (top)
        quadraticBezierTo(cx - 80f*scale, cy + 50f*scale, cx - 160f*scale, cy + 150f*scale) // Outsweep
        quadraticBezierTo(cx - 120f*scale, cy + 100f*scale, cx - 60f*scale, cy - 20f*scale) // Inner volume
        close()
    }
    drawPath(leftPath, color)
    
    // Right Curtain
    val rightPath = Path().apply {
        moveTo(cx, cy - 80f*scale)
        quadraticBezierTo(cx + 80f*scale, cy + 50f*scale, cx + 160f*scale, cy + 150f*scale)
        quadraticBezierTo(cx + 120f*scale, cy + 100f*scale, cx + 60f*scale, cy - 20f*scale)
        close()
    }
    drawPath(rightPath, color)
}

private fun DrawScope.drawEars(cx: Float, cy: Float, scale: Float) {
    drawOval(SkinShadow, topLeft = Offset(cx - 150f*scale, cy), size = Size(30f*scale, 60f*scale))
    drawOval(SkinShadow, topLeft = Offset(cx + 120f*scale, cy), size = Size(30f*scale, 60f*scale))
}
