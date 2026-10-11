package com.example.voicebrainlive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boot complete ရရှိပါက FloatingBubbleService ကို အလိုအလျောက် စတင်သည်။
 * Bubble နှင့် overlay permission ဖွင့်ထားပါက ဖုန်းပြန်ဖွင့်ပြီးနောက် bubble ဆက်အလုပ်လုပ်မည်။
 */
class BubbleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        
        val pendingResult = goAsync()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Boot up delay: Wait 12 seconds for System UI to stabilize (staggered with WakeWord)
        handler.postDelayed({
            try {
                if (BubblePrefs.isEnabled(context)) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)) {
                        val serviceIntent = Intent(context, FloatingBubbleService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        Log.w(TAG, "BubbleBootReceiver: Bubble started after boot delay")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BubbleBootReceiver error: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }, 12000L) // 12s delay (staggered)
    }

    companion object {
        private const val TAG = "BubbleBoot"
    }
}
