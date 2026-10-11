package com.example.voicebrainlive.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Phase 2.1: Geofencing – Home/Office geofence ထည့်ခြင်း / ဖယ်ခြင်း
 */
class GeofenceHelper(private val context: Context) {

    private val client: GeofencingClient = LocationServices.getGeofencingClient(context)

    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Home နဲ့ Office geofence များ ပြန်ထည့်မယ် */
    fun registerGeofences() {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation) {
            Log.w(TAG, "Skipping geofence registration: ACCESS_FINE_LOCATION not granted")
            return
        }
        val home = LocationTriggerStorage.getHomeLocation(context)
        val office = LocationTriggerStorage.getOfficeLocation(context)
        val list = mutableListOf<Geofence>()
        if (home != null) {
            list.add(
                Geofence.Builder()
                    .setRequestId(GeofenceReceiver.GEOFENCE_ID_HOME)
                    .setCircularRegion(home.lat, home.lng, home.radiusM)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            )
        }
        if (office != null) {
            list.add(
                Geofence.Builder()
                    .setRequestId(GeofenceReceiver.GEOFENCE_ID_OFFICE)
                    .setCircularRegion(office.lat, office.lng, office.radiusM)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            )
        }
        if (list.isEmpty()) {
            Log.d(TAG, "No geofences to register")
            return
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(list)
            .build()
        try {
            client.addGeofences(request, geofencePendingIntent())
                .addOnSuccessListener { Log.d(TAG, "Geofences registered: ${list.size}") }
                .addOnFailureListener { e -> Log.e(TAG, "Geofence register failed", e) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Geofence registration blocked by missing permission", e)
        }
    }

    /** Geofence အားလုံး ဖယ်မယ် */
    fun removeGeofences() {
        client.removeGeofences(geofencePendingIntent())
            .addOnSuccessListener { Log.d(TAG, "Geofences removed") }
            .addOnFailureListener { e -> Log.e(TAG, "Geofence remove failed", e) }
    }

    companion object {
        private const val TAG = "GeofenceHelper"
    }
}
