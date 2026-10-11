package com.example.voicebrainlive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallStateReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "CallStateReceiver"
        var onCallStateChanged: ((String, String) -> Unit)? = null
        var lastCallNumber: String = ""
        var lastCallState: String = ""
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != TelephonyManager.ACTION_PHONE_STATE_CHANGED &&
            action != Intent.ACTION_NEW_OUTGOING_CALL
        ) {
            return
        }

        if (action == Intent.ACTION_NEW_OUTGOING_CALL) {
            val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: ""
            lastCallState = "OUTGOING"
            lastCallNumber = number
            onCallStateChanged?.invoke("OUTGOING", number)
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
        
        Log.d(TAG, "Call state: $state, number: $number")
        
        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                // Call answered - start recording
                lastCallState = "OFFHOOK"
                lastCallNumber = number
                onCallStateChanged?.invoke("OFFHOOK", number)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                // Call ended - stop recording
                lastCallState = "IDLE"
                onCallStateChanged?.invoke("IDLE", lastCallNumber)
            }
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Incoming call
                lastCallState = "RINGING"
                lastCallNumber = number
                onCallStateChanged?.invoke("RINGING", number)
            }
        }
    }
}
