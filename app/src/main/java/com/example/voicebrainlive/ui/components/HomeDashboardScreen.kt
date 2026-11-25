package com.example.voicebrainlive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicebrainlive.agent.AgentProgressBus
import com.example.voicebrainlive.ui.theme.*
import java.util.Calendar

data class DashboardItem(
    val icon: String,
    val title: String,
    val iconRes: Int? = null,
    val onClick: () -> Unit
)

data class PendingTaskCardModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val timeLabel: String,
    val categoryLabel: String,
    val statusLabel: String,
    val icon: String,
    val isReady: Boolean = false,
    val type: String = "reminder"
)

enum class HomeConnectionState { READY, CONNECTING, ERROR, OFFLINE, SETUP_REQUIRED }

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
    dashboardItems: List<DashboardItem>,
    pendingTasks: List<PendingTaskCardModel> = emptyList(),
    onTaskClick: (PendingTaskCardModel) -> Unit = {},
    onViewAllTasks: () -> Unit = onQuickAutomate,
    connectionState: HomeConnectionState = HomeConnectionState.READY,
    onSetupClick: () -> Unit = {},
    personaLabel: String = "J.A.R.V.I.S.",
    wakePhraseLabel: String = "Hey Jarvis",
    accessibilityReady: Boolean = false,
    agentState: AgentProgressBus.AgentExecutionState = AgentProgressBus.AgentExecutionState(),
    onToggleWakeWord: (() -> Unit)? = null,
    onOpenAssistSettings: (() -> Unit)? = null,
    onRunAgentMission: ((String) -> Unit)? = null
) {
    var textInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val isActive = voiceStatus != VoiceStatus.IDLE
    val voiceBusy = connectionState == HomeConnectionState.CONNECTING || voiceStatus == VoiceStatus.PROCESSING

    // Contextual Burmese Greeting
    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (currentHour) {
        in 4..11 -> "မင်္ဂလာနံနက်ခင်းပါ"
        in 12..16 -> "မင်္ဂလာနေ့လယ်ခင်းပါ"
        in 17..20 -> "မင်္ဂလာညနေခင်းပါ"
        else -> "မင်္ဂလာညချမ်းပါ"
    }

    // Dynamic Readiness Chip Attributes
    val (statusDotColor, statusLabel) = when (connectionState) {
        HomeConnectionState.CONNECTING -> VoiceBrainWarning to "ချိတ်ဆက်နေသည်"
        HomeConnectionState.ERROR -> VoiceBrainError to "ချိတ်ဆက်မှု ပြဿနာ"
        HomeConnectionState.OFFLINE -> VoiceBrainWarning to "အင်တာနက် မရှိပါ"
        HomeConnectionState.SETUP_REQUIRED -> VoiceBrainWarning to "Gemini Key လိုအပ်သည်"
        HomeConnectionState.READY -> when (voiceStatus) {
            VoiceStatus.LISTENING -> VoiceBrainSecondary to "နားထောင်နေသည်"
            VoiceStatus.SPEAKING -> VoiceBrainPrimaryLight to "ဖြေကြားနေသည်"
            VoiceStatus.PROCESSING -> VoiceBrainWarning to "စဉ်းစားနေသည်"
            VoiceStatus.IDLE -> VoiceBrainSuccess to "အသင့်ရှိသည်"
        }
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
                    text = "$personaLabel • AI Agent",
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

        // ── 1.5 J.A.R.V.I.S. SYSTEM TELEMETRY STRIP ─────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TelemetryPill(
                label = if (wakeWordActive) "🎙️ Wake: \"$wakePhraseLabel\"" else "🎙️ Wake: OFF",
                active = wakeWordActive,
                onClick = { onToggleWakeWord?.invoke() ?: onStatusCardClick() }
            )
            TelemetryPill(
                label = if (accessibilityReady) "🦾 Screen Co-Pilot: ON" else "🦾 Screen Co-Pilot: Setup",
                active = accessibilityReady,
                onClick = { onOpenAssistSettings?.invoke() ?: onStatusCardClick() }
            )
            TelemetryPill(
                label = "⚡ Default Assist",
                active = true,
                onClick = { onOpenAssistSettings?.invoke() ?: onStatusCardClick() }
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── 2. HERO VOICE CENTER (Arc-Reactor + Compact Fluid Orb + CTA) ──
        Box(
            modifier = Modifier
                .size(252.dp)
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
                ),
            contentAlignment = Alignment.Center
        ) {
            JarvisArcReactorCore(
                voiceStatus = voiceStatus,
                modifier = Modifier.size(244.dp)
            )
            AssistantFluidOrb(status = voiceStatus)
        }

        Spacer(Modifier.height(14.dp))

        // Primary Action Button (28dp Pill)
        val ctaText = when {
            connectionState == HomeConnectionState.CONNECTING -> "ချိတ်ဆက်နေသည်"
            voiceStatus == VoiceStatus.PROCESSING -> "စဉ်းစားနေသည်"
            isActive -> "အသံ ရပ်မည်"
            connectionState == HomeConnectionState.ERROR -> "ပြန်ချိတ်ဆက်မည်"
            connectionState in setOf(HomeConnectionState.OFFLINE, HomeConnectionState.SETUP_REQUIRED) ->
                "ဖုန်းအမိန့် ပြောမည်"
            else -> "စကားပြောမည်"
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            if (statusText.isNotBlank()) {
                Text(statusText, color = VoiceBrainTextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            if (connectionState == HomeConnectionState.SETUP_REQUIRED) {
                TextButton(onClick = onSetupClick) { Text("Gemini API Key ထည့်မည်", color = VoiceBrainPrimaryLight) }
            }
        }

        Button(
            onClick = onMicClick,
            enabled = !voiceBusy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6D42C8), contentColor = Color.White)
        ) {
            Text(ctaText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        // Wake word hint
        Text(
            text = if (wakeWordActive) "\"$wakePhraseLabel\" ဟု အသံဖြင့် ခေါ်၍ စေခိုင်းနိုင်ပါသည်"
                   else "Wake Word ဖွင့်ထားပါက \"$wakePhraseLabel\" ဟု ခေါ်၍ စေခိုင်းနိုင်သည်",
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

        Spacer(Modifier.height(12.dp))

        // ── 3.5 AUTONOMOUS AI AGENT MISSIONS (1-Tap Jarvis Tasks) ──────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AgentMissionChip("☀️ Morning Brief") {
                val cmd = "မနက်ခင်း အစီအစဉ် ပြောပြပေး"
                onRunAgentMission?.invoke(cmd) ?: onTextSubmit(cmd)
            }
            AgentMissionChip("📰 AI News → Notion") {
                val cmd = "AI technology news ရှာပြီး Notion ထဲ သိမ်းပေး"
                onRunAgentMission?.invoke(cmd) ?: onTextSubmit(cmd)
            }
            AgentMissionChip("📱 Screen Co-Pilot") {
                val cmd = "ဒီစခရင်ပေါ်က အချက်အလက်တွေကို ဖတ်ပြပေး"
                onRunAgentMission?.invoke(cmd) ?: onTextSubmit(cmd)
            }
            AgentMissionChip("🔋 System Check") {
                val cmd = "ဖုန်း battery နဲ့ wifi အခြေအနေ စစ်ပေး"
                onRunAgentMission?.invoke(cmd) ?: onTextSubmit(cmd)
            }
        }

        // Live Agent Progress Card
        AnimatedVisibility(visible = agentState.steps.isNotEmpty() || agentState.isRunning) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                color = Color(0xFF0E1830),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainSecondary.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = "🤖 ${agentState.goal.ifBlank { "Autonomous Agent" }} • ${agentState.phase.name}",
                        color = VoiceBrainSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    agentState.steps.forEach { step ->
                        val icon = when (step.status) {
                            AgentProgressBus.StepStatus.DONE -> "✓"
                            AgentProgressBus.StepStatus.RUNNING -> "⟳"
                            AgentProgressBus.StepStatus.BLOCKED -> "⚠"
                            AgentProgressBus.StepStatus.FAILED -> "✕"
                            AgentProgressBus.StepStatus.PENDING -> "•"
                        }
                        Text(
                            text = "$icon ${step.title}",
                            color = VoiceBrainTextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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

        // ── 4.5 ACTIVE & PENDING TASKS SECTION (ခိုင်းထားသော အလုပ်များနှင့် လုပ်ဆောင်ရန်များ) ──
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📋 လုပ်ဆောင်ရန် အလုပ်များ",
                    color = VoiceBrainText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (pendingTasks.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = VoiceBrainSecondary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainSecondary.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "${pendingTasks.size} ခု",
                            color = VoiceBrainSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = "အားလုံးကြည့်ရန် ➔",
                color = VoiceBrainPrimaryLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onViewAllTasks() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))

        if (pendingTasks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pendingTasks.take(4).forEach { task ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onTaskClick(task) },
                        color = VoiceBrainSurface,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                VoiceBrainPrimary.copy(alpha = 0.25f),
                                                VoiceBrainSecondary.copy(alpha = 0.25f)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(task.icon, fontSize = 22.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = task.title,
                                        color = VoiceBrainText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = VoiceBrainSurfaceSoft,
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(0.5.dp, VoiceBrainCardStroke)
                                    ) {
                                        Text(
                                            text = task.categoryLabel,
                                            color = VoiceBrainSecondary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = task.subtitle,
                                    color = VoiceBrainTextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "⏰ ${task.timeLabel}",
                                        color = VoiceBrainWarning,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "•",
                                        color = VoiceBrainTextMuted,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = "⚡ ${task.statusLabel}",
                                        color = VoiceBrainSuccess,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Friendly Empty State Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onViewAllTasks() },
                color = VoiceBrainSurfaceSoft.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainCardStroke.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📝", fontSize = 28.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "လက်ရှိ ခိုင်းထားသော အလုပ် မရှိသေးပါ",
                            color = VoiceBrainText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "အသံဖြင့် 'မနက် ၇ နာရီ နှိုးစက်ထားပေး' ဟု ခိုင်းနိုင်သည်",
                            color = VoiceBrainTextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Surface(
                        color = VoiceBrainPrimary.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainPrimary.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "+ အလုပ်သစ်",
                            color = VoiceBrainPrimaryLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
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

@Composable
private fun TelemetryPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val accent = if (active) VoiceBrainSecondary else VoiceBrainTextMuted
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable { onClick() },
        color = if (active) VoiceBrainSecondary.copy(alpha = 0.14f) else VoiceBrainSurfaceSoft,
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(0.9.dp, accent.copy(alpha = 0.55f))
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFFE0F2FE) else VoiceBrainTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun AgentMissionChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        color = Color(0xFF13203E),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VoiceBrainPrimaryLight.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            color = VoiceBrainText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
