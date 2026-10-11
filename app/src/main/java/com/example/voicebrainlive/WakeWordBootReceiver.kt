package com.example.voicebrainlive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Boot complete ရရှိပါက WakeWordListenerService ကို အလိုအလျောက် စတင်သည်။
 * အသံနှိုး ဖွင့်ထားပါက ဖုန်းပြန်ဖွင့်ပြီးနောက် Hey Voice / Voice Brain ဆက်နားထောင်မည်။
 */
class WakeWordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        
        val pendingResult = goAsync()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        // Boot up delay: Wait 10 seconds for System UI to stabilize
        handler.postDelayed({
            try {
                if (WakeWordPrefs.isEnabled(context)) {
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        val serviceIntent = Intent(context, WakeWordListenerService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                        Log.d(TAG, "WakeWordBootReceiver: Service started after boot delay")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WakeWordBootReceiver error: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }, 10000L) // 10s delay
    }

    companion object {
        private const val TAG = "WakeWordBoot"
    }
}
