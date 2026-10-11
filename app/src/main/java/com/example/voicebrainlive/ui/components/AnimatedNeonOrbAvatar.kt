package com.example.voicebrainlive.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedNeonOrbAvatar(status: VoiceStatus, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "neon_orb_animation")

    // Gentle breathing pulse
    val breathePulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathePulse"
    )

    // Speaking pulse
    val speakPulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(280, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speakPulse"
    )

    // Orbital ring rotation
    val orbitRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (status) {
                    VoiceStatus.PROCESSING -> 1200
                    VoiceStatus.SPEAKING -> 2500
                    else -> 6000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbitRotation"
    )

    // Inner core glow wave
    val coreGlow by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "coreGlow"
    )

    // Dynamic color palettes based on VoiceStatus
    val (primaryGlow, secondaryGlow, accentColor) = when (status) {
        VoiceStatus.SPEAKING -> Triple(
            Color(0xFF00E5FF),
            Color(0xFF3B82F6),
            Color(0xFF60A5FA)
        )
        VoiceStatus.LISTENING -> Triple(
            Color(0xFF10B981),
            Color(0xFF06B6D4),
            Color(0xFF34D399)
        )
        VoiceStatus.PROCESSING -> Triple(
            Color(0xFFA855F7),
            Color(0xFFEC4899),
            Color(0xFFC084FC)
        )
        else -> Triple(
            Color(0xFF38BDF8),
            Color(0xFF818CF8),
            Color(0xFFE0F2FE)
        )
    }

    Canvas(modifier = modifier.size(56.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension / 2f * 0.72f
        val pulse = when (status) {
            VoiceStatus.SPEAKING -> speakPulse
            VoiceStatus.LISTENING -> breathePulse * 1.05f
            VoiceStatus.PROCESSING -> 1.0f
            else -> breathePulse
        }
        val currentRadius = baseRadius * pulse

        // 1. Outermost soft aura bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryGlow.copy(alpha = 0.35f * coreGlow),
                    secondaryGlow.copy(alpha = 0.12f * coreGlow),
                    Color.Transparent
                ),
                center = center,
                radius = currentRadius * 1.55f
            ),
            radius = currentRadius * 1.55f,
            center = center
        )

        // 2. Rotating orbital tech ring
        rotate(orbitRotation, pivot = center) {
            drawCircle(
                color = accentColor.copy(alpha = 0.65f),
                radius = currentRadius * 1.18f,
                center = center,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 14f), 0f)
                )
            )
            // Small satellite beacon dot
            val dotOffset = Offset(
                x = center.x + currentRadius * 1.18f,
                y = center.y
            )
            drawCircle(
                color = Color.White,
                radius = 2.5.dp.toPx(),
                center = dotOffset
            )
        }

        // 3. Counter-rotating inner faint ring
        rotate(-orbitRotation * 0.7f, pivot = center) {
            drawCircle(
                color = secondaryGlow.copy(alpha = 0.4f),
                radius = currentRadius * 1.05f,
                center = center,
                style = Stroke(
                    width = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 16f), 4f)
                )
            )
        }

        // 4. Core glass sphere body
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.9f),
                    primaryGlow.copy(alpha = 0.85f),
                    secondaryGlow.copy(alpha = 0.95f),
                    Color(0xFF0F172A)
                ),
                center = Offset(center.x - currentRadius * 0.25f, center.y - currentRadius * 0.25f),
                radius = currentRadius
            ),
            radius = currentRadius,
            center = center
        )

        // 5. Glossy highlight reflection (top-left glass sheen)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.85f),
                    Color.White.copy(alpha = 0.0f)
                ),
                center = Offset(center.x - currentRadius * 0.35f, center.y - currentRadius * 0.35f),
                radius = currentRadius * 0.45f
            ),
            radius = currentRadius * 0.45f,
            center = Offset(center.x - currentRadius * 0.35f, center.y - currentRadius * 0.35f)
        )

        // 6. Central pulsating quantum iris
        drawCircle(
            color = Color.White.copy(alpha = 0.9f * coreGlow),
            radius = 3.dp.toPx() * (if (status == VoiceStatus.SPEAKING) speakPulse else 1f),
            center = center
        )
    }
}
