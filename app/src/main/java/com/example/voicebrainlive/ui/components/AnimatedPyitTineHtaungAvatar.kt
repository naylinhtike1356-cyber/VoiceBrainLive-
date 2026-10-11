package com.example.voicebrainlive.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun AnimatedPyitTineHtaungAvatar(status: VoiceStatus, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pyit_tine_htaung")

    // Rocking animation
    val rockAngle by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (status) {
                    VoiceStatus.SPEAKING -> 400
                    VoiceStatus.PROCESSING -> 300
                    else -> 1000
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rocking"
    )

    // Breathing animation
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Floating animation
    val floatOffset by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floating"
    )

    // Speaking Bounce (Body)
    val speakBounce by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speakBounce"
    )
    
    // Speaking Mouth Open/Close
    val speakMouth by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speakMouth"
    )

    // Processing Eye Dart
    val eyeDart by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eyeDart"
    )
    
    // Eye Blinking Animation
    val eyeBlink by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4000
                1f at 0
                1f at 3800 // Stay open
                0.1f at 3900 // Close quickly
                1f at 4000 // Open quickly
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "eyeBlink"
    )

    // Thanaka Glow
    val thanakaGlow by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thanakaGlow"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1.0f)
                rotationZ = when (status) {
                    VoiceStatus.IDLE -> rockAngle * 0.15f
                    VoiceStatus.LISTENING -> rockAngle * 0.4f
                    VoiceStatus.PROCESSING -> rockAngle * 1.5f
                    VoiceStatus.SPEAKING -> rockAngle
                }
                val currentScale = when (status) {
                    VoiceStatus.IDLE, VoiceStatus.LISTENING -> breatheScale
                    VoiceStatus.SPEAKING -> speakBounce
                    else -> 1f
                }
                scaleX = currentScale
                scaleY = currentScale
                translationY = if (status == VoiceStatus.PROCESSING) floatOffset else 0f
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f

            // Colors
            val goldColor = Color(0xFFFFC107) 
            val redColor = Color(0xFFE53935)  
            val darkRed = Color(0xFFB71C1C)   
            val tealColor = Color(0xFF00897B) 
            val skinColor = Color(0xFFFFCC80) 
            
            // 0. Draw Floating Drop Shadow (Floor Shadow)
            val shadowWidth = w * 0.5f
            val shadowHeight = w * 0.1f
            val shadowYOffset = h * 0.98f + (if (status == VoiceStatus.PROCESSING) floatOffset * 2 else 0f)
            drawOval(
                color = Color.Black.copy(alpha = 0.2f),
                topLeft = Offset(centerX - shadowWidth / 2f, shadowYOffset),
                size = Size(shadowWidth, shadowHeight)
            )

            // 1. Draw Top Knot (ဆံထုံး) with 3D Highlight
            val hairGradient = Brush.radialGradient(
                colors = listOf(Color(0xFF757575), Color.Black, Color.Black),
                center = Offset(centerX - w * 0.04f, h * 0.12f), // Highlight on top-left
                radius = w * 0.12f
            )
            drawCircle(
                brush = hairGradient,
                radius = w * 0.12f,
                center = Offset(centerX, h * 0.15f)
            )

            // 2. Draw Egg-shaped Body (Pyit Tine Htaung base)
            val eggPath = Path().apply {
                moveTo(centerX, h * 0.1f) 
                cubicTo(
                    w * 0.95f, h * 0.1f,  
                    w * 1.1f, h * 0.95f,  
                    centerX, h * 0.95f    
                )
                cubicTo(
                    -w * 0.1f, h * 0.95f, 
                    w * 0.05f, h * 0.1f,  
                    centerX, h * 0.1f     
                )
            }
            
            // Body Gradient (3D Shading effect)
            val bodyGradient = Brush.radialGradient(
                colors = listOf(Color(0xFFFF8A80), redColor, darkRed, Color(0xFF5D0000)), // Bright red highlight -> deep dark edge
                center = Offset(centerX - w * 0.15f, h * 0.3f), // Light source from top-left
                radius = w * 0.9f
            )
            drawPath(path = eggPath, brush = bodyGradient)

            // 3. Draw Dress Patterns (Teal and Gold arcs) with subtle drop shadows
            drawArc(
                color = Color.Black.copy(alpha = 0.3f), // Shadow
                startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(w * 0.15f, h * 0.705f), size = Size(w * 0.7f, h * 0.4f),
                style = Stroke(width = w * 0.08f)
            )
            drawArc(
                color = tealColor,
                startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(w * 0.15f, h * 0.7f), size = Size(w * 0.7f, h * 0.4f),
                style = Stroke(width = w * 0.08f)
            )
            
            drawArc(
                color = Color.Black.copy(alpha = 0.3f), // Shadow
                startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(w * 0.2f, h * 0.755f), size = Size(w * 0.6f, h * 0.35f),
                style = Stroke(width = w * 0.04f)
            )
            drawArc(
                color = goldColor,
                startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(w * 0.2f, h * 0.75f), size = Size(w * 0.6f, h * 0.35f),
                style = Stroke(width = w * 0.04f)
            )

            // 4. Draw Face Area (Skin Color with 3D gradient)
            val faceGradient = Brush.radialGradient(
                colors = listOf(Color(0xFFFFF3E0), skinColor, Color(0xFFF57C00)), // Light tone -> Skin Tone -> Darker shadow edge
                center = Offset(centerX - w * 0.1f, h * 0.4f),
                radius = w * 0.35f
            )
            drawCircle(
                brush = faceGradient,
                radius = w * 0.33f,
                center = Offset(centerX, h * 0.45f)
            )

            // 5. Draw Thanaka (ပါးကွက်ကြား) - Made 3D/Embossed
            val thanakaBase = Color(0xFFFFE082) 
            val thanakaPulse = if (status == VoiceStatus.LISTENING) Color(0xFFFFF59D) else thanakaBase
            val thanakaColor = thanakaPulse.copy(alpha = if (status == VoiceStatus.LISTENING) thanakaGlow else 0.9f)
            
            val leftThanakaCenter = Offset(centerX - w * 0.18f, h * 0.5f)
            val rightThanakaCenter = Offset(centerX + w * 0.18f, h * 0.5f)
            
            // Left Thanaka Gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.4f), thanakaColor, Color(0xFFFBC02D).copy(alpha = 0.8f)),
                    center = Offset(leftThanakaCenter.x - w*0.02f, leftThanakaCenter.y - w*0.02f),
                    radius = w * 0.1f
                ),
                radius = w * 0.1f,
                center = leftThanakaCenter
            )
            
            // Right Thanaka Gradient
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.4f), thanakaColor, Color(0xFFFBC02D).copy(alpha = 0.8f)),
                    center = Offset(rightThanakaCenter.x - w*0.02f, rightThanakaCenter.y - w*0.02f),
                    radius = w * 0.1f
                ),
                radius = w * 0.1f,
                center = rightThanakaCenter
            )

            // 6. Draw Eyes (With Blinking)
            val baseEyeRadius = if (status == VoiceStatus.LISTENING) w * 0.06f else w * 0.04f
            val eyeY = h * 0.4f
            var leftEyeX = centerX - w * 0.12f
            var rightEyeX = centerX + w * 0.12f
            
            if (status == VoiceStatus.PROCESSING) {
                leftEyeX += eyeDart * (w * 0.03f)
                rightEyeX += eyeDart * (w * 0.03f)
            }

            // Draw eye ovals to allow blinking (scaling Y axis)
            val currentEyeHeight = baseEyeRadius * 2f * eyeBlink
            
            // Eye drop shadows for 3D feel
            if (eyeBlink > 0.5f) {
                drawOval(
                    color = Color.Black.copy(alpha = 0.2f),
                    topLeft = Offset(leftEyeX - baseEyeRadius, eyeY - currentEyeHeight / 2f + w * 0.01f),
                    size = Size(baseEyeRadius * 2f, currentEyeHeight)
                )
                drawOval(
                    color = Color.Black.copy(alpha = 0.2f),
                    topLeft = Offset(rightEyeX - baseEyeRadius, eyeY - currentEyeHeight / 2f + w * 0.01f),
                    size = Size(baseEyeRadius * 2f, currentEyeHeight)
                )
            }

            drawOval(
                color = Color.Black,
                topLeft = Offset(leftEyeX - baseEyeRadius, eyeY - currentEyeHeight / 2f),
                size = Size(baseEyeRadius * 2f, currentEyeHeight)
            )
            drawOval(
                color = Color.Black,
                topLeft = Offset(rightEyeX - baseEyeRadius, eyeY - currentEyeHeight / 2f),
                size = Size(baseEyeRadius * 2f, currentEyeHeight)
            )
            
            // Eye Highlights (Sparkle) - Hide when blinking
            if (status != VoiceStatus.PROCESSING && eyeBlink > 0.5f) {
                drawCircle(color = Color.White, radius = baseEyeRadius * 0.35f, center = Offset(leftEyeX + baseEyeRadius * 0.3f, eyeY - baseEyeRadius * 0.3f))
                drawCircle(color = Color.White, radius = baseEyeRadius * 0.35f, center = Offset(rightEyeX + baseEyeRadius * 0.3f, eyeY - baseEyeRadius * 0.3f))
            }

            // 7. Draw Mouth (Smoother opening/closing)
            val mouthY = h * 0.55f
            if (status == VoiceStatus.SPEAKING) {
                // Open Mouth (Dynamic scaling)
                val maxMouthHeight = w * 0.18f
                val minMouthHeight = w * 0.04f
                val mouthHeight = minMouthHeight + (maxMouthHeight - minMouthHeight) * speakMouth
                
                // Mouth inner shadow
                val mouthGradient = Brush.verticalGradient(
                    colors = listOf(Color.Black, darkRed),
                    startY = mouthY,
                    endY = mouthY + mouthHeight
                )
                drawOval(
                    brush = mouthGradient,
                    topLeft = Offset(centerX - w * 0.08f, mouthY),
                    size = Size(w * 0.16f, mouthHeight)
                )
            } else if (status == VoiceStatus.LISTENING) {
                // Surprised 'O' Mouth
                drawCircle(
                    color = Color(0xFF6B0000), // Darker red for depth
                    radius = w * 0.05f,
                    center = Offset(centerX, mouthY + w * 0.05f)
                )
            } else {
                // Smile with subtle drop shadow
                drawArc(
                    color = Color.Black.copy(alpha = 0.2f),
                    startAngle = 20f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(centerX - w * 0.1f, mouthY - w * 0.05f + w * 0.01f),
                    size = Size(w * 0.2f, w * 0.15f),
                    style = Stroke(width = w * 0.02f)
                )
                drawArc(
                    color = darkRed,
                    startAngle = 20f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(centerX - w * 0.1f, mouthY - w * 0.05f),
                    size = Size(w * 0.2f, w * 0.15f),
                    style = Stroke(width = w * 0.02f)
                )
            }
        }
    }
}
