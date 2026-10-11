package com.example.voicebrainlive

/**
 * v2.3: Enhanced Offline voice command matching
 * Maps phrases to execute_phone_command when offline.
 */
object OfflineCommandMatcher {

    private val KNOWN_COMMANDS = setOf(
        "make_call", "call", "dial", "call_contact", "call_quick_contact", "contact_call",
        "search_phone_contacts", "call_phone_contact", "add_contact", "save_contact",
        "add_quick_contact", "edit_contact", "edit_quick_contact", "list_contacts",
        "get_contacts", "delete_contact", "record_correction", "save_correction",
        "send_sms", "send_messenger", "send_facebook_message", "messenger",
        "send_whatsapp", "whatsapp", "add_calendar_event", "play_music", "media_play",
        "play_spotify", "spotify_play", "search_spotify", "pause_music", "pause",
        "media_pause", "media_stop", "resume_music", "resume", "play", "next_track",
        "next", "media_next", "previous_track", "previous", "media_previous",
        "volume_up", "volume_down", "set_volume", "mute", "unmute", "set_ringer_silent",
        "silent_mode", "mute_ringer", "set_ringer_normal", "normal_mode", "unmute_ringer",
        "set_ringer_vibrate", "vibrate_mode", "vibration_on", "open_app", "open_youtube",
        "youtube", "play_youtube", "navigate_to", "directions", "web_search", "search",
        "search_news", "search_coupang", "search_aliexpress", "open_settings", "get_weather",
        "weather", "get_current_time", "get_time", "get_current_date", "get_date",
        "get_battery_status", "get_wifi_status", "get_bluetooth_status", "turn_on_wifi",
        "wifi_on", "turn_off_wifi", "wifi_off", "turn_on_bluetooth", "bluetooth_on",
        "turn_off_bluetooth", "bluetooth_off", "turn_on_flashlight", "flashlight_on",
        "turn_off_flashlight", "flashlight_off", "toggle_flashlight", "set_alarm",
        "dismiss_alarm", "set_timer", "set_reminder", "list_reminders", "get_reminders",
        "get_pending_reminders", "add_scheduled_routine", "add_routine", "list_routines",
        "get_routines", "list_scheduled_routines", "delete_scheduled_routine", "delete_routine",
        "remove_routine", "save_note", "add_note", "read_notes", "list_notes", "show_notes",
        "delete_note", "clear_notes", "delete_all_notes", "send_to_notion", "sync_notion",
        "notion_sync", "send_last_note_to_notion", "test_notion", "set_listening_timeout",
        "screenshot", "read_action_logs", "switch_profile", "run_routine", "quiz_from_notion",
        "get_relevant_memory", "search_memory", "create_macro", "run_macro", "click_text",
        "scroll_screen", "input_text", "perform_system_action", "create_and_play_playlist",
        "set_focus_mode", "end_focus_mode", "click_at", "swipe_gesture",
        "search_notion", "append_notion_note", "delete_notion_note"
    )

