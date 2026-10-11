package com.example.voicebrainlive.routines

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Routines Engine: Scheduled daily tasks (weather at 7am, reminder at noon, etc.)
 */
object RoutineStorage {

    private const val FILE_NAME = "scheduled_routines.json"
    private const val MAX_ROUTINES = 30

    /** dayOfWeek: 0 = daily, 1-7 = Calendar.SUNDAY..SATURDAY */
    data class RoutineItem(
        val id: Int,
        val type: String,  // "weather", "reminder", "music"
        val hour: Int,
        val minute: Int,
        val message: String = "",  // reminder text (reminder type) OR legacy storage field
        val dayOfWeek: Int = 0,    // 0 = daily, 1=Sun..7=Sat
        val musicQuery: String = "", // for music type (genre/mood/artist/song)
        val reminderText: String = "", // spoken reminder (optional, before main trigger)
        val reminderMinutesBefore: Int = 0, // 0 disables
        val enabled: Boolean = true,
        val useLiveAgent: Boolean = true
    ) {
        fun timeLabel(): String = String.format("%02d:%02d", hour, minute)
        fun isWeekly(): Boolean = dayOfWeek in 1..7
    }

    private fun getFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): MutableList<RoutineItem> {
        return try {
            val file = getFile(context)
            if (!file.exists()) return mutableListOf()
            val json = file.readText()
            if (json.isBlank()) return mutableListOf()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                RoutineItem(
                    id = obj.optInt("id", 0),
                    type = obj.optString("type", "reminder"),
                    hour = obj.optInt("hour", 7),
                    minute = obj.optInt("minute", 0),
                    message = obj.optString("message", ""),
                    dayOfWeek = obj.optInt("dayOfWeek", 0),
                    musicQuery = obj.optString("musicQuery", ""),
                    reminderText = obj.optString("reminderText", ""),
                    reminderMinutesBefore = obj.optInt("reminderMinutesBefore", 0),
                    enabled = obj.optBoolean("enabled", true),
                    useLiveAgent = obj.optBoolean("useLiveAgent", true)
                )
            }.toMutableList()
        } catch (e: Exception) {
            Log.e("RoutineStorage", "load", e)
            mutableListOf()
        }
    }

    private fun save(context: Context, items: List<RoutineItem>) {
        try {
            val arr = JSONArray()
            items.take(MAX_ROUTINES).forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("type", r.type)
                    put("hour", r.hour)
                    put("minute", r.minute)
                    put("message", r.message)
                    put("dayOfWeek", r.dayOfWeek)
                    put("musicQuery", r.musicQuery)
                    put("reminderText", r.reminderText)
                    put("reminderMinutesBefore", r.reminderMinutesBefore)
                    put("enabled", r.enabled)
                    put("useLiveAgent", r.useLiveAgent)
                })
            }
            getFile(context).writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("RoutineStorage", "save", e)
        }
    }

    fun saveRoutine(context: Context, item: RoutineItem) {
        val items = load(context)
        val idx = items.indexOfFirst { it.id == item.id || it.type == item.type }
        if (idx >= 0) {
            items[idx] = item
        } else {
            items.add(item)
        }
        save(context, items)
    }

    fun add(
        context: Context,
        type: String,
        hour: Int,
        minute: Int,
        message: String = "",
        dayOfWeek: Int = 0,
        musicQuery: String = "",
        reminderText: String = "",
        reminderMinutesBefore: Int = 0,
        enabled: Boolean = true,
        useLiveAgent: Boolean = true
    ): RoutineItem {
        val items = load(context)
        val id = (items.maxOfOrNull { it.id } ?: 0) + 1
        val item = RoutineItem(id, type, hour, minute, message, dayOfWeek, musicQuery, reminderText, reminderMinutesBefore, enabled, useLiveAgent)
        items.add(item)
        save(context, items)
        Log.d("RoutineStorage", "add: $type at $hour:$minute id=$id")
        return item
    }

    fun getAll(context: Context): List<RoutineItem> = load(context)

    fun delete(context: Context, id: Int) {
        val items = load(context).filter { it.id != id }
        save(context, items)
        Log.d("RoutineStorage", "delete: id=$id")
    }

    fun update(
        context: Context,
        id: Int,
        type: String,
        hour: Int,
        minute: Int,
        message: String = "",
        dayOfWeek: Int = 0,
        musicQuery: String = "",
        reminderText: String = "",
        reminderMinutesBefore: Int = 0,
        enabled: Boolean = true,
        useLiveAgent: Boolean = true
    ): RoutineItem? {
        val items = load(context)
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = RoutineItem(id, type, hour, minute, message, dayOfWeek, musicQuery, reminderText, reminderMinutesBefore, enabled, useLiveAgent)
        items[idx] = updated
        save(context, items)
        Log.d("RoutineStorage", "update: id=$id $type at $hour:$minute")
        return updated
    }

    fun getById(context: Context, id: Int): RoutineItem? = load(context).find { it.id == id }

    fun setEnabled(context: Context, id: Int, enabled: Boolean) {
        val items = load(context)
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(enabled = enabled)
            save(context, items)
            Log.d("RoutineStorage", "setEnabled id=$id $enabled")
        }
    }
}
