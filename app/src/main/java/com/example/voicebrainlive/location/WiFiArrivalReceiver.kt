package com.example.voicebrainlive.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Event-Driven: Office WiFi ချိတ်ဆက်တဲ့အခါ "ခုပဲ ရောက်တာလား" ပြောမယ်
 */
class WiFiArrivalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.NETWORK_STATE_CHANGED_ACTION) return

        val officeSsid = LocationTriggerStorage.getOfficeSsid(context) ?: return
        val currentSsid = getCurrentSsid(context) ?: return

        val normalizedOffice = normalizeSsid(officeSsid)
        val normalizedCurrent = normalizeSsid(currentSsid)
        if (normalizedCurrent != normalizedOffice) return

        if (!LocationTriggerStorage.shouldTrigger(context, normalizedCurrent)) return
        LocationTriggerStorage.markTriggered(context, normalizedCurrent)

        Log.d(TAG, "Office WiFi arrived: $currentSsid")
        val serviceIntent = Intent(context, LocationArrivalSpeakerService::class.java).apply {
            putExtra(LocationArrivalSpeakerService.EXTRA_MESSAGE, "ခုပဲ ရောက်တာလား")
            putExtra(LocationArrivalSpeakerService.EXTRA_ACTION, LocationArrivalSpeakerService.ACTION_OFFICE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun getCurrentSsid(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo ?: return null
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ getSSID() deprecated
                info.ssid?.let { if (it == "<unknown ssid>") null else it }
            } else {
                @Suppress("DEPRECATION")
                info.ssid?.takeIf { it != "<unknown ssid>" }
            }
            ssid
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentSsid: ${e.message}")
            null
        }
    }

    private fun normalizeSsid(ssid: String): String {
        return ssid.trim().removeSurrounding("\"").trim().lowercase()
    }

    /** Call from MainActivity onResume - when user opens app at office, trigger "ခုပဲ ရောက်တာလား" */
    companion object {
        private const val TAG = "WiFiArrival"

        fun checkAndTriggerOnResume(context: Context) {
            val officeSsid = LocationTriggerStorage.getOfficeSsid(context) ?: return
            val currentSsid = getCurrentSsidStatic(context) ?: return
            val normalizedOffice = officeSsid.trim().removeSurrounding("\"").trim().lowercase()
            val normalizedCurrent = currentSsid.trim().removeSurrounding("\"").trim().lowercase()
            if (normalizedCurrent != normalizedOffice) return
            if (!LocationTriggerStorage.shouldTrigger(context, normalizedCurrent)) return
            LocationTriggerStorage.markTriggered(context, normalizedCurrent)
            val intent = Intent(context, LocationArrivalSpeakerService::class.java).apply {
                putExtra(LocationArrivalSpeakerService.EXTRA_MESSAGE, "ခုပဲ ရောက်တာလား")
                putExtra(LocationArrivalSpeakerService.EXTRA_ACTION, LocationArrivalSpeakerService.ACTION_OFFICE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun getCurrentSsidStatic(context: Context): String? {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return null
                val caps = cm.getNetworkCapabilities(network) ?: return null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wifi?.connectionInfo ?: return null
                @Suppress("DEPRECATION")
                val raw = info.ssid ?: return null
                if (raw == "<unknown ssid>") return null
                raw.trim().removeSurrounding("\"").trim()
            } catch (e: Exception) {
                Log.e(TAG, "getCurrentSsid: ${e.message}")
                null
            }
        }
    }
}
