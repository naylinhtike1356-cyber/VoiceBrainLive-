package com.example.voicebrainlive.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.voicebrainlive.AssistantActivity.VoiceStatus
import kotlin.math.sin

/**
 * Ultra-compact, text-free soundbar positioned directly beneath the selfie camera cutout.
 * Features 5 vertical equalizer sound bars reacting in real time to voice amplitude and status colors.
 *
 * Colors:
 * - LISTENING: Neon Cyan / Blue
 * - SPEAKING: Electric Violet / Purple Aurora
 * - PROCESSING: Amber Gold / Radiant Flame
 * - IDLE: Emerald Teal
 */
@Composable
fun CameraNotchSoundBar(
    voiceStatus: VoiceStatus,
    audioAmplitude: Float,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {}
) {
    val targetColors = when (voiceStatus) {
        VoiceStatus.LISTENING -> listOf(Color(0xFF00F2FE), Color(0xFF06B6D4))
        VoiceStatus.SPEAKING -> listOf(Color(0xFFD946EF), Color(0xFFA855F7))
        VoiceStatus.PROCESSING -> listOf(Color(0xFFFBBF24), Color(0xFFF59E0B))
        VoiceStatus.IDLE -> listOf(Color(0xFF34D399), Color(0xFF059669))
    }

    val primaryColor by animateColorAsState(
        targetValue = targetColors[0],
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "primaryColor"
    )
    val secondaryColor by animateColorAsState(
        targetValue = targetColors[1],
        animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing),
        label = "secondaryColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "notchWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .padding(top = 4.dp, bottom = 4.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(50),
                spotColor = primaryColor.copy(alpha = 0.65f),
                ambientColor = primaryColor.copy(alpha = 0.35f)
            )
            .clip(RoundedCornerShape(50))
            .background(Color(0xEE0B1020))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(listOf(primaryColor.copy(alpha = 0.8f), secondaryColor.copy(alpha = 0.8f))),
                shape = RoundedCornerShape(50)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onTap() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(24.dp)
        ) {
            val barMultipliers = listOf(0.45f, 0.85f, 1.35f, 0.85f, 0.45f)
            val barOffsets = listOf(0f, 1.2f, 2.4f, 3.6f, 4.8f)

            for (i in 0 until 5) {
                val rhythm = (sin(wavePhase + barOffsets[i]) * 0.5f + 0.5f).coerceIn(0f, 1f)

                val barHeightDp = when (voiceStatus) {
                    VoiceStatus.LISTENING -> {
                        val ampScale = (audioAmplitude * 2.5f).coerceIn(0.1f, 1.0f)
                        val dynamicH = 5f + (17f * barMultipliers[i] * ampScale * (0.6f + 0.4f * rhythm))
                        dynamicH.coerceIn(4f, 24f).dp
                    }
                    VoiceStatus.SPEAKING -> {
                        val dynamicH = 6f + (16f * barMultipliers[i] * rhythm)
                        dynamicH.coerceIn(5f, 23f).dp
                    }
                    VoiceStatus.PROCESSING -> {
                        val pulse = (sin(wavePhase * 2f + i * 0.8f) * 0.5f + 0.5f)
                        (6f + 12f * pulse).dp
                    }
                    VoiceStatus.IDLE -> {
                        (4f + 3f * rhythm).dp
                    }
                }

                Box(
                    modifier = Modifier
                        .width(3.2.dp)
                        .height(barHeightDp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(primaryColor, secondaryColor)
                            )
                        )
                )
            }
        }
    }
}