    /** Phrases (lowercase) -> (command, target?, value?) */
    private val PHRASES = mapOf(
        // Phone calls (only specific unambiguous phrases)
        "ဖုန်းခေါ်မယ်" to Triple("make_call", "", null),
        "ဖုန်းဆက်မယ်" to Triple("make_call", "", null),
        "ဖုန်းဖွင့်" to Triple("open_app", "dialer", null),
        "dialer" to Triple("open_app", "dialer", null),
        "phone" to Triple("open_app", "dialer", null),
        
        // Flashlight
        "မီးအိမ်" to Triple("toggle_flashlight", null, null),
        "မီးအိမ်ဖွင့်" to Triple("turn_on_flashlight", null, null),
        "မီးအိမ်ပိတ်" to Triple("turn_off_flashlight", null, null),
        "မီးဖွင့်ပေး" to Triple("turn_on_flashlight", null, null),
        "မီးပိတ်ပေး" to Triple("turn_off_flashlight", null, null),
        "flashlight" to Triple("toggle_flashlight", null, null),
        "flashlight on" to Triple("turn_on_flashlight", null, null),
        "flashlight off" to Triple("turn_off_flashlight", null, null),
        "torch" to Triple("toggle_flashlight", null, null),
        
        // Smart Home (v2.2)
        "မီးပိတ်" to Triple("turn_off_all_lights", null, null),
        "မီးတွေအကုန်ပိတ်" to Triple("turn_off_all_lights", null, null),
        "မီးပိတ်ပေး" to Triple("turn_off_all_lights", null, null),
        "မီးပိတ်ပေးပါ" to Triple("turn_off_all_lights", null, null),
        "all lights off" to Triple("turn_off_all_lights", null, null),
        "ဧည့်ခန်းမီး" to Triple("smart_home", "ဧည့်ခန်းမီး", "toggle"),
        "အိပ်ခန်းမီး" to Triple("smart_home", "အိပ်ခန်းမီး", "toggle"),
        "မီးဖွင့်" to Triple("smart_home", "မီး", "on"),
        "မီးဖွင့်ပေး" to Triple("smart_home", "မီး", "on"),
        "မီးဖွင့်ပေးပါ" to Triple("smart_home", "မီး", "on"),
        "ပန်ကာ" to Triple("smart_home", "ပန်ကာ", "toggle"),
        "အဲကွန်း" to Triple("smart_home", "အဲကွန်း", "toggle"),
        
        // Volume
        "ဝေါလ်ယမ်တိုး" to Triple("volume_up", null, null),
        "ဝေါလ်ယမ်လျော့" to Triple("volume_down", null, null),
        "အသံတိုး" to Triple("volume_up", null, null),
        "အသံလျော့" to Triple("volume_down", null, null),
        "volume up" to Triple("volume_up", null, null),
        "volume down" to Triple("volume_down", null, null),
        "louder" to Triple("volume_up", null, null),
        "quieter" to Triple("volume_down", null, null),
        "mute" to Triple("mute", null, null),
        "အသံပိတ်" to Triple("mute", null, null),
        // Ringer / Silent / Vibration (အသံပိတ်မုဒ်၊ တုန်ခါမှု)
        "အသံပိတ်မုဒ်" to Triple("set_ringer_silent", null, null),
        "ဆိုင်းလ့မုဒ်" to Triple("set_ringer_silent", null, null),
        "silent mode" to Triple("set_ringer_silent", null, null),
        "နားချိန်မုဒ်" to Triple("set_ringer_silent", null, null),
        "အသံဖွင့်" to Triple("set_ringer_normal", null, null),
        "ပုံမှန်မုဒ်" to Triple("set_ringer_normal", null, null),
        "normal mode" to Triple("set_ringer_normal", null, null),
        "တုန်ခါမှုဖွင့်" to Triple("set_ringer_vibrate", null, null),
        "တုန်ခါမှုပိတ်" to Triple("set_ringer_silent", null, null),
        "vibrate on" to Triple("set_ringer_vibrate", null, null),
        "vibration on" to Triple("set_ringer_vibrate", null, null),
        
        // Camera
        "ကင်မရာ" to Triple("open_app", "camera", null),
        "ကင်မရာဖွင့်" to Triple("open_app", "camera", null),
        "camera" to Triple("open_app", "camera", null),
        "open camera" to Triple("open_app", "camera", null),
        "ဓာတ်ပုံရိုက်" to Triple("open_app", "camera", null),
        "selfie" to Triple("open_app", "camera", null),
        
        // Calculator
        "ကယ်လ်ကူလေတာ" to Triple("open_app", "calculator", null),
        "calculator" to Triple("open_app", "calculator", null),
        "calc" to Triple("open_app", "calculator", null),
        "ဂဏန်းတွက်စက်" to Triple("open_app", "calculator", null),
        "ဂဏန်းတွက်" to Triple("open_app", "calculator", null),
        
        // Settings
        "settings" to Triple("open_app", "settings", null),
        "ဆက်တင်" to Triple("open_app", "settings", null),
        "ဆက်တင်ဖွင့်" to Triple("open_app", "settings", null),
        
        // WiFi
        "wifi ဖွင့်" to Triple("turn_on_wifi", null, null),
        "wifi ပိတ်" to Triple("turn_off_wifi", null, null),
        "wifi on" to Triple("turn_on_wifi", null, null),
        "wifi off" to Triple("turn_off_wifi", null, null),
        "turn on wifi" to Triple("turn_on_wifi", null, null),
        "turn off wifi" to Triple("turn_off_wifi", null, null),
        
        // Bluetooth
        "bluetooth ဖွင့်" to Triple("turn_on_bluetooth", null, null),
        "bluetooth ပိတ်" to Triple("turn_off_bluetooth", null, null),
        "bluetooth on" to Triple("turn_on_bluetooth", null, null),
        "bluetooth off" to Triple("turn_off_bluetooth", null, null),
        
        // Messages
        "messages" to Triple("open_app", "messages", null),
        "sms" to Triple("open_app", "messages", null),
        "စာပို့" to Triple("open_app", "messages", null),
        
        // Browser
        "browser" to Triple("open_app", "browser", null),
        "chrome" to Triple("open_app", "chrome", null),
        "internet" to Triple("open_app", "browser", null),
        
        // Maps
        "maps" to Triple("open_app", "maps", null),
        "google maps" to Triple("open_app", "maps", null),
        "မြေပုံ" to Triple("open_app", "maps", null),
        
        // Music
        "music" to Triple("open_app", "music", null),
        "သီချင်း" to Triple("open_app", "music", null),
        "သီးချင်းဖွင့်ပေး" to Triple("media_play", null, null),
        "သီချင်းဖွင့်ပေး" to Triple("media_play", null, null),
        "သီးချင်းရပ်ပေး" to Triple("media_pause", null, null),
        "သီချင်းရပ်ပေး" to Triple("media_pause", null, null),
        "spotify" to Triple("open_app", "spotify", null),
        "youtube music" to Triple("open_app", "youtube music", null),
        
        // Play/Pause media
        "play" to Triple("media_play", null, null),
        "pause" to Triple("media_pause", null, null),
        "stop" to Triple("media_stop", null, null),
        "next" to Triple("media_next", null, null),
        "previous" to Triple("media_previous", null, null),
        "ရပ်" to Triple("media_pause", null, null),
        "ဖွင့်" to Triple("media_play", null, null),
        "သီချင်းရပ်" to Triple("media_pause", null, null),
        "နောက်တစ်ပုဒ်" to Triple("media_next", null, null),
        "ရှေ့တစ်ပုဒ်" to Triple("media_previous", null, null),
        "ပြန်စ" to Triple("media_play", null, null),
        
        // Gallery
        "gallery" to Triple("open_app", "gallery", null),
        "photos" to Triple("open_app", "photos", null),
        "ဓာတ်ပုံ" to Triple("open_app", "gallery", null),
        
        // Calendar
        "calendar" to Triple("open_app", "calendar", null),
        "ပြက္ခဒိန်" to Triple("open_app", "calendar", null),
        "မနက်ဖြန်ဘာရှိလဲ" to Triple("get_calendar_events", null, "tomorrow"),
        "ဒီနေ့ဘာရှိလဲ" to Triple("get_calendar_events", null, "today"),
        "schedule" to Triple("get_calendar_events", null, "today"),
        "tomorrow schedule" to Triple("get_calendar_events", null, "tomorrow"),
        "မနက်ဖြန် appointment" to Triple("get_calendar_events", null, "tomorrow"),
        "ဒီနေ့ appointment" to Triple("get_calendar_events", null, "today"),
 
        // Gems / memory (offline local routing via ToolRegistry)
        "gem ပြောင်း" to Triple("switch_gem", "assistant", null),
        "switch gem" to Triple("switch_gem", "assistant", null),
        "မှတ်ဉာဏ်ရှာ" to Triple("get_relevant_memory", "", null),
        
        // Clock/Alarm
        "clock" to Triple("open_app", "clock", null),
        "alarm" to Triple("open_app", "clock", null),
        "နာရီ" to Triple("open_app", "clock", null),
        "အလားမ်" to Triple("open_app", "clock", null),
        
        // Notes
        "notes" to Triple("open_app", "notes", null),
        "မှတ်စု" to Triple("open_app", "notes", null),
        
        // Weather
        "weather" to Triple("get_weather", null, null),
        "weather tomorrow" to Triple("get_weather", null, "tomorrow"),
        "weather week" to Triple("get_weather", null, "week"),
        "weather how" to Triple("get_weather", null, null),
        
        // Email
        "email" to Triple("open_app", "gmail", null),
        "gmail" to Triple("open_app", "gmail", null),
        "အီးမေးလ်" to Triple("open_app", "gmail", null),
        
        // Social apps (Facebook vs Messenger - ဖေ့စ်ဘုတ်နဲ့ မက်ဆင်ဂျာ ကွဲပြားအောင်)
        "facebook" to Triple("open_app", "facebook", null),
        "ဖေ့စ်ဘုတ်ဖွင့်" to Triple("open_app", "facebook", null),
        "messenger" to Triple("open_app", "messenger", null),
        "မက်ဆင်ဂျာဖွင့်" to Triple("open_app", "messenger", null),
        "instagram" to Triple("open_app", "instagram", null),
        "twitter" to Triple("open_app", "twitter", null),
        "tiktok" to Triple("open_app", "tiktok", null),
        "telegram" to Triple("open_app", "telegram", null),
        "whatsapp" to Triple("open_app", "whatsapp", null),
        "viber" to Triple("open_app", "viber", null),
        
        // YouTube
        "youtube" to Triple("open_app", "youtube", null),
        "ယူကျုပ်" to Triple("open_app", "youtube", null),
        
        "play youtube" to Triple("play_youtube", null, null),
        "youtube play" to Triple("play_youtube", null, null),
        "ယူကျူ့ဖွင့်" to Triple("play_youtube", null, null),
        // Screenshot
        "screenshot" to Triple("screenshot", null, null),
        "ဖန်သားပြင်ရိုက်" to Triple("screenshot", null, null),
        
        // Weather & Status (speaks result via TTS)
        "ရာသီဥတု" to Triple("get_weather", null, null),
        "ရာသီဥတုစစ်ဆေး" to Triple("get_weather", null, null),
        "မိုးရွာမလား" to Triple("get_weather", null, null),
        "ဘတ်တရီ" to Triple("get_battery_status", null, null),
        "battery" to Triple("get_battery_status", null, null),
        "battery ဘယ်လောက်" to Triple("get_battery_status", null, null),
        "ဘတ်တရီဘယ်လောက်" to Triple("get_battery_status", null, null),
        
        // Samsung Health & Smartwatch Health Commands
        "အိပ်ချိန်" to Triple("get_health_summary", "sleep", null),
        "အိပ်ချိန် ဘယ်လောက်ရှိလဲ" to Triple("get_health_summary", "sleep", null),
        "ခြေလှမ်း" to Triple("get_health_summary", "activity", null),
        "ခြေလှမ်း ဘယ်လောက်ရှိလဲ" to Triple("get_health_summary", "activity", null),
        "ဒီနေ့ လှုပ်ရှားမှု" to Triple("get_health_summary", "activity", null),
        "လေ့ကျင့်ခန်း" to Triple("get_health_summary", "workout", null),
        "လေ့ကျင့်ခန်း ဘယ်လောက်လုပ်ထားလဲ" to Triple("get_health_summary", "workout", null),
        "ကျန်းမာရေး" to Triple("get_health_summary", "all", null),
        "ကျန်းမာရေး အခြေအနေ" to Triple("get_health_summary", "all", null),
        "ကျန်းမာရေး အစီရင်ခံပါ" to Triple("get_health_summary", "all", null),
        "health" to Triple("get_health_summary", "all", null),
        "sleep" to Triple("get_health_summary", "sleep", null),
        "steps" to Triple("get_health_summary", "activity", null),
        "workout" to Triple("get_health_summary", "workout", null),
        
        // Search/News (opens browser)
        "ယနေ့သတင်း" to Triple("search_news", "Korea news today", null),
        "သတင်းရှာပေး" to Triple("search_news", "Korea news", null),
 
        // Notion Extended AI & CRUD Operations (must precede generic open "နိုရှင်း")
        "နိုရှင်းထဲရှာ" to Triple("search_notion", "", null),
        "နိုရှင်းမှာရှာ" to Triple("search_notion", "", null),
        "notion search" to Triple("search_notion", "", null),
        "notion ရှာပေး" to Triple("search_notion", "", null),
        "နိုရှင်းထဲကဆွေးနွေး" to Triple("search_notion", "", null),
        "နိုရှင်းထဲမှတ်" to Triple("send_to_notion", "", null),
        "နိုရှင်းမှာမှတ်" to Triple("send_to_notion", "", null),
        "notion မှတ်" to Triple("send_to_notion", "", null),
        "notion save" to Triple("send_to_notion", "", null),
        "နိုရှင်းမှာထပ်ဖြည့်" to Triple("append_notion_note", "", null),
        "နိုရှင်းထဲထပ်ဖြည့်" to Triple("append_notion_note", "", null),
        "notion ထပ်ဖြည့်" to Triple("append_notion_note", "", null),
        "notion append" to Triple("append_notion_note", "", null),
        "နိုရှင်းထဲကဖျက်" to Triple("delete_notion_note", "", null),
        "နိုရှင်းမှာဖျက်" to Triple("delete_notion_note", "", null),
        "notion ဖျက်" to Triple("delete_notion_note", "", null),
        "notion delete" to Triple("delete_notion_note", "", null),

        // Notion
        "notion" to Triple("open_app", "notion", null),
        "နိုရှင်း" to Triple("open_app", "notion", null),
        "နိုရှင်းဖွင့်" to Triple("open_app", "notion", null),
        "လုပ်ရမယ့်အလုပ်" to Triple("open_app", "notion", null),
        "todo" to Triple("open_app", "notion", null),
        "tasks" to Triple("open_app", "notion", null),
 
        // Focus Mode
        "focus mode" to Triple("set_focus_mode", null, "30"),
        "အာရုံစိုက်မုဒ်" to Triple("set_focus_mode", null, "30"),
        "focus mode pait" to Triple("end_focus_mode", null, null),
        "focus mode ပိတ်" to Triple("end_focus_mode", null, null),
        "focus ပိတ်" to Triple("end_focus_mode", null, null),
        "အာရုံစိုက်မုဒ်ပိတ်" to Triple("end_focus_mode", null, null),
 
        // Action Logs
        "logs" to Triple("read_action_logs", null, null),
        "လော့ဂ်ဖတ်" to Triple("read_action_logs", null, null),
        "လော့ဂ်" to Triple("read_action_logs", null, null),
 
        // Routines
        "routine ဖွင့်" to Triple("list_routines", null, null),
        "လုပ်ဆောင်မှုများ" to Triple("list_routines", null, null),
        "ရူတင်း" to Triple("list_routines", null, null),
 
        // Salary Integration
        "လစာ" to Triple("get_salary_data", null, null),
        "salary" to Triple("get_salary_data", null, null),
        "လစာစစ်ဆေး" to Triple("get_salary_data", null, null),
        "လစာစာရင်း" to Triple("get_salary_data", null, null),
        "လစာမှတ်တမ်း" to Triple("get_salary_data", null, null),

        // Accessibility (v3.0)
        "အနောက်ပြန်" to Triple("perform_system_action", "back", null),
        "အနောက်ဆုတ်" to Triple("perform_system_action", "back", null),
        "အနောက်ဆုတ်ပေး" to Triple("perform_system_action", "back", null),
        "go back" to Triple("perform_system_action", "back", null),
        "back" to Triple("perform_system_action", "back", null),
        "မူလမျက်နှာပြင်" to Triple("perform_system_action", "home", null),
        "အိမ်ပြန်" to Triple("perform_system_action", "home", null),
        "go home" to Triple("perform_system_action", "home", null),
        "home" to Triple("perform_system_action", "home", null),
        "အသိပေးချက်" to Triple("perform_system_action", "notifications", null),
        "အသိပေးချက်များ" to Triple("perform_system_action", "notifications", null),
        "open notifications" to Triple("perform_system_action", "notifications", null),
        "အမြန်ဆက်တင်" to Triple("perform_system_action", "quick_settings", null),
        "အမြန်ဆက်တင်များ" to Triple("perform_system_action", "quick_settings", null),
        
        // Scrolling in all directions & top/bottom
        "အပေါ်ဆွဲ" to Triple("scroll_screen", "up", null),
        "အပေါ်ရွှေ့" to Triple("scroll_screen", "up", null),
        "အပေါ်တက်" to Triple("scroll_screen", "up", null),
        "scroll up" to Triple("scroll_screen", "up", null),
        "အောက်ဆွဲ" to Triple("scroll_screen", "down", null),
        "အောက်ရွှေ့" to Triple("scroll_screen", "down", null),
        "အောက်ဆင်း" to Triple("scroll_screen", "down", null),
        "scroll down" to Triple("scroll_screen", "down", null),
        "ဘယ်ဆွဲ" to Triple("scroll_screen", "left", null),
        "ဘယ်ရွှေ့" to Triple("scroll_screen", "left", null),
        "ဘယ်ဘက်ဆွဲ" to Triple("scroll_screen", "left", null),
        "scroll left" to Triple("scroll_screen", "left", null),
        "ညာဆွဲ" to Triple("scroll_screen", "right", null),
        "ညာရွှေ့" to Triple("scroll_screen", "right", null),
        "ညာဘက်ဆွဲ" to Triple("scroll_screen", "right", null),
        "scroll right" to Triple("scroll_screen", "right", null),
        "အပေါ်ဆုံးထိဆွဲ" to Triple("scroll_screen", "top", null),
        "အပေါ်ဆုံး" to Triple("scroll_screen", "top", null),
        "scroll to top" to Triple("scroll_screen", "top", null),
        "အောက်ဆုံးထိဆွဲ" to Triple("scroll_screen", "bottom", null),
        "အောက်ဆုံး" to Triple("scroll_screen", "bottom", null),
        "scroll to bottom" to Triple("scroll_screen", "bottom", null),
        
        // System Navigation Actions (နောက်ဆုတ်၊ ရှေ့တိုး၊ မူလမျက်နှာပြင်၊ နိုတီ)
        "နောက်ဆုတ်" to Triple("perform_system_action", "back", null),
        "နောက်ဆုတ်ပါ" to Triple("perform_system_action", "back", null),
        "အနောက်ဆုတ်" to Triple("perform_system_action", "back", null),
        "အနောက်သို့" to Triple("perform_system_action", "back", null),
        "ပြန်သွား" to Triple("perform_system_action", "back", null),
        "back" to Triple("perform_system_action", "back", null),
        "go back" to Triple("perform_system_action", "back", null),
        "မူလမျက်နှာပြင်" to Triple("perform_system_action", "home", null),
        "ပင်မမျက်နှာပြင်" to Triple("perform_system_action", "home", null),
        "home" to Triple("perform_system_action", "home", null),
        "လတ်တလောအက်ပ်များ" to Triple("perform_system_action", "recents", null),
        "လတ်တလောသုံးထားသောအက်ပ်များ" to Triple("perform_system_action", "recents", null),
        "recents" to Triple("perform_system_action", "recents", null),
        "နိုတီဆွဲချ" to Triple("perform_system_action", "notifications", null),
        "အသိပေးချက်များ" to Triple("perform_system_action", "notifications", null),
        "notifications" to Triple("perform_system_action", "notifications", null),
        "စခရင်ပိတ်" to Triple("perform_system_action", "lock_screen", null),
        "lock screen" to Triple("perform_system_action", "lock_screen", null),
        "စခရင်ရှော့ရိုက်" to Triple("perform_system_action", "take_screenshot", null),
        "take screenshot" to Triple("perform_system_action", "take_screenshot", null),
        "စခရင်နှစ်ခြမ်းခွဲ" to Triple("perform_system_action", "split_screen", null),
        
        // Navigation / Click Synonyms (v3.0 Stability)
        "နောက်တစ်ဆင့်" to Triple("click_text", "next", null),
        "နောက်တဆင့်" to Triple("click_text", "next", null),
        "ရှေ့ဆက်" to Triple("click_text", "next", null),
        "ရှေ့တိုး" to Triple("click_text", "next", null),
        "ဆက်လုပ်" to Triple("click_text", "next", null),
        "ကျော်ပါ" to Triple("click_text", "skip", null),
        "ကျော်ဖတ်" to Triple("click_text", "skip", null),
        "အိုကေ" to Triple("click_text", "ok", null),
        "သဘောတူတယ်" to Triple("click_text", "agree", null),
        "အတည်ပြု" to Triple("click_text", "confirm", null),
        "စာဖျက်" to Triple("clear_text", null, null),
        "စာသားရှင်း" to Triple("clear_text", null, null),
        
        // Screen Reading / Vision ("Eyes")
        "စခရင်ဖတ်" to Triple("read_screen_contents", null, null),
        "စခရင်ဖတ်ပေးပါ" to Triple("read_screen_contents", null, null),
        "မျက်နှာပြင်ဖတ်" to Triple("read_screen_contents", null, null),
        "မျက်နှာပြင်ဖတ်ပေးပါ" to Triple("read_screen_contents", null, null),
        "စခရင်ပေါ်မှာဘာရှိလဲ" to Triple("read_screen_contents", null, null),
        "ဘာတွေမြင်ရလဲ" to Triple("read_screen_contents", null, null),
        "read screen" to Triple("read_screen_contents", null, null),
        "read screen contents" to Triple("read_screen_contents", null, null),
        
        // Memory & Facts Management
        "ငါ့အကြောင်း ဘာတွေမှတ်ထားလဲ" to Triple("get_remembered_facts", null, null),
        "ဘာတွေမှတ်ထားလဲ" to Triple("get_remembered_facts", null, null),
        "မန်မိုရီထဲမှာ ဘာတွေရှိလဲ" to Triple("get_remembered_facts", null, null),
        "မန်မိုရီပြပါ" to Triple("get_remembered_facts", null, null),
        "မှတ်ထားတဲ့ အချက်အလက်များ" to Triple("get_remembered_facts", null, null),
        "get memories" to Triple("get_remembered_facts", null, null),
        "list memories" to Triple("get_remembered_facts", null, null),
    )

