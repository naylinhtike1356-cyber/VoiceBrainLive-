package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.commands.WebSearchHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * App Handler - Handles app-related commands
 * - open_app, open_youtube, play_youtube
 * - open_settings, navigate_to, directions
 * - web_search, search, search_news, search_coupang, search_aliexpress
 * 
 * This handler extracts app-related functionality from PhoneCommandExecutor
 * to improve code maintainability and reduce file size.
 */
class AppHandler(
    private val context: Context
) {
    companion object {
        private const val TAG = "AppHandler"
        
        private val APP_PACKAGES = mapOf(
            "youtube" to "com.google.android.youtube",
            "spotify" to "com.spotify.music",
            "music" to "com.spotify.music",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "calendar" to "com.google.android.calendar",
            "play_music" to "com.google.android.music",
            "settings" to "com.android.settings",
            "camera" to "com.android.camera",
            "facebook" to "com.facebook.katana",
            "fb" to "com.facebook.katana",
            "messenger" to "com.facebook.orca",
            "whatsapp" to "com.whatsapp",
            "wa" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "tg" to "org.telegram.messenger",
            "instagram" to "com.instagram.android",
            "ig" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "netflix" to "com.netflix.mediaclient",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "calculator" to "com.google.android.calculator",
            "calc" to "com.google.android.calculator",
            "files" to "com.google.android.apps.nbu.files",
            "file_manager" to "com.google.android.apps.nbu.files",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "contacts" to "com.google.android.contacts",
            "viber" to "com.viber.voip",
            "zoom" to "us.zoom.videomeetings",
            "teams" to "com.microsoft.teams",
            "zoom_meetings" to "us.zoom.videomeetings",
            "play_store" to "com.android.vending",
            "playstore" to "com.android.vending",
            "browser" to "com.android.chrome",
            "google" to "com.google.android.googlequicksearchbox",
            "google_app" to "com.google.android.googlequicksearchbox",
            "notes" to "com.google.android.keep",
            "keep" to "com.google.android.keep",
            "translate" to "com.google.android.apps.translate",
            "drive" to "com.google.android.apps.docs",
            "google_drive" to "com.google.android.apps.docs",
            // Myanmar app aliases
            "ဖေ့စ်ဘုတ်" to "com.facebook.katana",
            "ဖေ့စ်ဘု့" to "com.facebook.katana",
            "ဝက်အပ်" to "com.whatsapp",
            "ဝါ" to "com.whatsapp",
            "တီတော့" to "com.zhiliaoapp.musically",
            "တစ်တော့" to "com.zhiliaoapp.musically",
            "မက်ဆင်ဂျာ" to "com.facebook.orca",
            "မက်ဆင်" to "com.facebook.orca",
            "ဗိုက်ဘာ" to "com.viber.voip",
            "အင်စတာဂရမ်" to "com.instagram.android",
            "အင်စတာ" to "com.instagram.android",
            "ထီးတိုက်" to "org.telegram.messenger",
            "တယ်လီဂရမ်" to "org.telegram.messenger",
            "ယူကျု့" to "com.google.android.youtube",
            "ယူကျု့ဘ်" to "com.google.android.youtube",
            "စပီဒီဖိုင်" to "com.spotify.music",
            "စပီဒီဖေး" to "com.spotify.music",
            "မြေပုံ" to "com.google.android.apps.maps",
            "ဂူဂဲလ်မြေပုံ" to "com.google.android.apps.maps",
            "ကင်မရာ" to "com.android.camera",
            "ဆက်တင်း" to "com.android.settings",
            "ဆက်တင်" to "com.android.settings",
            "ဖုန်း" to "com.google.android.dialer",
            "ဖုန်းခေါ်" to "com.google.android.dialer",
            "ကယ်လ်ကူလေတာ" to "com.google.android.calculator",
            "ကယ်ကူလေတာ" to "com.google.android.calculator",
            "နော့" to "com.google.android.keep",
            "မှတ်စု" to "com.google.android.keep",
            "ချရွမ်း" to "com.android.chrome",
            "ဘရောက်ဆာ" to "com.android.chrome",
            // v2.2 Smart Home
            "google_home" to "com.google.android.apps.chromecast.app",
            "googlehome" to "com.google.android.apps.chromecast.app",
            "smart_home" to "com.google.android.apps.chromecast.app"
        )
        
        private val APP_PACKAGE_ALTERNATIVES = mapOf(
            "com.spotify.music" to listOf("com.google.android.apps.youtube.music", "com.google.android.music", "com.samsung.android.music"),
            "com.android.camera" to listOf("com.sec.android.app.camera", "com.samsung.android.camera"),
            "com.google.android.dialer" to listOf("com.samsung.android.dialer", "com.android.dialer"),
            "com.google.android.apps.messaging" to listOf("com.samsung.android.messaging", "com.android.mms"),
            "com.android.settings" to listOf("com.android.settings", "com.samsung.android.settings"),
            "com.google.android.youtube" to listOf("com.google.android.apps.youtube.kids"),
            "org.telegram.messenger" to listOf("org.telegram.plus", "org.telegram.BifToN")
        )
    }

    private val webSearchHelper by lazy { WebSearchHelper() }

    suspend fun execute(commandType: String, target: String?, value: String?): String {
        return withContext(Dispatchers.IO) {
            executeInternal(commandType, target, value)
        }
    }

    private suspend fun executeInternal(commandType: String, target: String?, value: String?): String {
        return when (commandType.lowercase()) {
            // App Control
            "open_app" -> openApp(target ?: "")
            "open_youtube", "youtube", "play_youtube" -> openYouTube(target)
            
            // Navigation
            "navigate_to", "directions" -> navigateTo(target ?: "")
            
            // Search
            "web_search", "search" -> webSearch(target ?: value ?: "")
            "search_news" -> searchNews(target ?: value ?: "")
            "search_coupang" -> searchCoupang(target ?: value ?: "")
            "search_aliexpress" -> searchAliExpress(target ?: value ?: "")
            
            // Settings
            "open_settings" -> openSettings(target)
            
            else -> "Unknown app command: $commandType"
        }
    }

    // ===== APP CONTROL =====
    private fun openApp(target: String): String {
        if (target.isBlank()) {
            return "Please tell me which app to open"
        }
        
        if (target.lowercase().contains("youtube")) {
            return openYouTube(null)
        }
        
        val normalized = target.lowercase().trim().replace(" ", "_")
        
        // First try hardcoded apps (including aliases)
        var packageName = APP_PACKAGES[normalized] ?: resolvePackageFromName(target)
        if (packageName != null) {
            val intent = tryLaunchPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opened $target"
            }
            val altPkgs = APP_PACKAGE_ALTERNATIVES[packageName]
            if (altPkgs != null) {
                for (alt in altPkgs) {
                    val altIntent = tryLaunchPackage(alt)
                    if (altIntent != null) {
                        altIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(altIntent)
                        return "Opened $target"
                    }
                }
            }
        }
        
        // Search all installed apps
        val foundApp = findInstalledApp(target)
        if (foundApp != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(foundApp.first)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opened ${foundApp.second}"
            }
        }
        
        return "App not found: $target"
    }
    
    private fun tryLaunchPackage(packageName: String): Intent? {
        return try {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } catch (e: Exception) {
            null
        }
    }
    
    /** When searching for Facebook, exclude Messenger (and vice versa) */
    private fun findInstalledApp(query: String): Pair<String, String>? {
        val pm = context.packageManager
        val q = query.lowercase(Locale.getDefault()).trim()
        val searchTerms = q.split(Regex("\\s+")).filter { it.length >= 2 }
        
        val isFacebookIntent = q in listOf("facebook", "fb", "ဖေ့စ်ဘုတ်", "ဖေ့စ်ဘု့")
        val isMessengerIntent = q in listOf("messenger", "messaging", "မက်ဆင်ဂျာ", "မက်ဆင်", "msg")
        fun filterFbMessenger(pkg: String, label: String): Boolean {
            return when {
                isFacebookIntent && pkg == "com.facebook.orca" -> false
                isMessengerIntent && pkg == "com.facebook.katana" -> false
                else -> true
            }
        }
        
        val installedApps = getLaunchableApps().filter { (pkg, label) -> filterFbMessenger(pkg, label) }
        if (installedApps.isEmpty()) return null
        
        // Exact match
        val exact = installedApps.find { (_, label) -> label.lowercase(Locale.getDefault()) == q }
        if (exact != null) return exact
        
        // Query equals label
        val eq = installedApps.find { (_, label) -> label.equals(query, ignoreCase = true) }
        if (eq != null) return eq
        
        // All search terms contained in label
        if (searchTerms.isNotEmpty()) {
            val allTerms = installedApps.find { (_, label) ->
                val labelLower = label.lowercase(Locale.getDefault())
                searchTerms.all { term -> labelLower.contains(term) }
            }
            if (allTerms != null) return allTerms
        }
        
        // Query contained in label
        val contains = installedApps.find { (_, label) ->
            label.lowercase(Locale.getDefault()).contains(q)
        }
        if (contains != null) return contains
        
        // Label contained in query
        val reverse = installedApps.find { (_, label) ->
            val labelLower = label.lowercase(Locale.getDefault())
            q.contains(labelLower) || labelLower.split(" ").any { word -> q.contains(word) && word.length >= 3 }
        }
        if (reverse != null) return reverse
        
        // Package name contains query
        val pkgMatch = installedApps.find { (pkg, _) ->
            pkg.lowercase(Locale.getDefault()).contains(q.replace(" ", ""))
        }
        if (pkgMatch != null) return pkgMatch
        
        // Single-word partial
        if (q.length >= 3) {
            val partial = installedApps.find { (_, label) ->
                label.lowercase(Locale.getDefault()).startsWith(q) ||
                label.lowercase(Locale.getDefault()).contains(q)
            }
            if (partial != null) return partial
        }
        
        return null
    }
    
    private fun getLaunchableApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val seen = mutableSetOf<String>()
        return resolveList.mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            if (seen.contains(pkg)) return@mapNotNull null
            seen.add(pkg)
            val label = info.loadLabel(pm).toString().trim()
            if (label.isBlank()) return@mapNotNull null
            Pair(pkg, label)
        }
    }
    
    fun listInstalledApps(): List<String> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app -> pm.getLaunchIntentForPackage(app.packageName) != null }
            .map { app -> pm.getApplicationLabel(app).toString() }
            .sorted()
    }

    private fun resolvePackageFromName(name: String): String? {
        val normalized = name.lowercase().replace(" ", "_")
        return APP_PACKAGES[normalized] ?: APP_PACKAGES.entries
            .find { it.key.contains(normalized) || normalized.contains(it.key) }?.value
    }

    // ===== YOUTUBE =====
    private fun openYouTube(query: String?): String {
        if (query.isNullOrBlank()) {
            return tryOpenYouTubeApp() ?: openYouTubeBrowser(null)
        }
        // For simplicity, just open YouTube app or browser
        return tryOpenYouTubeApp() ?: openYouTubeBrowser(query)
    }

    private fun tryOpenYouTubeApp(): String? {
        return try {
            val intent = (
                context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    ?: context.packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.kids")
                )?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (intent != null) { context.startActivity(intent); "YouTube ဖွင့်ပေးလိုက်ပါပြီ။" }
            else null
        } catch (e: Exception) { null }
    }

    private fun openYouTubeBrowser(query: String?): String {
        val url = if (!query.isNullOrBlank())
            "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        else "https://www.youtube.com"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            if (!query.isNullOrBlank()) "'$query' ကို YouTube မှာ ရှာပေးလိုက်ပါပြီ။"
            else "YouTube ဖွင့်ပေးလိုက်ပါပြီ။"
        } catch (e: Exception) { "YouTube ဖွင့်မရပါ။ ${e.message}" }
    }

    // ===== NAVIGATION =====
    private fun navigateTo(destination: String): String {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("google.navigation:q=$destination")
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Navigating to $destination"
        } catch (e: Exception) {
            "Could not open navigation: ${e.message}"
        }
    }

    // ===== WEB SEARCH =====
    private suspend fun webSearch(query: String): String {
        val gl = if (query.contains("မြန်မာ", ignoreCase = true) || query.contains("myanmar", ignoreCase = true)) "mm" else "kr"
        val result = webSearchHelper.searchWeb(query, gl)
        if (result.contains("API Key မထည့်ရသေးပါ")) {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=$encoded")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "webSearch: ${e.message}", e)
            }
            return "Google မှာ ရှာပေးလိုက်ပါပြီ: $query"
        }
        return result
    }

    /** News search */
    private suspend fun searchNews(query: String): String {
        val q = query.trim().ifBlank { "Korea news today" }
        val gl = if (q.contains("မြန်မာ", ignoreCase = true) || q.contains("myanmar", ignoreCase = true)) "mm" else "kr"
        val result = webSearchHelper.searchNews(q, gl)
        if (result.contains("API Key မထည့်ရသေးပါ")) {
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://news.google.com/search?q=$encoded")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "searchNews: ${e.message}", e)
            }
            return "Google News မှာ သတင်းရှာပေးလိုက်ပါပြီ: $q"
        }
        return result
    }

    /** Coupang search */
    private fun searchCoupang(query: String): String {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://www.coupang.com/np/search?q=$encoded"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Coupang"))
        } catch (e: Exception) {
            Log.e(TAG, "searchCoupang: ${e.message}")
        }
        return "Coupang မှာ ရှာနေပါတယ်: $query"
    }

    /** AliExpress search */
    private fun searchAliExpress(query: String): String {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://www.aliexpress.com/w/wholesale-$encoded.html"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "AliExpress"))
        } catch (e: Exception) {
            Log.e(TAG, "searchAliExpress: ${e.message}")
        }
        return "AliExpress မှာ ရှာနေပါတယ်: $query"
    }

    // ===== SETTINGS =====
    private fun openSettings(target: String?): String {
        val action = when (target?.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane", "flight" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opening ${target ?: "settings"}"
        } catch (e: Exception) {
            "Could not open settings: ${e.message}"
        }
    }
}
