package com.example.voicebrainlive.commands
import com.example.voicebrainlive.BuildConfig
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.ContactsContract
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.view.KeyEvent
import android.bluetooth.BluetoothAdapter
import com.example.voicebrainlive.AppLog
import com.example.voicebrainlive.ErrorUtils
import com.example.voicebrainlive.OfflineCommandMatcher
import com.example.voicebrainlive.CalendarHelper
import com.example.voicebrainlive.EmailHelper
import com.example.voicebrainlive.FocusMode
import com.example.voicebrainlive.GemManager
import com.example.voicebrainlive.LastResponseStore
import com.example.voicebrainlive.MeetingNotesHelper
import com.example.voicebrainlive.ReminderHelper
import com.example.voicebrainlive.ReminderReceiver
import com.example.voicebrainlive.SalaryDataReader
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.UserPreferences
import com.example.voicebrainlive.MediaSessionHelper
import com.example.voicebrainlive.SpotifyManager
import com.example.voicebrainlive.memory.BehaviorMemory
import com.example.voicebrainlive.learning.FeedbackLearning
import com.example.voicebrainlive.learning.IntentPatternLearner
import com.example.voicebrainlive.learning.PersonalProfileEngine
import com.example.voicebrainlive.learning.UserUtteranceContext
import com.example.voicebrainlive.routines.RoutineScheduler
import com.example.voicebrainlive.routines.RoutineStorage
import com.example.voicebrainlive.notion.NotionClient
import com.example.voicebrainlive.weather.WeatherAppReader
import okhttp3.OkHttpClient
import okhttp3.Request
import android.Manifest
import android.location.LocationManager
import android.location.LocationListener
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.example.voicebrainlive.messenger.MessengerApiClient
import com.example.voicebrainlive.messenger.MessengerNotificationReplyService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes phone commands requested by Gemini via function calls.
 * Supports: calls, volume, apps, WiFi, Bluetooth, flashlight, alarms, Spotify, YouTube, quick contacts.
 */
class PhoneCommandExecutor(private val context: Context) {

    private val approvalSession = java.util.UUID.randomUUID().toString()
    internal fun isAuthorizationSessionOpen(): Boolean = SensitiveActionGuard.store.epoch(approvalSession) != null

