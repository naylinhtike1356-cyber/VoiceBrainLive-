package com.example.voicebrainlive

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * VoiceInteractionService for power-button long-press to launch Assistant.
 * Power button ကြာနှိပ်ရင် OS က ဤ Service ကို ခေါ်ပြီး AssistantActivity ဖွင့်သည်။
 * Microphone conflict ကို ရှောင်ရန် Service က minimal ထားသည်。
 */
class VoiceBrainService : VoiceInteractionService() {

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "Service onBind ဖြစ်သွားပြီ - Intent Action: ${intent?.action}")
        return super.onBind(intent)
    }

    override fun onReady() {
        try {
            super.onReady()
            Log.d(TAG, "Voice Interaction Service is READY")
        } catch (e: Exception) {
            Log.e(TAG, "onReady failed (network/signature?): ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "VoiceBrainPower"
    }
}

class VoiceBrainSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.w(VoiceBrainSession.TAG, "VoiceBrainSessionService onNewSession - Session အသစ် တောင်းခံမှု ရရှိ")
        return VoiceBrainSession(this)
    }
}

class VoiceBrainSession(context: VoiceInteractionSessionService) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // Disable default session UI; we show our own AssistantActivity instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setUiEnabled(false)
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d("VoiceBrain", "VoiceBrainSession onShow - ပါဝါခလုတ် ကြာနှိပ်မှု ရရှိ၊ AssistantActivity ဖွင့်မည်")
        val intent = Intent(context, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startAssistantActivity(intent)
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("VoiceBrain", "Activity ကို ခေါ်လို့မရပါ: ${e.message}", e)
        }
        // Session ကို ခေတ္တစောင့်ပြီးမှ ပိတ်ပါ (Race condition မဖြစ်အောင်)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
    }

    companion object {
        const val TAG = "VoiceBrainPower"
    }
}
