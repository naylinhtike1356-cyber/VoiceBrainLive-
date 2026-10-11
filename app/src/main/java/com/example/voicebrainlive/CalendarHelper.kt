package com.example.voicebrainlive

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Calendar Integration - Google Calendar sync
 * v2.0 Feature
 */
class CalendarHelper(private val context: Context) {
    
    companion object {
        const val TAG = "CalendarHelper"
        
        val calendarIds: LongArray? = null
    }
    
    /**
     * Get all events for today
     */
    suspend fun getTodayEvents(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        getEventsForDay(Calendar.getInstance())
    }
    
    /**
     * Get events for tomorrow
     */
    suspend fun getTomorrowEvents(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val tomorrow = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
        }
        getEventsForDay(tomorrow)
    }
    
    /**
     * Get events for a specific date
     */
    suspend fun getEventsForDate(dateStr: String): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val calendar = parseDate(dateStr) ?: Calendar.getInstance()
        getEventsForDay(calendar)
    }
    
    /**
     * Get all events for a specific day
     */
    private fun getEventsForDay(day: Calendar): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        
        try {
            val startOfDay = day.clone() as Calendar
            startOfDay.set(Calendar.HOUR_OF_DAY, 0)
            startOfDay.set(Calendar.MINUTE, 0)
            startOfDay.set(Calendar.SECOND, 0)
            
            val endOfDay = day.clone() as Calendar
            endOfDay.set(Calendar.HOUR_OF_DAY, 23)
            endOfDay.set(Calendar.MINUTE, 59)
            endOfDay.set(Calendar.SECOND, 59)
            
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.CALENDAR_ID
            )
            
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(
                startOfDay.timeInMillis.toString(),
                endOfDay.timeInMillis.toString()
            )
            
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
            
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val title = it.getString(1) ?: "Untitled"
                    val description = it.getString(2)
                    val startTime = it.getLong(3)
                    val endTime = it.getLong(4)
                    val location = it.getString(5)
                    
                    events.add(CalendarEvent(
                        id = id,
                        title = title,
                        description = description ?: "",
                        startTime = startTime,
                        endTime = endTime,
                        location = location ?: "",
                        calendarId = it.getLong(6)
                    ))
                }
            }
            
            Log.d(TAG, "Found ${events.size} events for ${day.time}")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting events: ${e.message}")
        }
        
        return events
    }
    
    /**
     * Create a new calendar event
     */
    suspend fun createEvent(
        title: String,
        description: String = "",
        location: String = "",
        startTime: Long = System.currentTimeMillis() + 3600000, // 1 hour from now
        durationMinutes: Int = 60
    ): Long = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, startTime + durationMinutes * 60000)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, getPrimaryCalendarId())
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val eventId = ContentUris.parseId(uri!!)
            
            Log.d(TAG, "Created event: $eventId")
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating event: ${e.message}")
            -1
        }
    }
    
    /**
     * Delete a calendar event
     */
    suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting event: ${e.message}")
            false
        }
    }
    
    /**
     * Open calendar app
     */
    fun openCalendar() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://com.android.calendar/events")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening calendar: ${e.message}")
        }
    }
    
    /**
     * Get primary calendar ID
     */
    private fun getPrimaryCalendarId(): Long {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null,
            null
        )
        
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        
        // If no primary calendar, get first writable calendar
        val fallbackProjection = arrayOf(CalendarContract.Calendars._ID)
        val fallbackCursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            fallbackProjection,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null,
            null
        )
        
        fallbackCursor?.use {
            if (it.moveToFirst()) {
                return it.getLong(0)
            }
        }
        
        return 1
    }
    
    /**
     * Parse date string to Calendar
     */
    private fun parseDate(dateStr: String): Calendar? {
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()),
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()),
            SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
        )
        
        for (format in formats) {
            try {
                val date = format.parse(dateStr)
                if (date != null) {
                    return Calendar.getInstance().apply { time = date }
                }
            } catch (e: Exception) { Log.w(TAG, "parseDate", e) }
        }
        
        // Handle relative dates
        val lower = dateStr.lowercase()
        val calendar = Calendar.getInstance()
        
        when {
            lower.contains("today") || lower.contains("ဒီနေ့") -> { }
            lower.contains("tomorrow") || lower.contains("မနက်ဖြန်") -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            lower.contains("next week") -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            else -> return null
        }
        
        return calendar
    }
    
    /**
     * Format events for voice output
     */
    fun formatEventsForVoice(events: List<CalendarEvent>): String {
        if (events.isEmpty()) {
            return "No events scheduled"
        }
        
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        
        return when (events.size) {
            1 -> {
                val event = events.first()
                "1 event: ${event.title} at ${timeFormat.format(Date(event.startTime))}"
            }
            else -> {
                val titles = events.take(3).joinToString(", ") { 
                    "${it.title} at ${timeFormat.format(Date(it.startTime))}" 
                }
                if (events.size > 3) {
                    "$titles, and ${events.size - 3} more"
                } else {
                    titles
                }
            }
        }
    }
}

data class CalendarEvent(
    val id: Long,
    val title: String,
    val description: String,
    val startTime: Long,
    val endTime: Long,
    val location: String,
    val calendarId: Long
)
