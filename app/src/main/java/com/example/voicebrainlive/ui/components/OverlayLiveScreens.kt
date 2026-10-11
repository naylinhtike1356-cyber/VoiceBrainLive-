package com.example.voicebrainlive.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.example.voicebrainlive.BubblePrefs

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
            .background(Color(0xFF0F172A).copy(alpha = 0.96f))
            .padding(16.dp)
    ) {
        // Top Bar with Pyit Tine Htaung Mascot & Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pyit Tine Htaung Animated Avatar Icon
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clickable { onMinimize() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedPyitTineHtaungAvatar(
                    status = voiceStatus,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Status Text & App Name
            Column(modifier = Modifier.weight(1f)) {
                val statusLabel = when (voiceStatus) {
                    VoiceStatus.SPEAKING -> "🗣️ Agent ရှင်းပြနေသည်..."
                    VoiceStatus.LISTENING -> "🎙️ Agent နားထောင်နေသည်..."
                    VoiceStatus.PROCESSING -> "🧠 Agent စဉ်းစားနေသည်..."
                    else -> if (statusText.isNotBlank()) statusText else "✨ Pyit Tine Htaung Agent"
                }
                Text(
                    text = statusLabel,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (notifApp.isNotBlank()) "🔔 $notifApp Notification" else "Voice Brain AI Assistant",
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
        Divider(color = Color(0xFF334155))
        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton("📹", "Video", onActionVideo)
            ActionButton("💻", "Screen", onActionScreenshare)
            ActionButton("✨", "Assistant", onActionLive)
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
