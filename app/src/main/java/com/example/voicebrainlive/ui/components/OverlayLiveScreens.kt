package com.example.voicebrainlive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.BubblePrefs
import com.example.voicebrainlive.agent.AgentProgressBus

@Composable
fun OverlayLiveCardScreen(
    statusText: String,
    voiceStatus: VoiceStatus,
    isMuted: Boolean,
    isPaused: Boolean,
    notifApp: String = "",
    notifSender: String = "",
    notifText: String = "",
    lastTranscript: String = "",
    agentState: AgentProgressBus.AgentExecutionState = AgentProgressBus.AgentExecutionState(),
    onMuteClick: () -> Unit,
    onPauseClick: () -> Unit,
    onCloseClick: () -> Unit,
    onActionLive: () -> Unit,
    onActionVideo: () -> Unit,
    onActionScreenshare: () -> Unit,
    onMinimize: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF0A1124).copy(alpha = 0.96f))
            .padding(16.dp)
    ) {
        // Top Bar with Arc-Reactor / Mascot & Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clickable { onMinimize() },
                contentAlignment = Alignment.Center
            ) {
                JarvisArcReactorCore(
                    voiceStatus = voiceStatus,
                    modifier = Modifier.size(60.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Status Text & App Name
            Column(modifier = Modifier.weight(1f)) {
                val statusLabel = when {
                    agentState.isRunning -> "🤖 Agent လုပ်ဆောင်နေသည်..."
                    voiceStatus == VoiceStatus.SPEAKING -> "🗣️ J.A.R.V.I.S. ဖြေကြားနေသည်..."
                    voiceStatus == VoiceStatus.LISTENING -> "🎙️ နားထောင်နေသည်..."
                    voiceStatus == VoiceStatus.PROCESSING -> "🧠 စဉ်းစားနေသည်..."
                    else -> if (statusText.isNotBlank()) statusText else "✨ J.A.R.V.I.S. Ready"
                }
                Text(
                    text = statusLabel,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (notifApp.isNotBlank()) "🔔 $notifApp Notification" else "Voice Brain AI Agent",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Quick Control Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = onMuteClick, modifier = Modifier.size(36.dp)) {
                    Text(if (isMuted) "🔇" else "🎙️", fontSize = 18.sp)
                }
                IconButton(onClick = onPauseClick, modifier = Modifier.size(36.dp)) {
                    Text(if (isPaused) "▶️" else "⏸️", fontSize = 18.sp)
                }
                IconButton(onClick = onCloseClick, modifier = Modifier.size(36.dp)) {
                    Text("❌", fontSize = 18.sp)
                }
            }
        }

        // Notification Message Content Badge (if popped up by a notification)
        if (notifApp.isNotBlank() || notifSender.isNotBlank() || notifText.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E293B))
                    .padding(12.dp)
            ) {
                if (notifSender.isNotBlank()) {
                    Text(
                        text = "📩 $notifSender",
                        color = Color(0xFFFACC15),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                if (notifText.isNotBlank()) {
                    Text(
                        text = notifText,
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                        maxLines = 3
                    )
                }
            }
        }

        // Live Autonomous Agent Step Timeline
        if (agentState.steps.isNotEmpty() || agentState.isRunning) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF111C38))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "🤖 ${agentState.goal.ifBlank { "Agent Mission" }}",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                agentState.steps.take(4).forEach { step ->
                    val prefix = when (step.status) {
                        AgentProgressBus.StepStatus.DONE -> "✓"
                        AgentProgressBus.StepStatus.RUNNING -> "⟳"
                        AgentProgressBus.StepStatus.BLOCKED -> "⚠"
                        AgentProgressBus.StepStatus.FAILED -> "✕"
                        AgentProgressBus.StepStatus.PENDING -> "•"
                    }
                    Text(
                        text = "$prefix ${step.title}",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // Live Transcript / AI Response preview
        if (lastTranscript.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastTranscript,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                maxLines = 2
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF334155))
        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton("☀️", "Brief", onActionVideo)
            ActionButton("📱", "Screen AI", onActionScreenshare)
            ActionButton("✨", "J.A.R.V.I.S.", onActionLive)
        }
    }
}

@Composable
fun OverlayRobotCornerScreen(
    statusText: String,
    voiceStatus: VoiceStatus,
    theme: BubblePrefs.BubbleTheme = BubblePrefs.BubbleTheme.PYIT_TINE_HTAUNG,
    onExpand: () -> Unit
) {
    // A sleek, minimal layout showing the selected avatar floating in the corner
    Box(
        modifier = Modifier
            .clickable { onExpand() }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        when (theme) {
            BubblePrefs.BubbleTheme.ROBOT -> {
                AnimatedRobotAvatar(
                    status = voiceStatus,
                    modifier = Modifier.size(56.dp)
                )
            }
            BubblePrefs.BubbleTheme.NEON_ORB -> {
                AnimatedNeonOrbAvatar(
                    status = voiceStatus,
                    modifier = Modifier.size(56.dp)
                )
            }
            else -> {
                AnimatedPyitTineHtaungAvatar(
                    status = voiceStatus,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
    }
}

@Composable
fun ActionButton(icon: String, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E293B))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}
