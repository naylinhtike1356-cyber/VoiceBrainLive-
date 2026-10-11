package com.example.voicebrainlive.location

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Event-Driven: Office / Home / နေရာ triggers
 * - WiFi SSID သိမ်းပြီး ရောက်တဲ့အခါ သတိပေး
 * - Phase 2.1: Geofencing – Home/Office lat,lng သိမ်းပြီး GPS ရောက်တဲ့အခါ trigger
 */
object LocationTriggerStorage {

    private const val FILE_NAME = "location_triggers.json"
    private const val COOLDOWN_MS = 2 * 60 * 60 * 1000L  // 2 hours - don't repeat same trigger
    private const val DEFAULT_RADIUS_M = 150f

    private fun getFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): JSONObject {
        return try {
            val file = getFile(context)
            if (!file.exists()) return JSONObject()
            val json = file.readText()
            if (json.isBlank()) return JSONObject()
            JSONObject(json)
        } catch (e: Exception) {
            Log.e("LocationTrigger", "load", e)
            JSONObject()
        }
    }

    private fun save(context: Context, obj: JSONObject) {
        try {
            getFile(context).writeText(obj.toString())
        } catch (e: Exception) {
            Log.e("LocationTrigger", "save", e)
        }
    }

    /** Set office WiFi SSID - call when user says "ဒီ WiFi ကို Office လို့ သတ်မှတ်ပါ" */
    fun setOfficeSsid(context: Context, ssid: String) {
        if (ssid.isBlank()) return
        try {
            val obj = load(context)
            obj.put("office_ssid", ssid.trim())
            save(context, obj)
            Log.d("LocationTrigger", "setOfficeSsid: $ssid")
        } catch (e: Exception) {
            Log.e("LocationTrigger", "setOfficeSsid", e)
        }
    }

    /** Get stored office SSID (without quotes) */
    fun getOfficeSsid(context: Context): String? {
        return try {
            val obj = load(context)
            val s = obj.optString("office_ssid", "").trim()
            s.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // --- Phase 2.1 Geofencing: Home / Office GPS ---

    data class GeoPoint(val lat: Double, val lng: Double, val radiusM: Float = DEFAULT_RADIUS_M)

    fun setHomeLocation(context: Context, lat: Double, lng: Double, radiusM: Float = DEFAULT_RADIUS_M) {
        try {
            val obj = load(context)
            obj.put("home_lat", lat)
            obj.put("home_lng", lng)
            obj.put("home_radius", radiusM.toDouble())
            save(context, obj)
            Log.d("LocationTrigger", "setHomeLocation: $lat,$lng r=$radiusM")
        } catch (e: Exception) {
            Log.e("LocationTrigger", "setHomeLocation", e)
        }
    }

    fun getHomeLocation(context: Context): GeoPoint? {
        return try {
            val obj = load(context)
            val lat = obj.optDouble("home_lat", 0.0)
            val lng = obj.optDouble("home_lng", 0.0)
            if (lat == 0.0 && lng == 0.0) null
            else GeoPoint(lat, lng, obj.optDouble("home_radius", DEFAULT_RADIUS_M.toDouble()).toFloat())
        } catch (e: Exception) {
            null
        }
    }

    fun setOfficeLocation(context: Context, lat: Double, lng: Double, radiusM: Float = DEFAULT_RADIUS_M) {
        try {
            val obj = load(context)
            obj.put("office_lat", lat)
            obj.put("office_lng", lng)
            obj.put("office_radius", radiusM.toDouble())
            save(context, obj)
            Log.d("LocationTrigger", "setOfficeLocation: $lat,$lng r=$radiusM")
        } catch (e: Exception) {
            Log.e("LocationTrigger", "setOfficeLocation", e)
        }
    }

    fun getOfficeLocation(context: Context): GeoPoint? {
        return try {
            val obj = load(context)
            val lat = obj.optDouble("office_lat", 0.0)
            val lng = obj.optDouble("office_lng", 0.0)
            if (lat == 0.0 && lng == 0.0) null
            else GeoPoint(lat, lng, obj.optDouble("office_radius", DEFAULT_RADIUS_M.toDouble()).toFloat())
        } catch (e: Exception) {
            null
        }
    }

    fun shouldTriggerGeofence(context: Context, placeId: String): Boolean {
        val key = "last_geofence_$placeId"
        val prefs = context.getSharedPreferences("location_trigger", Context.MODE_PRIVATE)
        val last = prefs.getLong(key, 0L)
        return System.currentTimeMillis() - last > COOLDOWN_MS
    }

    fun markGeofenceTriggered(context: Context, placeId: String) {
        context.getSharedPreferences("location_trigger", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_geofence_$placeId", System.currentTimeMillis())
            .apply()
    }

    /** Check if we should trigger (cooldown elapsed) */
    fun shouldTrigger(context: Context, ssid: String): Boolean {
        val key = "last_trigger_${ssid.hashCode()}"
        val prefs = context.getSharedPreferences("location_trigger", Context.MODE_PRIVATE)
        val last = prefs.getLong(key, 0L)
        return System.currentTimeMillis() - last > COOLDOWN_MS
    }

    /** Mark triggered (update cooldown) */
    fun markTriggered(context: Context, ssid: String) {
        val key = "last_trigger_${ssid.hashCode()}"
        context.getSharedPreferences("location_trigger", Context.MODE_PRIVATE)
            .edit()
            .putLong(key, System.currentTimeMillis())
            .apply()
    }
}