    /**
     * Returns (command, target, value) if phrase matches, else null.
     */
    fun match(text: String): Triple<String, String?, String?>? {
        val normalized = text.trim().lowercase()
        if (normalized.isBlank()) return null

        // Check for raw command pattern e.g., "set_volume:50" or "set_ringer_silent" or "send_sms:0912345678:hello"
        val rawParts = text.split(":", limit = 3)
        val firstPart = rawParts.getOrNull(0)?.trim()?.lowercase(java.util.Locale.ROOT)
        if (firstPart != null && KNOWN_COMMANDS.contains(firstPart)) {
            val targetParam = rawParts.getOrNull(1)
            val valueParam = rawParts.getOrNull(2)
            return Triple(firstPart, targetParam, valueParam ?: targetParam)
        }

        // Time/date questions FIRST - avoid "နာရီ" matching "ဘယ်နှစ်နာရီလဲ" -> open_app
        when {
            normalized.contains("ဘယ်နှစ်နာရီ") || normalized.contains("what time") ||
            normalized.contains("အခုအချိန်") || normalized.contains("အချိန်ပြော") ||
            normalized.contains("အချိန်ဘယ်လောက်") || normalized.contains("အချိန်ဘယ်လောက်ရှိပြီလဲ") -> return Triple("get_current_time", null, "time")
            
            normalized.contains("ဒီနေ့ဘယ်နေ့") || normalized.contains("ဘယ်နေ့လဲ") ||
            normalized.contains("what day") || normalized.contains("ရက်စွဲပြော") ||
            normalized.contains("ဒီနေ့ရက်စွဲ") || normalized.contains("ယနေ့ရက်စွဲ") -> return Triple("get_current_time", null, "date")
            
            (normalized.contains("အချိန်") || normalized.contains("နာရီ")) && 
                (normalized.contains("လဲ") || normalized.contains("ဘယ်") || normalized.contains("ပြော") || normalized.contains("ရှိပြီ")) -> return Triple("get_current_time", null, "time")
        }

        // set_volume with percent: "အသံ ၅၀", "volume 50", "၇၅ ရာခိုင်နှုန်း ထားပေး"
        val volMatch = Regex("(?:အသံ|volume)\\s*([၀-၉0-9]+)|([၀-၉0-9]+)\\s*(?:ရာခိုင်နှုန်း|%)").find(normalized)
        if (volMatch != null && (normalized.contains("အသံ") || normalized.contains("volume") || normalized.contains("ရာခိုင်"))) {
            val num = (volMatch.groupValues[1].ifBlank { volMatch.groupValues[2] }).replace(" ", "")
            if (num.isNotBlank()) return Triple("set_volume", null, num)
        }

        // Extract call target for offline voice commands (e.g. "မောင်မောင် ကို ဖုန်းခေါ်")
        val isCallRequest = normalized.contains("ဖုန်းခေါ်") || normalized.contains("ဖုန်းဆက်") || 
                            normalized.contains("ခေါ်ပေး") || normalized.contains("ဆက်ပေး") || 
                            normalized.startsWith("call ") || normalized.startsWith("dial ")
        
        if (isCallRequest) {
            var cleanName = normalized
                .replace("ဖုန်းခေါ်ပေးပါ", "")
                .replace("ဖုန်းခေါ်ပေး", "")
                .replace("ဖုန်းခေါ်ပါ", "")
                .replace("ဖုန်းခေါ်", "")
                .replace("ခေါ်ပေးပါ", "")
                .replace("ခေါ်ပေး", "")
                .replace("ခေါ်လိုက်ပါ", "")
                .replace("ခေါ်လိုက်", "")
                .replace("ခေါ်ပါ", "")
                .replace("ခေါ်", "")
                .replace("ဖုန်းဆက်ပေးပါ", "")
                .replace("ဖုန်းဆက်ပေး", "")
                .replace("ဖုန်းဆက်ပါ", "")
                .replace("ဖုန်းဆက်", "")
                .replace("ဆက်ပေးပါ", "")
                .replace("ဆက်ပေး", "")
                .replace("ဆက်လိုက်ပါ", "")
                .replace("ဆက်လိုက်", "")
                .replace("ဆက်ပါ", "")
                .replace("ဆက်", "")
                .replace("call ", "")
                .replace("dial ", "")
                .trim()
            if (cleanName.endsWith("ဆီသို့") && cleanName.length > "ဆီသို့".length + 1) {
                cleanName = cleanName.removeSuffix("ဆီသို့").trim()
            }
            if (cleanName.endsWith("ကို") && cleanName.length > "ကို".length + 1) {
                cleanName = cleanName.removeSuffix("ကို").trim()
            }
            if (cleanName.endsWith("ဆီ") && cleanName.length > "ဆီ".length + 1) {
                cleanName = cleanName.removeSuffix("ဆီ").trim()
            }
            // Normalize digits and prefixes for index matching (e.g. "နံပါတ် 1" -> "1", "၁" -> "1")
            val normalizedDigits = cleanName
                .replace("၀", "0").replace("၁", "1").replace("၂", "2")
                .replace("၃", "3").replace("၄", "4").replace("၅", "5")
                .replace("၆", "6").replace("၇", "7").replace("၈", "8").replace("၉", "9")
                .replace(Regex("(?i)\\b(number|no\\.?|နံပါတ်|အမှတ်)\\b"), "")
                .trim()

            val finalTarget = if (normalizedDigits.matches(Regex("^[0-9]+$"))) normalizedDigits else cleanName
            if (finalTarget.isNotBlank() && finalTarget.length in 1..40) {
                return Triple("make_call", finalTarget, null)
            }
        }

        // Exact match first
        PHRASES[normalized]?.let { return it }
        
        // Then partial match (only if phrase is meaningful length >= 4 and matches on word boundaries or contained in longer sentence)
        for ((phrase, triple) in PHRASES) {
            if (phrase.length >= 4 && (normalized.contains(phrase) || normalized == phrase)) {
                return triple
            }
        }
        
        // Check for "click [text]" or "နှိပ် [text]" or "[text] နှိပ်" pattern
        if (normalized.startsWith("click ") || normalized.startsWith("click_text ") || 
            normalized.startsWith("နှိပ် ") || normalized.startsWith("နှိပ်ပေး ")) {
            val text = normalized
                .removePrefix("click_text ")
                .removePrefix("click ")
                .removePrefix("နှိပ်ပေး ")
                .removePrefix("နှိပ် ")
                .trim()
            if (text.isNotBlank()) {
                return Triple("click_text", text, null)
            }
        }
        if (normalized.endsWith(" နှိပ်") || normalized.endsWith(" နှိပ်ပါ") || normalized.endsWith(" နှိပ်ပေး") || normalized.endsWith(" ကို နှိပ်")) {
            val text = normalized
                .removeSuffix(" ကို နှိပ်")
                .removeSuffix(" နှိပ်ပေး")
                .removeSuffix(" နှိပ်ပါ")
                .removeSuffix(" နှိပ်")
                .trim()
            if (text.isNotBlank()) {
                return Triple("click_text", text, null)
            }
        }

        // Check for Message Reply patterns ("ပြန်ပြောလိုက် [text]", "ပြန်ပို့ပေး [text]", "[text] လို့ ပြန်ပြောလိုက်", "[text] လို့ ပြန်ပို့ပေး", "reply [text]")
        val replySuffixes = listOf(
            " လို့ ပြန်ပြောလိုက်ပါ", " လို့ ပြန်ပြောလိုက်", " လို့ ပြန်ပြောပေးပါ", " လို့ ပြန်ပြောပေး",
            " လို့ ပြန်ပို့ပေးပါ", " လို့ ပြန်ပို့ပေး", " လို့ ပြန်ပို့လိုက်ပါ", " လို့ ပြန်ပို့လိုက်",
            " လို့ ပြောလိုက်ပါ", " လို့ ပြောလိုက်", " လို့ ပို့ပေးပါ", " လို့ ပို့ပေး",
            " လို့ ပြန်ပြော", " လို့ ပြန်ပို့"
        )
        for (suffix in replySuffixes) {
            if (normalized.endsWith(suffix) && normalized.length > suffix.length) {
                val clean = normalized.substring(0, normalized.length - suffix.length).trim()
                if (clean.isNotBlank()) {
                    return Triple("send_messenger", null, clean)
                }
            }
        }

        val replyPrefixes = listOf(
            "မက်ဆင်ဂျာကနေ ပြန်ပြော ", "မက်ဆင်ဂျာကနေ ပြန်ပို့ ", "မက်ဆင်ဂျာကနေ ပြော ", "မက်ဆင်ဂျာကနေ ပို့ ",
            "ပြန်ပြောလိုက်ပါ ", "ပြန်ပြောလိုက် ", "ပြန်ပြောပေးပါ ", "ပြန်ပြောပေး ",
            "ပြန်ပို့လိုက်ပါ ", "ပြန်ပို့လိုက် ", "ပြန်ပို့ပေးပါ ", "ပြန်ပို့ပေး ",
            "ပြန်ပြော ", "ပြန်ပို့ ", "reply ", "send_messenger "
        )
        for (prefix in replyPrefixes) {
            if (normalized.startsWith(prefix) && normalized.length > prefix.length) {
                val clean = normalized.substring(prefix.length).trim()
                if (clean.isNotBlank()) {
                    return Triple("send_messenger", null, clean)
                }
            }
        }

        // Check for "type [text]" or "ရိုက် [text]" pattern
        if (normalized.startsWith("type ") || normalized.startsWith("input_text ") || 
            normalized.startsWith("ရိုက် ") || normalized.startsWith("ရိုက်ပေး ")) {
            val text = normalized
                .removePrefix("input_text ")
                .removePrefix("type ")
                .removePrefix("ရိုက်ပေး ")
                .removePrefix("ရိုက် ")
                .trim()
            if (text.isNotBlank()) {
                return Triple("input_text", text, null)
            }
        }

        // Check for "run_macro [name]" or "run macro [name]" or "မက်ခရို [name]" pattern
        if (normalized.startsWith("run_macro ") || normalized.startsWith("run macro ") || 
            normalized.startsWith("မက်ခရို ")) {
            val name = normalized
                .removePrefix("run_macro ")
                .removePrefix("run macro ")
                .removePrefix("မက်ခရို ")
                .trim()
            if (name.isNotBlank()) {
                return Triple("run_macro", name, null)
            }
        }

        // Check for Memory Save patterns ("ငါ့အကြောင်း မှတ်ထား [text]", "မန်မိုရီထဲ မှတ်ထား [text]", "မှတ်ထားပေး [text]")
        if (normalized.startsWith("ငါ့အကြောင်း မှတ်ထား ") || normalized.startsWith("ငါ့အကြောင်း မှတ်ပေး ") ||
            normalized.startsWith("မန်မိုရီထဲ မှတ်ထား ") || normalized.startsWith("မန်မိုရီထဲ မှတ်ပေး ") ||
            normalized.startsWith("မှတ်ထားပေး ") || normalized.startsWith("remember ")) {
            val fact = normalized
                .removePrefix("ငါ့အကြောင်း မှတ်ထား ")
                .removePrefix("ငါ့အကြောင်း မှတ်ပေး ")
                .removePrefix("မန်မိုရီထဲ မှတ်ထား ")
                .removePrefix("မန်မိုရီထဲ မှတ်ပေး ")
                .removePrefix("မှတ်ထားပေး ")
                .removePrefix("remember ")
                .trim()
            if (fact.isNotBlank()) {
                return Triple("save_preference", null, fact)
            }
        }

        // Check for Memory Forget patterns ("မန်မိုရီထဲက [text] ကို ဖျက်ပေး", "မေ့လိုက်ပါ [text]", "[text] ကို မေ့လိုက်")
        if (normalized.startsWith("မန်မိုရီထဲက ") || normalized.startsWith("မေ့လိုက်ပါ ") || normalized.startsWith("မေ့ပေးပါ ") ||
            normalized.startsWith("forget ")) {
            val key = normalized
                .removePrefix("မန်မိုရီထဲက ")
                .removePrefix("မေ့လိုက်ပါ ")
                .removePrefix("မေ့ပေးပါ ")
                .removePrefix("forget ")
                .removeSuffix(" ကို ဖျက်ပေး")
                .removeSuffix(" ကို ဖျက်ပါ")
                .removeSuffix(" ကို မေ့လိုက်ပါ")
                .removeSuffix(" ကို မေ့လိုက်")
                .removeSuffix(" ကို ဖျက်")
                .trim()
            if (key.isNotBlank()) {
                return Triple("forget_memory", key, null)
            }
        }

        // Check for "open [app]" pattern
        if (normalized.startsWith("open ") || normalized.startsWith("ဖွင့် ")) {
            val appName = normalized
                .removePrefix("open ")
                .removePrefix("ဖွင့် ")
                .trim()
            if (appName.isNotBlank()) {
                return Triple("open_app", appName, null)
            }
        }
        
        return null
    }
    
    /**
     * Get list of available offline commands for help
     */
    fun getAvailableCommands(): List<String> {
        return listOf(
            "📞 ဖုန်းခေါ် / call",
            "💡 မီးအိမ် ဖွင့်/ပိတ်",
            "📷 ကင်မရာ ဖွင့်",
            "🔊 အသံတိုး/လျော့",
            "📶 WiFi ဖွင့်/ပိတ်",
            "📱 Bluetooth ဖွင့်/ပိတ်",
            "⚙️ Settings ဖွင့်",
            "🎵 သီချင်း ဖွင့်",
            "📸 Gallery ဖွင့်",
            "📧 Gmail ဖွင့်",
            "🌐 Browser ဖွင့်",
            "📓 Notion (နိုရှင်း)",
            "⏱️ Focus Mode (အာရုံစိုက်မုဒ်)",
            "💰 Salary (လစာစစ်ဆေး)",
            "📱 [App နာမည်] ဖွင့်"
        )
    }
}
