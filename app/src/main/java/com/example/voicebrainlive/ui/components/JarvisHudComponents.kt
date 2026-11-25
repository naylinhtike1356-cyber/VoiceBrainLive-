package com.example.voicebrainlive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.AssistantActivity
import com.example.voicebrainlive.agent.AgentProgressBus
import kotlin.math.cos
import kotlin.math.sin

/**
 * Siri / Apple Intelligence inspired animated holographic screen-edge glow.
 */
@Composable
fun SiriEdgeGlowBorder(
    voiceStatus: AssistantActivity.VoiceStatus,
    audioAmplitude: Float,
    modifier: Modifier = Modifier
) {
    val (c1, c2, c3) = when (voiceStatus) {
        AssistantActivity.VoiceStatus.LISTENING -> Triple(Color(0xFF00F2FE), Color(0xFF38BDF8), Color(0xFF06B6D4))
        AssistantActivity.VoiceStatus.SPEAKING -> Triple(Color(0xFFA855F7), Color(0xFFEC4899), Color(0xFF38BDF8))
        AssistantActivity.VoiceStatus.PROCESSING -> Triple(Color(0xFFFBBF24), Color(0xFFF97316), Color(0xFF38BDF8))
        AssistantActivity.VoiceStatus.IDLE -> Triple(Color(0xFF34D399), Color(0xFF0EA5E9), Color(0xFF6366F1))
    }

    val colorA by animateColorAsState(c1, tween(350), label = "edgeA")
    val colorB by animateColorAsState(c2, tween(350), label = "edgeB")
    val colorC by animateColorAsState(c3, tween(350), label = "edgeC")

    val infiniteTransition = rememberInfiniteTransition(label = "siriEdge")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edgePulse"
    )

    val ampBoost = (audioAmplitude * 2.2f).coerceIn(0f, 1f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val brush = Brush.sweepGradient(
            colors = listOf(colorA, colorB, colorC, colorA),
            center = Offset(size.width / 2f, size.height / 2f)
        )
        rotate(sweepAngle, pivot = Offset(size.width / 2f, size.height / 2f)) {
            // Outer soft aura
            drawRoundRect(
                brush = brush,
                topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                alpha = (0.30f + 0.25f * ampBoost) * pulse,
                style = Stroke(width = (10f + 8f * ampBoost).dp.toPx())
            )
        }
        // Crisp inner holographic edge line
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(colorA, colorB, colorC)),
            topLeft = Offset(1.dp.toPx(), 1.dp.toPx()),
            size = Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()),
            cornerRadius = CornerRadius(26.dp.toPx(), 26.dp.toPx()),
            alpha = 0.72f * pulse,
            style = Stroke(width = 2.2.dp.toPx())
        )
    }
}

/**
 * Tony Stark Arc-Reactor inspired holographic core visualizer.
 */
