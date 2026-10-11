package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.example.voicebrainlive.CalendarHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Calendar voice commands (today/tomorrow schedule, open calendar).
 */
class CalendarHandler(private val context: Context) : CommandHandler {

    private val calendarHelper by lazy { CalendarHelper(context) }

    override fun getSupportedCommands(): List<String> = listOf(
        "get_calendar_events",
        "schedule_today",
        "my_schedule",
        "schedule_tomorrow",
        "add_meeting",
        "create_meeting",
        "open_calendar"
    )

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "get_calendar_events" -> getCalendarEvents(target ?: value ?: "today")
            "schedule_today", "my_schedule" -> scheduleToday()
            "schedule_tomorrow" -> scheduleTomorrow()
            "add_meeting", "create_meeting" -> "Meeting scheduling: use add_calendar_event with title and time."
            "open_calendar" -> openCalendar()
            else -> null
        }
    }

    private suspend fun getCalendarEvents(period: String): String {
        try {
            val events = when (period.lowercase()) {
                "tomorrow", "နက်ဖြန်", "မနက်ဖြန်" -> calendarHelper.getTomorrowEvents()
                else -> calendarHelper.getTodayEvents()
            }
            return if (events.isEmpty()) {
                when (period.lowercase()) {
                    "tomorrow", "နက်ဖြန်", "မနက်ဖြန်" -> "မနက်ဖြန် အစီအစဉ် မရှိပါ။"
                    else -> "ဒီနေ့ အစီအစဉ် မရှိပါ။"
                }
            } else {
                calendarHelper.formatEventsForVoice(events)
            }
        } catch (e: Exception) {
            return "Calendar မဖတ်နိုင်ပါ: ${e.message}"
        }
    }

    private suspend fun scheduleToday(): String {
        try {
            val events = calendarHelper.getTodayEvents()
            return if (events.isEmpty()) "ဒီနေ့ အစီအစဉ် မရှိပါ။"
            else calendarHelper.formatEventsForVoice(events)
        } catch (e: Exception) {
            return "Calendar မဖတ်နိုင်ပါ: ${e.message}"
        }
    }

    private suspend fun scheduleTomorrow(): String {
        try {
            val events = calendarHelper.getTomorrowEvents()
            return if (events.isEmpty()) "မနက်ဖြန် အစီအစဉ် မရှိပါ။"
            else "မနက်ဖြန်: ${calendarHelper.formatEventsForVoice(events)}"
        } catch (e: Exception) {
            return "Calendar မဖတ်နိုင်ပါ: ${e.message}"
        }
    }

    private fun openCalendar(): String {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = CalendarContract.CONTENT_URI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Calendar ဖွင့်ပြီးပါပြီ။"
        } catch (e: Exception) {
            "Calendar ဖွင့်၍ မရပါ: ${e.message}"
        }
    }
}
