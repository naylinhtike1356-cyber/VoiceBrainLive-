package com.example.voicebrainlive.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent

/**
 * Phase 2.1: Geofencing – Home/Office ရောက်တဲ့အခါ trigger
 * WiFi နဲ့အတူ GPS သုံးပြီး ပိုတိကျစွာ သတိပေးမယ်
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
            return
        }
        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER && transition != Geofence.GEOFENCE_TRANSITION_EXIT) return

        val isEnter = transition == Geofence.GEOFENCE_TRANSITION_ENTER
        for (gf in event.triggeringGeofences ?: emptyList()) {
            val requestId = gf.requestId
            when (requestId) {
                GEOFENCE_ID_HOME -> {
                    if (!LocationTriggerStorage.shouldTriggerGeofence(context, "home")) continue
                    LocationTriggerStorage.markGeofenceTriggered(context, "home")
                    if (isEnter) {
                        Log.d(TAG, "Home geofence entered")
                        val serviceIntent = Intent(context, LocationArrivalSpeakerService::class.java).apply {
                            putExtra(LocationArrivalSpeakerService.EXTRA_MESSAGE, "အိမ်ရောက်ပြီ")
                            putExtra(LocationArrivalSpeakerService.EXTRA_ACTION, LocationArrivalSpeakerService.ACTION_HOME)
                        }
                        startService(context, serviceIntent)
                    } else {
                        Log.d(TAG, "Home geofence exited")
                        val serviceIntent = Intent(context, LocationArrivalSpeakerService::class.java).apply {
                            putExtra(LocationArrivalSpeakerService.EXTRA_MESSAGE, "အိမ်ကနေ ထွက်သွားပြီ")
                            putExtra(LocationArrivalSpeakerService.EXTRA_ACTION, LocationArrivalSpeakerService.ACTION_HOME_EXIT)
                        }
                        startService(context, serviceIntent)
                    }
                }
                GEOFENCE_ID_OFFICE -> {
                    if (!LocationTriggerStorage.shouldTriggerGeofence(context, "office")) continue
                    LocationTriggerStorage.markGeofenceTriggered(context, "office")
                    if (isEnter) {
                        Log.d(TAG, "Office geofence entered")
                        val serviceIntent = Intent(context, LocationArrivalSpeakerService::class.java).apply {
                            putExtra(LocationArrivalSpeakerService.EXTRA_MESSAGE, "ခုပဲ ရောက်တာလား")
                            putExtra(LocationArrivalSpeakerService.EXTRA_ACTION, LocationArrivalSpeakerService.ACTION_OFFICE)
                        }
                        startService(context, serviceIntent)
                    } else {
                        Log.d(TAG, "Office geofence exited")
                        val serviceIntent = Intent(context, LocationArrivalSpeakerService::class.java).apply {
                            putExtra(LocationArrivalSpeakerService.EXTRA_MESSAGE, "ရုံးကနေ ထွက်သွားပြီ")
                            putExtra(LocationArrivalSpeakerService.EXTRA_ACTION, LocationArrivalSpeakerService.ACTION_OFFICE_EXIT)
                        }
                        startService(context, serviceIntent)
                    }
                }
            }
        }
    }

    private fun startService(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val GEOFENCE_ID_HOME = "voice_brain_home"
        const val GEOFENCE_ID_OFFICE = "voice_brain_office"
    }
}
