package com.example.voicebrainlive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.ui.theme.*
import java.util.Calendar

data class DashboardItem(
    val icon: String,
    val title: String,
    val iconRes: Int? = null,
    val onClick: () -> Unit
)

@Composable
fun HomeDashboardScreen(
    statusText: String,
    transcriptText: String,
    voiceStatus: VoiceStatus,
    wakeWordActive: Boolean = false,
    batteryOptimized: Boolean = true,
    onMicClick: () -> Unit,
    onTextSubmit: (String) -> Unit,
    onQuickAsk: () -> Unit = onMicClick,
    onQuickRemember: () -> Unit = {},
    onQuickAutomate: () -> Unit = {},
    onStatusCardClick: () -> Unit = {},
    dashboardItems: List<DashboardItem>
) {
    var textInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val isActive = voiceStatus != VoiceStatus.IDLE

    // Contextual Burmese Greeting
    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (currentHour) {
        in 4..11 -> "မင်္ဂလာနံနက်ခင်းပါ"
        in 12..16 -> "မင်္ဂလာနေ့လယ်ခင်းပါ"
        in 17..20 -> "မင်္ဂလာညနေခင်းပါ"
        else -> "မင်္ဂလာညချမ်းပါ"
    }

    // Dynamic Readiness Chip Attributes
    val (statusDotColor, statusLabel) = when (voiceStatus) {
        VoiceStatus.LISTENING -> VoiceBrainSecondary to "နားထောင်နေသည် • Listening"
        VoiceStatus.SPEAKING -> VoiceBrainSecondary to "ရှင်းပြနေသည် • Speaking"
        VoiceStatus.PROCESSING -> VoiceBrainWarning to "စဉ်းစားနေသည် • Thinking"
        VoiceStatus.IDLE -> VoiceBrainSuccess to "အသင့်ရှိသည် • Ready"
    }

    // Ambient breathing pulse for orb
    val infiniteTransition = rememberInfiniteTransition(label = "ambientGlow")
    val ambientPulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambientPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        VoiceBrainBackground,
                        Color(0xFF0E162B),
                        VoiceBrainBackgroundEnd
                    )
                )
            )
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── 1. GREETING & READINESS STATUS ROW ──────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = greeting,
                    color = VoiceBrainTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "AI Voice Assistant",
                    color = VoiceBrainText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Readiness Badge
            Surface(
                color = VoiceBrainSurfaceSoft,
                shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        color = VoiceBrainText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 2. HERO VOICE CENTER (Compact Fluid Orb + CTA) ─────────────
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            if (isActive) VoiceBrainSecondary.copy(alpha = 0.35f * ambientPulse)
                            else VoiceBrainPrimary.copy(alpha = 0.22f * ambientPulse),
                            Color(0xFF151D33).copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
                .clickable { onMicClick() },
            contentAlignment = Alignment.Center
        ) {
            AssistantFluidOrb(status = voiceStatus)
        }

        Spacer(Modifier.height(14.dp))

        // Primary Action Button (28dp Pill)
        val (ctaColor, ctaIcon, ctaText) = when (voiceStatus) {
            VoiceStatus.LISTENING -> Triple(VoiceBrainError, "⏹️", "နားထောင်နေသည် (ရပ်ရန် နှိပ်ပါ)")
            VoiceStatus.SPEAKING -> Triple(VoiceBrainSecondary, "⏹️", "ရှင်းပြနေသည် (ရပ်ရန် နှိပ်ပါ)")
            VoiceStatus.PROCESSING -> Triple(VoiceBrainWarning, "⏳", "စဉ်းစားနေပါသည်...")
            VoiceStatus.IDLE -> Triple(VoiceBrainPrimary, "🎙️", "စကားပြောရန် နှိပ်ပါ (Tap to talk)")
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(28.dp))
                .clickable { onMicClick() },
            color = ctaColor,
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(text = ctaIcon, fontSize = 18.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = ctaText,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Wake word hint
        Text(
            text = if (wakeWordActive) "\"Hey Voice\" ဟု အသံဖြင့် နှိုးနိုင်ပါသည်"
                   else "အသံဖြင့် အဆင်ပြေစွာ ခိုင်းစေနိုင်ပါသည်",
            color = VoiceBrainTextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
        )

        // ── 3. SMART SEARCH / PROMPT INPUT BAR (Text Fallback) ──────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            color = VoiceBrainSurface,
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⌨️", fontSize = 15.sp)
                Spacer(Modifier.width(6.dp))
                TextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = {
                        Text(
                            "စာရိုက်၍ မေးမြန်းရန်...",
                            color = VoiceBrainTextMuted,
                            fontSize = 13.sp
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = VoiceBrainText,
                        unfocusedTextColor = VoiceBrainText,
                        cursorColor = VoiceBrainSecondary
                    ),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                if (textInput.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                onTextSubmit(textInput.trim())
                                textInput = ""
                            },
                        color = VoiceBrainSecondary
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("➔", color = VoiceBrainBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 4. CONVERSATION PREVIEW / SUGGESTED PROMPTS CARD ─────────────
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = VoiceBrainSurface,
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (transcriptText.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "စကားပြောမှတ်တမ်း",
                            color = VoiceBrainTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Live",
                            color = VoiceBrainSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = transcriptText,
                        color = VoiceBrainText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    // Suggested Prompts when idle
                    Text(
                        text = "အကြံပြု မေးခွန်းများ",
                        color = VoiceBrainTextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SuggestionChip(
                            label = "ဒီနေ့ ရာသီဥတု ဘယ်လိုလဲ?",
                            onClick = { onTextSubmit("ဒီနေ့ ရာသီဥတု ဘယ်လိုလဲ?") },
                            modifier = Modifier.weight(1f)
                        )
                        SuggestionChip(
                            label = "သတိပေးချက် အသစ်လုပ်မယ်",
                            onClick = { onTextSubmit("သတိပေးချက် အသစ်လုပ်မယ်") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 5. ESSENTIAL CORE SERVICES (2x2 Grid - Genuine Useful Only) ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "မကြာခဏသုံးသော ဝန်ဆောင်မှုများ",
                color = VoiceBrainText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            dashboardItems.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashboardTile(rowItems.getOrNull(0))
                    DashboardTile(rowItems.getOrNull(1))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── 6. SLIM PRIVACY & SYSTEM FOOTER ───────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onStatusCardClick() },
            color = VoiceBrainSurfaceSoft.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🛡️", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (wakeWordActive) "Wake Word: ဖွင့်ထားသည် • Privacy လုံခြုံမှုရှိသည်"
                               else "Wake Word: ပိတ်ထားသည် • Privacy လုံခြုံမှုရှိသည်",
                        color = VoiceBrainTextMuted,
                        fontSize = 11.sp
                    )
                }
                Text("⚙️", fontSize = 13.sp, color = VoiceBrainTextSecondary)
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SuggestionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = VoiceBrainSurfaceSoft,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke.copy(alpha = 0.4f))
    ) {
        Text(
            text = "💡 $label",
            color = VoiceBrainTextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun RowScope.DashboardTile(item: DashboardItem?) {
    if (item == null) {
        Spacer(Modifier.weight(1f))
        return
    }
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable { item.onClick() },
        color = VoiceBrainSurfaceSoft,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VoiceBrainBackground.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                if (item.iconRes != null) {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = item.title,
                        modifier = Modifier.size(22.dp),
                        tint = VoiceBrainSecondary
                    )
                } else {
                    Text(item.icon, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.title,
                color = VoiceBrainText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
