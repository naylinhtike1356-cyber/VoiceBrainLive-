package com.example.voicebrainlive.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.voicebrainlive.proactive.ProactiveAlertDispatcher
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
            when (gf.requestId) {
                GEOFENCE_ID_HOME -> {
                    if (!LocationTriggerStorage.shouldTriggerGeofence(context, "home")) continue
                    LocationTriggerStorage.markGeofenceTriggered(context, "home")
                    if (isEnter) {
                        Log.d(TAG, "Home geofence entered")
                        ProactiveAlertDispatcher.dispatchLocationArrival(
                            context, "အိမ်ရောက်ပြီ", ProactiveAlertDispatcher.ACTION_HOME
                        )
                    } else {
                        Log.d(TAG, "Home geofence exited")
                        ProactiveAlertDispatcher.dispatchLocationArrival(
                            context, "အိမ်ကနေ ထွက်သွားပြီ", ProactiveAlertDispatcher.ACTION_HOME_EXIT
                        )
                    }
                }
                GEOFENCE_ID_OFFICE -> {
                    if (!LocationTriggerStorage.shouldTriggerGeofence(context, "office")) continue
                    LocationTriggerStorage.markGeofenceTriggered(context, "office")
                    if (isEnter) {
                        Log.d(TAG, "Office geofence entered")
                        ProactiveAlertDispatcher.dispatchLocationArrival(
                            context, "ခုပဲ ရောက်တာလား", ProactiveAlertDispatcher.ACTION_OFFICE
                        )
                    } else {
                        Log.d(TAG, "Office geofence exited")
                        ProactiveAlertDispatcher.dispatchLocationArrival(
                            context, "ရုံးကနေ ထွက်သွားပြီ", ProactiveAlertDispatcher.ACTION_OFFICE_EXIT
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val GEOFENCE_ID_HOME = "voice_brain_home"
        const val GEOFENCE_ID_OFFICE = "voice_brain_office"
    }
}
