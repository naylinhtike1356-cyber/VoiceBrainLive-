package com.example.voicebrainlive.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedRobotAvatar(status: VoiceStatus, modifier: Modifier = Modifier) {
    // Animations for eyes and aura
    val infiniteTransition = rememberInfiniteTransition(label = "robot_animation")

    val breathePulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    val speakPulse by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speak"
    )
    
    val processingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "processing"
    )

    val techRingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tech_ring"
    )

    // Smooth state transitions
    val targetEyeHeight = when (status) {
        VoiceStatus.LISTENING -> 28f
        VoiceStatus.SPEAKING -> 12f * speakPulse // Eyes bounce/pulse when speaking
        VoiceStatus.PROCESSING -> 18f
        VoiceStatus.IDLE -> 16f
    }
    
    val targetEyeWidth = when (status) {
        VoiceStatus.LISTENING -> 22f
        VoiceStatus.SPEAKING -> 26f
        VoiceStatus.PROCESSING -> 14f
        VoiceStatus.IDLE -> 16f
    }

    val eyeHeight by animateFloatAsState(targetValue = targetEyeHeight, animationSpec = tween(300))
    val eyeWidth by animateFloatAsState(targetValue = targetEyeWidth, animationSpec = tween(300))

    val eyeColor = when (status) {
        VoiceStatus.LISTENING -> Color(0xFF00E5FF) // Cyan
        VoiceStatus.SPEAKING -> Color(0xFFFF2A55) // Pinkish Red
        VoiceStatus.PROCESSING -> Color(0xFFFFD600) // Yellow
        VoiceStatus.IDLE -> Color(0xFF64748B) // Slate Gray
    }

    Canvas(modifier = modifier.size(64.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.width / 2.2f * if (status == VoiceStatus.IDLE || status == VoiceStatus.LISTENING) breathePulse else 1f

        // Draw 3D Sphere Body (Metallic/Glassy Look with darker advanced theme)
        val bodyGradient = Brush.radialGradient(
            colors = listOf(
                Color(0xFF334155), // Highlight
                Color(0xFF1E293B), // Mid tone
                Color(0xFF0F172A), // Shadow
                Color(0xFF020617)  // Deep shadow edge
            ),
            center = Offset(center.x * 0.6f, center.y * 0.5f),
            radius = radius * 1.3f
        )
        
        // Aura / Glow
        if (status != VoiceStatus.IDLE) {
            val auraRadius = radius * (if (status == VoiceStatus.SPEAKING) speakPulse else breathePulse) * 1.4f
            val auraBrush = Brush.radialGradient(
                colors = listOf(eyeColor.copy(alpha = 0.5f), Color.Transparent),
                center = center,
                radius = auraRadius
            )
            drawCircle(brush = auraBrush, radius = auraRadius, center = center)
        }

        // Tech Ring (Advanced futuristic detail)
        rotate(degrees = techRingRotation, pivot = center) {
            drawCircle(
                color = eyeColor.copy(alpha = 0.4f),
                radius = radius * 1.15f,
                center = center,
                style = Stroke(
                    width = 4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f, 5f, 10f))
                )
            )
            drawCircle(
                color = eyeColor.copy(alpha = 0.2f),
                radius = radius * 1.25f,
                center = center,
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(40f, 20f))
                )
            )
        }

        // Draw the robot sphere
        drawCircle(brush = bodyGradient, radius = radius, center = center)

        // Draw Eyes (Face)
        val eyeSpacing = radius * 0.40f
        val eyeYOffset = if (status == VoiceStatus.SPEAKING) (speakPulse - 1f) * 10f else 0f
        val eyeY = center.y - (radius * 0.1f) + eyeYOffset
        
        var leftEyeX = center.x - eyeSpacing
        var rightEyeX = center.x + eyeSpacing
        
        // Processing animation: eyes move side to side a bit
        if (status == VoiceStatus.PROCESSING) {
            val offset = kotlin.math.sin(processingRotation * Math.PI / 180).toFloat() * (radius * 0.2f)
            leftEyeX += offset
            rightEyeX += offset
        }
        
        val leftEyeCenter = Offset(leftEyeX, eyeY)
        val rightEyeCenter = Offset(rightEyeX, eyeY)
        
        val eyeGlowLeft = Brush.radialGradient(
            colors = listOf(eyeColor, eyeColor.copy(alpha = 0.2f), Color.Transparent),
            center = leftEyeCenter,
            radius = eyeWidth * 2.5f
        )
        val eyeGlowRight = Brush.radialGradient(
            colors = listOf(eyeColor, eyeColor.copy(alpha = 0.2f), Color.Transparent),
            center = rightEyeCenter,
            radius = eyeWidth * 2.5f
        )

        // Draw eye glows
        if (status != VoiceStatus.IDLE) {
            drawCircle(brush = eyeGlowLeft, radius = eyeWidth * 2.5f, center = leftEyeCenter)
            drawCircle(brush = eyeGlowRight, radius = eyeWidth * 2.5f, center = rightEyeCenter)
        }

        // Draw solid eyes (rounded rects for robotic look)
        val eyeSize = Size(eyeWidth, eyeHeight)
        val cornerRadius = CornerRadius(eyeWidth / 2, eyeWidth / 2)
        
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(leftEyeCenter.x - eyeWidth / 2, leftEyeCenter.y - eyeHeight / 2),
            size = eyeSize,
            cornerRadius = cornerRadius
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(rightEyeCenter.x - eyeWidth / 2, rightEyeCenter.y - eyeHeight / 2),
            size = eyeSize,
            cornerRadius = cornerRadius
        )

        // Draw Mouth
        val mouthWidth = eyeSpacing * 1.2f
        val baseMouthHeight = if (status == VoiceStatus.IDLE) 3f else 5f
        val mouthHeight = if (status == VoiceStatus.SPEAKING) 12f * speakPulse else baseMouthHeight
        val mouthY = center.y + (radius * 0.35f)
        val mouthCorner = CornerRadius(mouthHeight / 2, mouthHeight / 2)

        drawRoundRect(
            color = eyeColor,
            topLeft = Offset(center.x - mouthWidth / 2, mouthY - mouthHeight / 2),
            size = Size(mouthWidth, mouthHeight),
            cornerRadius = mouthCorner
        )
        
        // Add a subtle glass reflection on top of the sphere
        val reflectionGradient = Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.6f), Color.Transparent),
            start = Offset(center.x * 0.2f, center.y * 0.2f),
            end = Offset(center.x * 1.5f, center.y * 1.5f)
        )
        drawCircle(brush = reflectionGradient, radius = radius * 0.95f, center = center, style = Stroke(width = 2f))
    }
}