    fun invalidateApprovals() {
        try { SensitiveActionGuard.invalidate(approvalSession) }
        finally { toolRegistry.invalidateApprovals() }
    }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    private val prefs: SharedPreferences by lazy { 
        context.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE) 
    }
    private val notesPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("voice_notes", Context.MODE_PRIVATE)
    }
    private val notionClient by lazy { NotionClient(context) }
    private val notionRepository by lazy { com.example.voicebrainlive.notion.NotionRepository(context) }
    private val reminderPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("voice_reminders", Context.MODE_PRIVATE)
    }
    private val gemManager by lazy { GemManager(context) }
    private val calendarHelper by lazy { CalendarHelper(context) }
    private val emailHelper by lazy { EmailHelper(context) }
    private val meetingNotesHelper by lazy { MeetingNotesHelper(context) }
    private val noteRepository by lazy { com.example.voicebrainlive.memory.NoteRepository(context) }
    private var isFlashlightOn = false
    private var lastSavedNote: Pair<String, String>? = null
    private val httpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    private val webSearchHelper by lazy { com.example.voicebrainlive.commands.WebSearchHelper() }
    private val messengerApiClient by lazy { MessengerApiClient(context) }
    private val toolRegistry by lazy { com.example.voicebrainlive.commands.handlers.ToolRegistryImpl(context, prefs) }
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    companion object {
        private const val WEATHER_TAG = "Weather"
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
            // Myanmar app aliases (v1.6)
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


    suspend fun executeAsync(commandType: String, target: String?, value: String? = null): String {
        return withContext(Dispatchers.IO) {
            executeInternal(commandType, target, value)
        }
    }

    private suspend fun executeInternal(commandType: String, target: String?, value: String? = null): String {
        // Keep sensitive user content out of persistent action history and debug logs.
        AppLog.d("PhoneCommand", "Executing: $commandType, targetPresent=${!target.isNullOrBlank()}, valuePresent=${!value.isNullOrBlank()}")
        com.example.voicebrainlive.ActionRecordManager.recordAction(context, "Command", "Executing: $commandType, targetPresent=${!target.isNullOrBlank()}, valuePresent=${!value.isNullOrBlank()}")

        return try {
            // Phase 1.1: Delegate to ToolRegistry (for Refactored Handlers)
            toolRegistry.executeCommandAsync(commandType, target, value)?.let { 
                com.example.voicebrainlive.ActionRecordManager.recordAction(context, "Command", "Result: completed=${it.isNotBlank()}")
                return it 
            }

            val result = SensitiveActionGuard.run(context, approvalSession, commandType, target, value) {
                executeFallback(commandType, target, value)
            }
            com.example.voicebrainlive.ActionRecordManager.recordAction(context, "Command", "Result: completed=${result.isNotBlank()}")
            if (CommandSafetyPolicy.classify(commandType) == CommandSafetyPolicy.Classification.SAFE)
                recordLearnedIntentPattern(commandType, target, value)
            result
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            return ErrorUtils.toUserMessage(context, e.message ?: "Unknown error")
        }
    }

    private suspend fun executeFallback(commandType: String, target: String?, value: String?): String {
        return when (commandType.lowercase()) {
                // v2.0
                "repeat_last" -> repeatLast()
                "save_preference" -> savePreference(target ?: "", value ?: "")
                "set_focus_mode" -> setFocusMode(value?.toIntOrNull() ?: 30)
                "end_focus_mode" -> endFocusMode()
                
                // SalaryTracker Integration
                "get_salary_data", "query_salary" -> SalaryDataReader.getSalaryAnswer(context, target ?: value ?: "")
                "add_salary_today", "quick_add_salary" -> SalaryDataReader.insertSalaryAction(context, "quick_add_today")
                "copy_salary_yesterday" -> SalaryDataReader.insertSalaryAction(context, "copy_yesterday")
                
                // v2.0 Calendar Integration
                "schedule_today", "my_schedule" -> getScheduleToday()
                "schedule_tomorrow" -> getScheduleTomorrow()
                "add_meeting", "create_meeting" -> addMeeting(target ?: "Meeting", value)
                "open_calendar" -> openCalendar()
                
                // v2.0 Email Integration
                "open_gmail", "open_email", "gmail" -> emailHelper.handleEmailCommand("open_gmail", null, null)
                "compose_email", "send_email", "new_email" -> emailHelper.handleEmailCommand("compose_email", target, value)
                "search_email", "find_email" -> emailHelper.handleEmailCommand("search_email", target, null)
                "check_email", "inbox" -> emailHelper.handleEmailCommand("inbox", null, null)
                
                // v2.0 Meeting Notes
                "start_meeting", "new_meeting" -> meetingNotesHelper.handleVoiceCommand("start_meeting", target, value)
                "add_meeting_note", "take_note" -> meetingNotesHelper.handleVoiceCommand("add_note", target, null)
                "end_meeting", "close_meeting" -> meetingNotesHelper.handleVoiceCommand("end_meeting", null, null)
                "show_meeting_notes", "list_meetings" -> meetingNotesHelper.handleVoiceCommand("show_notes", null, null)
                
                // Notes (Local)
                "save_note", "add_note" -> saveNote(target ?: "", value)
                "read_notes", "list_notes", "show_notes" -> readNotes()
                "delete_note" -> deleteNote(target ?: "")
                "clear_notes", "delete_all_notes" -> clearAllNotes()
                
                // Notion Integration
                "send_to_notion", "sync_notion", "notion_sync" -> sendToNotion(target, value)
                "send_last_note_to_notion" -> sendLastNoteToNotion(value)
                "test_notion" -> testNotionConnection()
                "search_notion", "notion_search", "query_notion" -> notionRepository.searchAndDiscussNotes(target ?: value ?: "")
                "append_notion_note", "update_notion_note", "notion_append" -> notionRepository.appendToNoteByTitle(target ?: "", value ?: "")
                "delete_notion_note", "archive_notion_note", "notion_delete" -> notionRepository.deleteNoteByTitle(target ?: value ?: "")

                // Timeout
                "set_listening_timeout" -> setListeningTimeout(value?.toIntOrNull() ?: 10)

                // Macros (v3.0)
                "create_macro" -> createMacro(target ?: "", value ?: "")
                "run_macro" -> runMacro(target ?: value ?: "")

                // Memory Management
                "clear_memory", "reset_memory", "forget_everything" -> {
                    com.example.voicebrainlive.memory.MemoryResetHelper.resetAllMemory(context)
                    "မှတ်ဉာဏ်ဟောင်းများ အားလုံးကို ဖျက်ဆီးလိုက်ပါပြီ။ အခု အသစ်ကစပြီး ပြန်သင်ကြားပေးလို့ ရပါပြီ။"
                }

                else -> "Unknown command: $commandType"
            }
    }

    private fun createMacro(name: String, commandsSequence: String): String {
        if (name.isBlank() || commandsSequence.isBlank()) {
            return "မက်ခရိုအမည် သို့မဟုတ် ကွန်မန်ဒ်အစီအစဉ် မမှန်ကန်ပါ။"
        }
        val macroPrefs = context.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)
        macroPrefs.edit().putString(name.lowercase().trim(), commandsSequence).apply()
        return "မက်ခရို '$name' ကို အောင်မြင်စွာ ဖန်တီးပြီးပါပြီ။"
    }

    private suspend fun runMacro(name: String): String {
        if (name.isBlank()) return "မက်ခရိုအမည် မရှိပါ။"
        val macroPrefs = context.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)
        val sequence = macroPrefs.getString(name.lowercase().trim(), null)
            ?: return "မက်ခရို '$name' ကို ရှာမတွေ့ပါ။"
        
        val commandList = AutomationCommandValidator.extractSteps(sequence)
        val results = mutableListOf<String>()
        for (cmdStr in commandList) {
            val trimmed = cmdStr.trimStart()
            if (trimmed.isBlank()) continue
            
            val match = if (trimmed.contains(":")) null else OfflineCommandMatcher.match(trimmed)
            val (cmdType, tgt, valStr) = if (match != null) {
                match
            } else {
                val parts = trimmed.split(":", limit = 3)
                val cmdType = parts.getOrNull(0)?.trim() ?: continue
                val tgt = parts.getOrNull(1)
                val valStr = parts.getOrNull(2)
                Triple(cmdType, tgt, valStr ?: tgt)
            }
            
            AppLog.d("MacroExecutor", "Running macro step: $cmdType, targetPresent=${!tgt.isNullOrBlank()}, valuePresent=${!valStr.isNullOrBlank()}")
            val res = executeAsync(cmdType, tgt, valStr ?: tgt)

            results.add("$cmdType: $res")
            kotlinx.coroutines.delay(500)
        }
        return "မက်ခရို '$name' ပြီးဆုံးပါပြီ။ ရလဒ်များ- ${results.joinToString(", ")}"
    }

    private fun setListeningTimeout(seconds: Int): String {
        val prefs = SettingsActivity.getSecurePrefs(context)
        prefs.edit().apply {
            putInt(SettingsActivity.KEY_SILENCE_TIMEOUT_VALUE, seconds)
            putString(SettingsActivity.KEY_SILENCE_TIMEOUT_UNIT, "s")
            apply()
        }
        return "Listening timeout set to $seconds seconds."
    }


    // [Cleaned up duplicate legacy code - delegated to handlers]

    // ===== NOTES =====
    private suspend fun saveNote(content: String, category: String? = null): String {
        if (content.isBlank()) return "Please tell me what to note"
        val timestamp = System.currentTimeMillis()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val dateStr = dateFormat.format(java.util.Date(timestamp))
        val cat = when {
            category.isNullOrBlank() -> ""
            category.contains("idea", ignoreCase = true) -> "Idea"
            category.contains("korean", ignoreCase = true) -> "Korean"
            category.contains("work", ignoreCase = true) -> "Work"
            category.contains("general", ignoreCase = true) -> "General"
            else -> if (category.isNotBlank()) "Quick Note" else ""
        }
        val title = if (cat.isNotBlank()) "[$cat] $dateStr" else dateStr
        
        noteRepository.saveNote(title, content)
        lastSavedNote = Pair(dateStr, content)
        AppLog.d("PhoneCommand", "Note saved to Room: noteLength=${content.length} category=$cat")

        return "Note saved: $content"
    }

    private suspend fun readNotes(): String {
        val notionSummary = if (notionRepository.isConfigured()) {
            notionRepository.searchAndDiscussNotes("all")
        } else ""

        val notes = noteRepository.getAllNotes()
        val localSummary = if (notes.isNotEmpty()) {
            val notesList = notes.take(10).joinToString("\n") { "${it.title}: ${it.content}" }
            "Local Offline Notes:\n$notesList"
        } else ""

        if (notionSummary.isBlank() && localSummary.isBlank()) {
            return "No notes saved yet in Notion or local storage."
        }
        return listOf(notionSummary, localSummary).filter { it.isNotBlank() }.joinToString("\n\n---\n\n")
    }

    private suspend fun deleteNote(keyword: String): String {
        if (keyword.isBlank()) return "Please specify which note to delete"
        val deleted = noteRepository.deleteNoteByKeyword(keyword)
        return if (deleted) "Deleted note containing: $keyword" else "No note found with: $keyword"
    }

    private suspend fun clearAllNotes(): String {
        noteRepository.clearAll()
        return "All notes cleared from database"
    }

    fun getAllNotes(): List<Pair<String, String>> {
        return runBlocking(Dispatchers.IO) { getAllNotesAsync() }
    }

    suspend fun getAllNotesAsync(): List<Pair<String, String>> {
        return noteRepository.getAllNotes().map { Pair(it.title, it.content) }
    }

    // ===== NOTION INTEGRATION =====
    private suspend fun sendToNotion(arg1: String?, arg2: String?): String {
                AppLog.d("PhoneCommand", "sendToNotion called: arg1Present=${!arg1.isNullOrBlank()}, arg2Present=${!arg2.isNullOrBlank()}")

        if (!notionClient.isConfigured()) {
            Log.e("PhoneCommand", "Notion not configured")
            return "Notion not configured. Please add token and database ID in Settings."
        }
        if (arg1.isNullOrBlank() && arg2.isNullOrBlank()) {
            return "Please tell me what to send to Notion"
        }
        val content: String
        val title: String
        var noteCategory = "Quick Note"
        if (!arg2.isNullOrBlank() && !arg2.equals("Idea", true) && !arg2.equals("Korean", true) && !arg2.equals("Quick Note", true) && !arg2.equals("Work", true)) {
            title = arg1 ?: "Note"
            content = arg2
        } else {
            content = arg1 ?: ""
            if (!arg2.isNullOrBlank()) noteCategory = arg2
            title = content.take(50).let { if (content.length > 50) "$it..." else it }
        }
        return notionRepository.createNote(title, content, noteCategory)
    }

    private suspend fun sendLastNoteToNotion(category: String?): String {
        AppLog.d("PhoneCommand", "sendLastNoteToNotion called: categoryPresent=${!category.isNullOrBlank()}")

        if (!notionClient.isConfigured()) {
            return "Notion not configured. Please add token and database ID in Settings."
        }
        
        val lastNote = lastSavedNote
        if (lastNote == null) {
            // Try to get the most recent note from storage
            val allNotes = getAllNotes()
            if (allNotes.isEmpty()) {
                return "No notes to send. Please save a note first."
            }
            val recentNote = allNotes.first()
            return sendToNotion(recentNote.second, category)
        }
        
        return sendToNotion(lastNote.second, category)
    }

    private fun testNotionConnection(): String {
        return notionClient.testConnection()
    }

    // ===== v2.0: Repeat, Preferences, Focus, Routine, Quiz =====
    private fun repeatLast(): String {
        val text = LastResponseStore.get()
        return if (text.isBlank()) "Nothing to repeat yet. Ask something first." else text
    }

    private fun recordLearnedIntentPattern(commandType: String, target: String?, value: String?) {
        val learnableCommands = setOf(
            "make_call", "call", "call_contact", "play_spotify", "play_music", "web_search", "search",
            "send_sms", "set_alarm", "set_reminder", "open_app", "navigate_to", "search_news",
            "save_note", "get_weather", "weather", "toggle_flashlight", "turn_on_flashlight",
            "pause_music", "resume_music", "next_track", "previous_track"
        )
        val cmd = commandType.lowercase()
        if (cmd !in learnableCommands) return

        val userPhrase = UserUtteranceContext.consume() ?: UserUtteranceContext.peek()
        val phrase = when {
            !userPhrase.isNullOrBlank() && userPhrase.length in 2..80 -> userPhrase
            !target.isNullOrBlank() && target.length in 2..80 && target.split(" ").size <= 8 -> target
            else -> return
        }
        val patternTarget = when {
            !target.isNullOrBlank() -> target
            !value.isNullOrBlank() -> value
            else -> ""
        }
        IntentPatternLearner.recordPattern(
            context = context,
            phrase = phrase,
            commandType = cmd,
            target = patternTarget
        )
    }

    private fun savePreference(key: String, value: String): String {
        if (key.isBlank() || value.isBlank()) return "Preference key နဲ့ value ပြောပေးပါ။ ဥပမာ: သီးချင်းဖွင့်မယ်ဆိုရင် Lo-fi ပဲ ဖွင့်ပေး"
        val k = key.trim().lowercase().replace(" ", "_")
        val v = value.trim()
        UserPreferences.set(context, k, v)
        if (k in setOf("name", "user_name", "display_name", "နာမည်")) {
            PersonalProfileEngine.setFact(context, PersonalProfileEngine.CAT_IDENTITY, "name", v)
        }
        // User Profile: music_volume_preference, preferred_music_genre -> BehaviorMemory for proactive
        when (k) {
            "music_volume", "music_volume_preference", "volume_preference" -> {
                if (v.contains("တိုး", ignoreCase = true) || v.contains("low", ignoreCase = true) || v.contains("လျှော့", ignoreCase = true)) {
                    BehaviorMemory.setMusicVolumePreference(context, "low")
                } else {
                    BehaviorMemory.setMusicVolumePreference(context, "normal")
                }
            }
            "play_music", "preferred_music", "preferred_music_genre" -> {
                BehaviorMemory.setPreferredMusicGenre(context, v)
            }
        }
        return "မှတ်ပြီးပါပြီ။ နောက်တစ်ခါ $k အတွက် $v သုံးပေးမယ်။"
    }

    private fun setFocusMode(minutes: Int): String {
        val ms = minutes * 60_000L
        FocusMode.setFocusUntil(context, System.currentTimeMillis() + ms)
        return "Focus mode for $minutes minutes. Say 'wake up' or 'end focus' to cancel."
    }

    private fun endFocusMode(): String {
        FocusMode.clear(context)
        return "Focus mode ended"
    }

    fun getActiveGemSystemPrompt(): String {
        return gemManager.getSystemPromptForActiveGem()
    }

    /** Proactive Greeting: fetch weather + today's schedule + Preference Evolution suggestion. Call from IO. */
    fun getProactiveGreetingContext(): String {
        return runBlocking(Dispatchers.IO) { getProactiveGreetingContextAsync() }
    }

    suspend fun getProactiveGreetingContextAsync(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when {
            hour in 5..11 -> "မနက်ခင်း"
            hour in 12..17 -> "နေ့လယ်"
            hour in 18..21 -> "ညနေ"
            else -> "ညခင်း"
        }
        val weather = toolRegistry.executeCommandAsync("get_weather", null, null) ?: ""
        val schedule = getScheduleToday()
        val sb = StringBuilder("အချိန်: $timeOfDay. ")
        if (weather.isNotBlank() && !weather.contains("မရပါ") && !weather.contains("error", ignoreCase = true)) {
            sb.append("ရာသီဥတု: ").append(weather.take(180)).append(". ")
        }
        if (schedule.isNotBlank() && !schedule.contains("No events") && !schedule.contains("Could not")) {
            sb.append("ယနေ့: ").append(schedule.take(180)).append(". ")
        }
        // Preference Evolution: suggest calling frequent contact
        val frequent = BehaviorMemory.getMostFrequentContact(context)
        if (!frequent.isNullOrBlank()) {
            sb.append("မကြာခဏ ခေါ်ဆက်တဲ့သူ: $frequent. သူ့ကို ဒီနေ့ ခေါ်ဆက်မလား မေးနိုင်ပါတယ်။ ")
        }
        // Auto-action: Last music suggestion for familiarity
        val lastMusic = BehaviorMemory.getLastMusic(context)
        if (!lastMusic.isNullOrBlank()) {
            sb.append("နောက်ဆုံး ဖွင့်ခဲ့တဲ့ သီချင်း: $lastMusic. ပြန်ဖွင့်မလား မေးနိုင်ပါတယ်။ ")
        }
        
        // Active Reminders Context (AI Agent capacity)
        val activeReminders = ReminderHelper.getReminders(context).filter { !it.isPast }.take(3)
        if (activeReminders.isNotEmpty()) {
            sb.append("သတိပေးစရာများ: ")
            activeReminders.forEach { sb.append("'${it.message}' (${it.formatTime(context)}). ") }
        }
        
        // Scheduled Routines Context
        val routines = RoutineStorage.getAll(context).take(3)
        if (routines.isNotEmpty()) {
            sb.append("နေ့စဉ် လုပ်ဆောင်ချက်များ: ")
            routines.forEach { 
                val type = when(it.type) {
                    "weather" -> "ရာသီဥတု"
                    "music" -> "သီချင်းဖွင့်"
                    else -> "သတိပေး"
                }
                sb.append("အချိန် $it.hour:$it.minute ($type). ")
            }
        }
        // ရင်းနှီးမှု: Session count မြင့်ရင် proactive ပိုပြီး
        val sessionCount = BehaviorMemory.getSessionCount(context)
        if (sessionCount >= 10) {
            sb.append("User က ငါ့နဲ့ ရင်းနှီးပြီးသား (${sessionCount}+ sessions). Proactive ပိုပြီး ကြိုဆိုပြီး အကြံပြုနိုင်ပါတယ်။")
        }
        return sb.toString().trim()
    }

    suspend fun isRainExpectedToday(): Boolean {
        return try {
            val result = executeAsync("get_weather", null, null)
            if (result.startsWith("WEATHER_DATA|")) {
                val parts = result.split("|")
                parts.getOrNull(6)?.toBoolean() ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    // ===== v2.0: Calendar Helper Methods (suspend on IO to avoid blocking Main) =====
    private suspend fun getScheduleToday(): String {
        return try {
            val events = calendarHelper.getTodayEvents()
            if (events.isEmpty()) "No events scheduled for today"
            else calendarHelper.formatEventsForVoice(events)
        } catch (e: Exception) {
            Log.w("PhoneCommand", "getScheduleToday: ${e.message}")
            "Could not get schedule: ${e.message}"
        }
    }

    private suspend fun getScheduleTomorrow(): String {
        return try {
            val events = calendarHelper.getTomorrowEvents()
            if (events.isEmpty()) "No events scheduled for tomorrow"
            else "Tomorrow: ${calendarHelper.formatEventsForVoice(events)}"
        } catch (e: Exception) {
            Log.w("PhoneCommand", "getScheduleTomorrow: ${e.message}")
            "Could not get schedule: ${e.message}"
        }
    }

    private suspend fun addMeeting(title: String, timeStr: String?): String {
        return try {
            val startTime = ReminderHelper.parseTimeInput(context, timeStr ?: "")?.timeInMillis
                ?: (System.currentTimeMillis() + 3600000)
            val eventId = calendarHelper.createEvent(title = title, startTime = startTime)
            if (eventId > 0) "Meeting '$title' added to calendar"
            else "Could not add meeting. Check calendar permissions."
        } catch (e: Exception) {
            Log.w("PhoneCommand", "addMeeting: ${e.message}")
            "Could not add meeting: ${e.message}"
        }
    }
    
    private fun openCalendar(): String {
        calendarHelper.openCalendar()
        return "Opening calendar"
    }

    /** Memory improvement: မေးခွန်းမျိုးအလိုက် semantic search – query expansion နဲ့ ပိုတိကျအောင် */
}