@Composable
fun JarvisArcReactorCore(
    voiceStatus: VoiceStatus,
    audioAmplitude: Float = 0f,
    modifier: Modifier = Modifier
) {
    val (coreColor, ringColor, accentColor) = when (voiceStatus) {
        VoiceStatus.LISTENING -> Triple(Color(0xFF00F2FE), Color(0xFF38BDF8), Color(0xFF22D3EE))
        VoiceStatus.SPEAKING -> Triple(Color(0xFFC084FC), Color(0xFFE879F9), Color(0xFF38BDF8))
        VoiceStatus.PROCESSING -> Triple(Color(0xFFFBBF24), Color(0xFFF59E0B), Color(0xFF38BDF8))
        VoiceStatus.IDLE -> Triple(Color(0xFF38BDF8), Color(0xFF6366F1), Color(0xFF34D399))
    }

    val animCore by animateColorAsState(coreColor, tween(300), label = "arcCore")
    val animRing by animateColorAsState(ringColor, tween(300), label = "arcRing")

    val infiniteTransition = rememberInfiniteTransition(label = "arcReactor")
    val outerAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (voiceStatus) {
                    VoiceStatus.PROCESSING -> 1400
                    VoiceStatus.SPEAKING -> 2400
                    else -> 5200
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "outerAngle"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arcPulse"
    )

    val ampFactor = 1f + (audioAmplitude * 0.45f).coerceIn(0f, 0.35f)

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.minDimension / 2f) * 0.80f * ampFactor

        // Ambient glow halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animCore.copy(alpha = 0.38f), animRing.copy(alpha = 0.10f), Color.Transparent),
                center = center,
                radius = radius * 1.45f
            ),
            radius = radius * 1.45f,
            center = center
        )

        // Outer segmented Arc-Reactor ring
        rotate(outerAngle, pivot = center) {
            drawCircle(
                color = animRing.copy(alpha = 0.85f),
                radius = radius,
                center = center,
                style = Stroke(
                    width = 2.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
                )
            )
            // 4 cardinal reactor fins
            for (k in 0 until 4) {
                val rad = Math.toRadians((k * 90).toDouble())
                val p1 = Offset(
                    center.x + (radius * 0.72f * cos(rad)).toFloat(),
                    center.y + (radius * 0.72f * sin(rad)).toFloat()
                )
                val p2 = Offset(
                    center.x + (radius * 1.02f * cos(rad)).toFloat(),
                    center.y + (radius * 1.02f * sin(rad)).toFloat()
                )
                drawLine(color = Color.White.copy(alpha = 0.8f), start = p1, end = p2, strokeWidth = 2.dp.toPx())
            }
        }

        // Counter-rotating inner telemetry ring
        rotate(-outerAngle * 1.4f, pivot = center) {
            drawCircle(
                color = accentColor.copy(alpha = 0.7f),
                radius = radius * 0.68f,
                center = center,
                style = Stroke(
                    width = 1.6.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                )
            )
        }

        // Reactor core sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f),
                    animCore.copy(alpha = 0.9f),
                    Color(0xFF0B1020)
                ),
                center = center,
                radius = radius * 0.45f * pulse
            ),
            radius = radius * 0.45f * pulse,
            center = center
        )
    }
}

/**
 * Full-overlay Tony Stark J.A.R.V.I.S. HUD + Siri Aura used by AssistantActivity.
 */
