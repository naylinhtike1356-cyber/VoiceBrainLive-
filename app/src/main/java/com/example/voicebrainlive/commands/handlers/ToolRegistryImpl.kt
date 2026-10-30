package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.content.SharedPreferences
/**
 * Tool Registry Implementation - Routes commands to appropriate handlers.
 */
class ToolRegistryImpl(
    private val context: Context,
    private val prefs: SharedPreferences
) : ToolRegistry {

    private val approvalSession = java.util.UUID.randomUUID().toString()
    fun invalidateApprovals() = com.example.voicebrainlive.commands.SensitiveActionGuard.invalidate(approvalSession)
    private val phoneHandler = PhoneHandler(context, prefs)
    private val callCommands = CallCommands(context)
    private val musicCommands = MusicCommands(context)
    private val mediaHandler = MediaHandler(context, this)
    private val appHandler = AppHandler(context)
    private val utilityHandler = UtilityHandler(context, prefs)

    private val gemHandler = GemHandler(context)
    private val automationHandler = AutomationHandler(context)
    private val visionHandler = VisionHandler(context)
    private val smartHomeCommands = SmartHomeCommands(context)
    private val smartHomeHandler = SmartHomeHandler(context)
    private val translationHandler = TranslationHandler(context)
    private val creativeHandler = CreativeHandler(context)
    private val salaryHandler = SalaryHandler(context)
    private val locationHandler = LocationHandler(context)
    private val calendarHandler = CalendarHandler(context)
    private val accessibilityHandler = AccessibilityHandler(context)

    /**
     * Handlers for commands not covered by the legacy when-dispatch blocks.
     * Order matters when multiple handlers share a command alias.
     */
    private val specializedHandlers: List<CommandHandler> = listOf(
        com.example.voicebrainlive.routines.SleepWakeCommands(context, ::executeCommandAsync),
        gemHandler,
        automationHandler,
        visionHandler,
        smartHomeCommands,
        smartHomeHandler,
        locationHandler,
        calendarHandler,
        translationHandler,
        creativeHandler,
        salaryHandler,
        accessibilityHandler
    )

    override fun getCommandSpec(commandType: String): ToolCommandSpec? {
        return COMMAND_SPECS[commandType.lowercase()]
    }

    override suspend fun executeCommandAsync(
        commandType: String,
        target: String?,
        value: String?
    ): String? {
        if (!supportsCommand(commandType)) return null
        // UtilityHandler bytes stay untouched; its private settings write has one actual caller.
        if (commandType.equals("set_listening_timeout", true) && !com.example.voicebrainlive.SettingsActivity.isSecureSettingsAvailable(context))
            return com.example.voicebrainlive.security.SecureSettingsStorage.ERROR
        return com.example.voicebrainlive.commands.SensitiveActionGuard.run(context, approvalSession, commandType, target, value) {
            try { dispatchSpecialized(commandType, target, value) ?: executeLegacyDispatchAsync(commandType, target, value).orEmpty() }
            catch (_: com.example.voicebrainlive.security.SecureSettingsUnavailable) { com.example.voicebrainlive.security.SecureSettingsStorage.ERROR }
        }
    }

    fun supportsCommand(command: String): Boolean = command.trim().lowercase() in LEGACY_COMMANDS ||
        specializedHandlers.any { handler -> command.trim().lowercase() in handler.getSupportedCommands() }

    override fun executeCommand(commandType: String, target: String?, value: String?): String? {
        return kotlinx.coroutines.runBlocking {
            executeCommandAsync(commandType, target, value)
        }
    }

    private suspend fun dispatchSpecialized(commandType: String, target: String?, value: String?): String? {
        val cmd = commandType.lowercase()
        for (handler in specializedHandlers) {
            if (cmd in handler.getSupportedCommands().map { it.lowercase() }) {
                return handler.execute(commandType, target, value)
            }
        }
        return null
    }

    private suspend fun executeLegacyDispatchAsync(
        commandType: String,
        target: String?,
        value: String?
    ): String? {
        // Existing common guard has already run. Exact scheduled targets never fall back to aliases.
        if (commandType in setOf("open_app", "resume_music") && value?.startsWith(com.example.voicebrainlive.commands.ReviewedAppTarget.PREFIX) == true)
            return com.example.voicebrainlive.commands.ReviewedAppTarget.dispatch(context, commandType, target, value)
        return when (commandType.lowercase()) {
            // Calling commands
            "make_call", "call", "dial", "call_contact", "call_quick_contact", "contact_call",
            "search_phone_contacts", "call_phone_contact",
            "add_contact", "save_contact", "add_quick_contact",
            "edit_contact", "edit_quick_contact",
            "list_contacts", "get_contacts",
            "delete_contact" -> callCommands.execute(commandType, target, value)

            // Phone commands
            "record_correction", "save_correction",
            "send_sms",
            "send_messenger", "send_facebook_message", "messenger",
            "send_whatsapp", "whatsapp",
            "add_calendar_event" -> phoneHandler.execute(commandType, target, value)

            "play_music", "media_play",
            "play_spotify", "spotify_play",
            "search_spotify",
            "pause_music", "pause", "media_pause", "media_stop",
            "resume_music", "resume", "play",
            "next_track", "next", "media_next",
            "previous_track", "previous", "media_previous",
            "create_playlist", "add_to_playlist", "create_and_play_playlist" -> musicCommands.execute(commandType, target, value)

            "volume_up", "volume_down", "set_volume",
            "mute", "unmute",
            "set_ringer_silent", "silent_mode", "mute_ringer",
            "set_ringer_normal", "normal_mode", "unmute_ringer",
            "set_ringer_vibrate", "vibrate_mode", "vibration_on" -> mediaHandler.execute(commandType, target, value)

            "open_app",
            "open_youtube", "youtube", "play_youtube",
            "navigate_to", "directions",
            "web_search", "search",
            "search_news",
            "search_coupang",
            "search_aliexpress",
            "open_settings" -> appHandler.execute(commandType, target, value)

            "get_weather", "weather",
            "get_current_time", "get_time", "get_current_date", "get_date",
            "get_battery_status",
            "get_wifi_status",
            "get_bluetooth_status",
            "turn_on_wifi", "wifi_on",
            "turn_off_wifi", "wifi_off",
            "turn_on_bluetooth", "bluetooth_on",
            "turn_off_bluetooth", "bluetooth_off",
            "turn_on_flashlight", "flashlight_on",
            "turn_off_flashlight", "flashlight_off",
            "toggle_flashlight",
            "set_alarm",
            "dismiss_alarm",
            "set_timer",
            "set_reminder",
            "list_reminders", "get_reminders", "get_pending_reminders",
            "add_scheduled_routine", "add_routine",
            "list_routines", "get_routines", "list_scheduled_routines",
            "delete_scheduled_routine", "delete_routine", "remove_routine",
            "save_note", "add_note",
            "read_notes", "list_notes", "show_notes",
            "delete_note",
            "clear_notes", "delete_all_notes",
            "send_to_notion", "sync_notion", "notion_sync",
            "send_last_note_to_notion",
            "test_notion",
            "search_notion", "notion_search", "query_notion",
            "append_notion_note", "update_notion_note", "notion_append",
            "delete_notion_note", "archive_notion_note", "notion_delete",
            "set_listening_timeout",
            "screenshot",
            "read_action_logs",
            "save_preference", "remember_fact", "add_memory", "remember",
            "get_remembered_facts", "list_memories", "read_memories", "get_memories",
            "search_memory", "search_facts", "query_memory",
            "forget_memory", "delete_fact", "forget_fact", "remove_memory",
            "switch_profile" -> utilityHandler.execute(commandType, target, value)

            "get_health_summary", "health_summary", "get_health_data", "health",
            "get_sleep_data", "sleep_summary", "sleep",
            "get_activity_data", "steps_summary", "steps",
            "get_workout_data", "workout_summary", "workout" -> HealthHandler(context).execute(commandType, target, value)

            else -> null
        }
    }

    
    companion object {
        private val LEGACY_COMMANDS = setOf("add_calendar_event","add_contact","add_memory","add_note","add_quick_contact","add_routine","add_scheduled_routine","add_to_playlist","append_notion_note","archive_notion_note","bluetooth_off","bluetooth_on","call","call_contact","call_phone_contact","call_quick_contact","clear_notes","contact_call","create_and_play_playlist","create_playlist","delete_all_notes","delete_contact","delete_fact","delete_note","delete_notion_note","delete_routine","delete_scheduled_routine","dial","directions","dismiss_alarm","edit_contact","edit_quick_contact","flashlight_off","flashlight_on","forget_fact","forget_memory","get_activity_data","get_battery_status","get_bluetooth_status","get_contacts","get_current_date","get_current_time","get_date","get_health_data","get_health_summary","get_memories","get_pending_reminders","get_remembered_facts","get_reminders","get_routines","get_sleep_data","get_time","get_weather","get_wifi_status","get_workout_data","health","health_summary","list_contacts","list_memories","list_notes","list_reminders","list_routines","list_scheduled_routines","make_call","media_next","media_pause","media_play","media_previous","media_stop","messenger","mute","mute_ringer","navigate_to","next","next_track","normal_mode","notion_append","notion_delete","notion_search","notion_sync","open_app","open_settings","open_youtube","pause","pause_music","play","play_music","play_spotify","play_youtube","previous","previous_track","query_memory","query_notion","read_action_logs","read_memories","read_notes","record_correction","remember","remember_fact","remove_memory","remove_routine","resume","resume_music","save_contact","save_correction","save_note","save_preference","screenshot","search","search_aliexpress","search_coupang","search_facts","search_memory","search_news","search_notion","search_phone_contacts","search_spotify","send_facebook_message","send_last_note_to_notion","send_messenger","send_sms","send_to_notion","send_whatsapp","set_alarm","set_listening_timeout","set_reminder","set_ringer_normal","set_ringer_silent","set_ringer_vibrate","set_timer","set_volume","show_notes","silent_mode","sleep","sleep_summary","spotify_play","steps","steps_summary","switch_profile","sync_notion","test_notion","toggle_flashlight","turn_off_bluetooth","turn_off_flashlight","turn_off_wifi","turn_on_bluetooth","turn_on_flashlight","turn_on_wifi","unmute","unmute_ringer","update_notion_note","vibrate_mode","vibration_on","volume_down","volume_up","weather","web_search","whatsapp","wifi_off","wifi_on","workout","workout_summary","youtube")
        private val COMMAND_SPECS = buildMap {
            fun spec(
                command: String,
                permissions: Set<String> = emptySet(),
                description: String = ""
            ) {
                put(command, ToolCommandSpec(command, permissions, description))
            }

            spec("make_call", setOf(android.Manifest.permission.CALL_PHONE), "Call a contact or phone number")
            spec("call_contact", setOf(android.Manifest.permission.CALL_PHONE), "Call a contact")
            spec("send_sms", setOf(android.Manifest.permission.SEND_SMS), "Send an SMS")
            spec("send_messenger", description = "Reply through Messenger notifications")
            spec("send_whatsapp", description = "Reply through WhatsApp notifications")
            spec("get_weather", setOf(android.Manifest.permission.ACCESS_FINE_LOCATION), "Read current or forecast weather")
            spec("turn_on_flashlight", description = "Turn flashlight on")
            spec("turn_off_flashlight", description = "Turn flashlight off")
            spec("set_reminder", description = "Create a local reminder")
            spec("add_scheduled_routine", description = "Create a scheduled routine")
            spec("play_spotify", description = "Play music through Spotify")
            spec("web_search", description = "Search the web")
            spec("search_news", description = "Search recent news")
            spec("switch_gem", description = "Switch active gem persona")
            spec("get_relevant_memory", description = "Semantic memory search")
            spec("run_routine", description = "Run a named morning/evening routine")
            spec("smart_home", description = "Control smart home device")
            spec("set_home_location", setOf(android.Manifest.permission.ACCESS_FINE_LOCATION), "Save home geofence")
            spec("set_office_location", setOf(android.Manifest.permission.ACCESS_FINE_LOCATION), "Save office geofence")
            spec("set_office_wifi", description = "Save office WiFi SSID trigger")
            spec("get_calendar_events", description = "Read calendar for today or tomorrow")
            spec("take_photo", setOf(android.Manifest.permission.CAMERA), "Capture a camera frame")
            spec("analyze_frame", setOf(android.Manifest.permission.CAMERA), "Analyze the current camera frame")
            spec("switch_profile", description = "Switch active user profile")
            spec("click_text", description = "Click screen element by text")
            spec("click_at", description = "Click screen element at specific coordinates (x,y)")
            spec("scroll_screen", description = "Scroll screen up, down, left or right")
            spec("swipe_gesture", description = "Swipe screen from start to end coordinates (startX,startY,endX,endY)")
            spec("input_text", description = "Type text in currently focused input")
            spec("perform_system_action", description = "Perform system action like back, home, notifications, quick_settings")
            spec("read_screen_contents", description = "Dump all visible text and interactive elements on the active screen")
            spec("create_macro", description = "Create custom voice macro containing multiple commands")
            spec("run_macro", description = "Execute a previously defined custom voice macro")
            spec("create_and_play_playlist", description = "Create a custom playlist on Spotify with specified songs and play it immediately")
            spec("search_notion", description = "Search and consult notes from Notion second brain database")
            spec("append_notion_note", description = "Append new ideas or content to an existing Notion note")
            spec("delete_notion_note", description = "Delete or archive a note in Notion by title")
        }
    }
}
