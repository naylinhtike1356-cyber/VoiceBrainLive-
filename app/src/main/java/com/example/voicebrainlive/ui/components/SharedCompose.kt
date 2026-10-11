package com.example.voicebrainlive.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class VoiceStatus { IDLE, LISTENING, SPEAKING, PROCESSING }

@Composable
fun AssistantFluidOrb(status: VoiceStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    val scale by animateFloatAsState(
        targetValue = when (status) {
            VoiceStatus.LISTENING -> 1.3f
            VoiceStatus.SPEAKING -> 1.6f
            VoiceStatus.PROCESSING -> 1.1f
            else -> 1.0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )

    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val colorList = when (status) {
        VoiceStatus.LISTENING -> listOf(Color(0xFF00E5FF), Color(0xFF00B0FF)) // Neon Cyan
        VoiceStatus.SPEAKING -> listOf(Color(0xFFFF007F), Color(0xFFD500F9)) // Vivid Magenta
        VoiceStatus.PROCESSING -> listOf(Color(0xFFFFEA00), Color(0xFFFF9100)) // Amber
        else -> listOf(Color(0xFF00E5FF).copy(alpha = 0.5f), Color(0xFF7F00FF).copy(alpha = 0.5f)) // Idle
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(240.dp)
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .size(200.dp * scale * breathe)
                .blur(40.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(colorList[0].copy(alpha = pulseAlpha), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )
        // Inner core
        Box(
            modifier = Modifier
                .size(100.dp * scale * breathe)
                .background(
                    brush = Brush.linearGradient(colors = colorList),
                    shape = CircleShape
                )
        )
    }
}