@Composable
fun JarvisAssistantHudOverlay(
    personaName: String,
    statusText: String,
    transcriptText: String,
    userInputText: String,
    voiceStatus: AssistantActivity.VoiceStatus,
    audioAmplitude: Float,
    isConnecting: Boolean,
    showRetry: Boolean,
    agentState: AgentProgressBus.AgentExecutionState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onSendText: (String) -> Unit,
    onQuickAction: (String) -> Unit
) {
    var showTypeBar by remember { mutableStateOf(false) }
    var draftInput by remember { mutableStateOf("") }

    val mappedStatus = when (voiceStatus) {
        AssistantActivity.VoiceStatus.LISTENING -> VoiceStatus.LISTENING
        AssistantActivity.VoiceStatus.SPEAKING -> VoiceStatus.SPEAKING
        AssistantActivity.VoiceStatus.PROCESSING -> VoiceStatus.PROCESSING
        AssistantActivity.VoiceStatus.IDLE -> VoiceStatus.IDLE
    }

    val stateBadgeColor = when (voiceStatus) {
        AssistantActivity.VoiceStatus.LISTENING -> Color(0xFF00F2FE)
        AssistantActivity.VoiceStatus.SPEAKING -> Color(0xFFC084FC)
        AssistantActivity.VoiceStatus.PROCESSING -> Color(0xFFFBBF24)
        AssistantActivity.VoiceStatus.IDLE -> Color(0xFF34D399)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Siri-style animated screen edge glow
        SiriEdgeGlowBorder(
            voiceStatus = voiceStatus,
            audioAmplitude = audioAmplitude
        )

        // 2. Tap scrim (top/bottom transparent touch area outside HUD card)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClose() }
        )

        // 3. Holographic HUD Card anchored cleanly near top
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 38.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume clicks inside HUD */ },
            shape = RoundedCornerShape(26.dp),
            color = Color(0xEA080E1E),
            border = BorderStroke(
                width = 1.2.dp,
                brush = Brush.horizontalGradient(
                    listOf(stateBadgeColor.copy(alpha = 0.85f), Color(0xFF6366F1).copy(alpha = 0.7f))
                )
            ),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Header Row: Arc Reactor + Telemetry + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    JarvisArcReactorCore(
                        voiceStatus = mappedStatus,
                        audioAmplitude = audioAmplitude,
                        modifier = Modifier.size(50.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = personaName,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = stateBadgeColor.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(0.8.dp, stateBadgeColor.copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = when {
                                        isConnecting -> "LINKING"
                                        agentState.isRunning -> "AGENT ACTIVE"
                                        voiceStatus == AssistantActivity.VoiceStatus.LISTENING -> "LISTENING"
                                        voiceStatus == AssistantActivity.VoiceStatus.SPEAKING -> "SPEAKING"
                                        voiceStatus == AssistantActivity.VoiceStatus.PROCESSING -> "THINKING"
                                        else -> "STANDBY"
                                    },
                                    color = stateBadgeColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.7.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = statusText.ifBlank { "အမိန့်ပေးနိုင်ပါပြီ..." },
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFE2E8F0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Live User Input Echo
                if (userInputText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFF131D36),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(0.6.dp, Color(0xFF2A3A60)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🎙️ $userInputText",
                            color = Color(0xFF7DD3FC),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // Live Autonomous Agent Step Timeline
                AnimatedVisibility(
                    visible = agentState.steps.isNotEmpty() || agentState.isRunning,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0F1A33))
                            .border(0.8.dp, Color(0xFF38BDF8).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "🤖 AGENT MISSION: ${agentState.goal.take(48)}",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        agentState.steps.forEach { step ->
                            val (icon, tint) = when (step.status) {
                                AgentProgressBus.StepStatus.DONE -> "✓" to Color(0xFF4ADE80)
                                AgentProgressBus.StepStatus.RUNNING -> "⟳" to Color(0xFFFBBF24)
                                AgentProgressBus.StepStatus.BLOCKED -> "⚠" to Color(0xFFF97316)
                                AgentProgressBus.StepStatus.FAILED -> "✕" to Color(0xFFF87171)
                                AgentProgressBus.StepStatus.PENDING -> "•" to Color(0xFF94A3B8)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(icon, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = step.title,
                                    color = if (step.status == AgentProgressBus.StepStatus.RUNNING) Color.White else Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Assistant Response Transcript
                if (transcriptText.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = transcriptText,
                        color = Color(0xFFF8FAFC),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Retry button on connection error
                if (showRetry) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ပြန်လည်ချိတ်ဆက်မည် (Retry)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Quick Agent Chips + Keyboard Toggle
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudQuickChip("☀️ Morning Brief") { onQuickAction("မနက်ခင်း အစီအစဉ် ပြောပြပေး") }
                    HudQuickChip("📱 Read Screen") { onQuickAction("ဒီစခရင်ပေါ်က အချက်အလက်တွေကို ဖတ်ပြပေး") }
                    HudQuickChip("📰 News → Notion") { onQuickAction("AI technology news ရှာပြီး Notion ထဲ သိမ်းပေး") }
                    HudQuickChip(if (showTypeBar) "🎙️ Voice" else "⌨️ Type") { showTypeBar = !showTypeBar }
                }

                // Expandable Text Command Input
                AnimatedVisibility(visible = showTypeBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF151F38))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = draftInput,
                            onValueChange = { draftInput = it },
                            placeholder = {
                                Text("J.A.R.V.I.S. ကို စာရိုက်ခိုင်းရန်...", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    val cmd = draftInput.trim()
                                    if (cmd.isNotBlank()) {
                                        onSendText(cmd)
                                        draftInput = ""
                                    }
                                }
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF00F2FE)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val cmd = draftInput.trim()
                                if (cmd.isNotBlank()) {
                                    onSendText(cmd)
                                    draftInput = ""
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color(0xFF00F2FE),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HudQuickChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() },
        color = Color(0xFF15223D),
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.8.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            color = Color(0xFFE0F2FE),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}
