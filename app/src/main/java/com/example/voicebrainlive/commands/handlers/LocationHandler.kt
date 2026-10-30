package com.example.voicebrainlive.commands.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.location.GeofenceHelper
import com.example.voicebrainlive.location.LocationTriggerStorage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Voice commands for home/office geofence and office WiFi triggers.
 */
class LocationHandler(private val context: Context) : CommandHandler {

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    private val geofenceHelper by lazy { GeofenceHelper(context) }

    override fun getSupportedCommands(): List<String> = listOf(
        "set_home_location",
        "set_home",
        "set_office_location",
        "set_office",
        "set_office_wifi"
    )

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "set_home_location", "set_home" -> setHomeLocation(target, value)
            "set_office_location", "set_office" -> setOfficeLocation(target, value)
            "set_office_wifi" -> setOfficeWifi(target ?: value)
            else -> null
        }
    }

    private suspend fun setHomeLocation(target: String?, value: String?): String {
        return setGeoPlace(
            placeLabel = "အိမ်",
            parseCoords = parseCoords(target, value),
            save = { lat, lng ->
                LocationTriggerStorage.setHomeLocation(context, lat, lng)
            }
        )
    }

    private suspend fun setOfficeLocation(target: String?, value: String?): String {
        return setGeoPlace(
            placeLabel = "ရုံး",
            parseCoords = parseCoords(target, value),
            save = { lat, lng ->
                LocationTriggerStorage.setOfficeLocation(context, lat, lng)
            }
        )
    }

    private suspend fun setGeoPlace(
        placeLabel: String,
        parseCoords: Pair<Double, Double>?,
        save: (Double, Double) -> Unit
    ): String {
        val coords = parseCoords
        if (coords != null) {
            save(coords.first, coords.second)
            geofenceHelper.registerGeofences()
            return "$placeLabel နေရာကို သတ်မှတ်ပြီးပါပြီ (${coords.first}, ${coords.second})"
        }
        if (!hasLocationPermission()) {
            return "Location ခွင့်ပြုချက် လိုအပ်ပါတယ်။ Settings မှာ ခွင့်ပြုပေးပါ။"
        }
        val location = fetchLastLocation()
            ?: return "လက်ရှိ GPS မရနိုင်သေးပါ။ အပြင်ထွက်ပြီး နေရာရှာဖွေမှု ဖွင့်ပြီး ထပ်စမ်းပါ။"
        save(location.latitude, location.longitude)
        geofenceHelper.registerGeofences()
        return "$placeLabel ကို လက်ရှိနေရာအဖြစ် သတ်မှတ်ပြီးပါပြီ။"
    }

    private fun setOfficeWifi(ssid: String?): String {
        val resolved = ssid?.trim()?.takeIf { it.isNotBlank() }
            ?: readCurrentWifiSsid()
            ?: return "Office WiFi အမည် မရနိုင်ပါ။ WiFi ချိတ်ဆက်ပြီး ထပ်စမ်းပါ သို့မဟုတ် SSID ကို ပြောပြပါ။"
        LocationTriggerStorage.setOfficeSsid(context, resolved)
        return "Office WiFi '$resolved' အဖြစ် သတ်မှတ်ပြီးပါပြီ။"
    }

    private fun parseCoords(target: String?, value: String?): Pair<Double, Double>? {
        val combined = listOfNotNull(target, value).joinToString(",").trim()
        if (combined.isBlank()) return null
        val parts = combined.split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        return lat to lng
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun fetchLastLocation() = withContext(Dispatchers.IO) {
        try {
            kotlin.coroutines.coroutineContext.ensureActive()
            // Preserve the existing fine-location policy and recheck at the API boundary.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return@withContext null
            val location = Tasks.await(fusedLocationClient.lastLocation)
            kotlin.coroutines.coroutineContext.ensureActive()
            location
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission unavailable")
            null
        } catch (e: Exception) {
            Log.w(TAG, "fetchLastLocation", e)
            null
        }
    }

    private fun readCurrentWifiSsid(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo ?: return null
            val raw = info.ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: return null
            raw.trim().removeSurrounding("\"")
        } catch (e: Exception) {
            Log.w(TAG, "readCurrentWifiSsid", e)
            null
        }
    }

    companion object {
        private const val TAG = "LocationHandler"
    }
}
