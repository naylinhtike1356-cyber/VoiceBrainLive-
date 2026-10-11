package com.example.voicebrainlive

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Meeting Notes - Auto-capture and manage meeting notes
 * v2.0 Feature
 */
class MeetingNotesHelper(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("meeting_notes", Context.MODE_PRIVATE)
    
    companion object {
        const val TAG = "MeetingNotesHelper"
    }
    
    /**
     * Create a new meeting
     */
    fun startMeeting(title: String, participants: String = ""): Meeting {
        val meeting = Meeting(
            id = UUID.randomUUID().toString(),
            title = title,
            participants = participants,
            startTime = System.currentTimeMillis(),
            notes = mutableListOf(),
            isActive = true
        )
        
        saveMeeting(meeting)
        Log.d(TAG, "Meeting started: ${meeting.title}")
        
        return meeting
    }
    
    /**
     * Add note to active meeting
     */
    fun addNote(meetingId: String, note: String): Boolean {
        val meeting = getMeeting(meetingId)
        if (meeting != null && meeting.isActive) {
            meeting.notes.add(Note(
                content = note,
                timestamp = System.currentTimeMillis()
            ))
            saveMeeting(meeting)
            return true
        }
        return false
    }
    
    /**
     * End a meeting
     */
    fun endMeeting(meetingId: String): Meeting? {
        val meeting = getMeeting(meetingId)
        if (meeting != null) {
            meeting.endTime = System.currentTimeMillis()
            meeting.isActive = false
            
            // Generate summary
            meeting.summary = generateSummary(meeting)
            
            saveMeeting(meeting)
            Log.d(TAG, "Meeting ended: ${meeting.title}")
            
            return meeting
        }
        return null
    }
    
    /**
     * Get all meetings
     */
    fun getAllMeetings(): List<Meeting> {
        val json = prefs.getString("meetings", "[]") ?: "[]"
        return parseMeetings(json)
    }
    
    /**
     * Get meeting by ID
     */
    fun getMeeting(meetingId: String): Meeting? {
        return getAllMeetings().find { it.id == meetingId }
    }
    
    /**
     * Get active meeting
     */
    fun getActiveMeeting(): Meeting? {
        return getAllMeetings().find { it.isActive }
    }
    
    /**
     * Get recent meetings
     */
    fun getRecentMeetings(limit: Int = 10): List<Meeting> {
        return getAllMeetings()
            .sortedByDescending { it.startTime }
            .take(limit)
    }
    
    /**
     * Delete a meeting
     */
    fun deleteMeeting(meetingId: String): Boolean {
        val meetings = getAllMeetings().toMutableList()
        val removed = meetings.removeAll { it.id == meetingId }
        
        if (removed) {
            saveMeetings(meetings)
            return true
        }
        return false
    }
    
    /**
     * Search meetings
     */
    fun searchMeetings(query: String): List<Meeting> {
        return getAllMeetings().filter { meeting ->
            meeting.title.contains(query, ignoreCase = true) ||
            meeting.notes.any { it.content.contains(query, ignoreCase = true) } ||
            meeting.participants.contains(query, ignoreCase = true)
        }
    }
    
    /**
     * Export meeting as text
     */
    fun exportMeeting(meetingId: String): String {
        val meeting = getMeeting(meetingId) ?: return "Meeting not found"
        
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        
        return buildString {
            appendLine("Meeting: ${meeting.title}")
            appendLine("Date: ${dateFormat.format(Date(meeting.startTime))}")
            appendLine("Duration: ${formatDuration(meeting.getDuration())}")
            if (meeting.participants.isNotBlank()) {
                appendLine("Participants: ${meeting.participants}")
            }
            appendLine()
            appendLine("Notes:")
            meeting.notes.forEachIndexed { index, note ->
                appendLine("${index + 1}. ${note.content}")
            }
            if (meeting.summary.isNotBlank()) {
                appendLine()
                appendLine("Summary: ${meeting.summary}")
            }
        }
    }
    
    /**
     * Save meeting to storage
     */
    private fun saveMeeting(meeting: Meeting) {
        val meetings = getAllMeetings().toMutableList()
        
        // Remove existing if any
        meetings.removeAll { it.id == meeting.id }
        
        // Add updated meeting
        meetings.add(meeting)
        
        saveMeetings(meetings)
    }
    
    /**
     * Save all meetings
     */
    private fun saveMeetings(meetings: List<Meeting>) {
        val jsonArray = JSONArray()
        
        meetings.forEach { meeting ->
            val json = JSONObject().apply {
                put("id", meeting.id)
                put("title", meeting.title)
                put("participants", meeting.participants)
                put("startTime", meeting.startTime)
                put("endTime", meeting.endTime)
                put("isActive", meeting.isActive)
                put("summary", meeting.summary)
                
                val notesArray = JSONArray()
                meeting.notes.forEach { note ->
                    notesArray.put(JSONObject().apply {
                        put("content", note.content)
                        put("timestamp", note.timestamp)
                    })
                }
                put("notes", notesArray)
            }
            jsonArray.put(json)
        }
        
        prefs.edit().putString("meetings", jsonArray.toString()).apply()
    }
    
    /**
     * Parse meetings from JSON
     */
    private fun parseMeetings(json: String): List<Meeting> {
        val meetings = mutableListOf<Meeting>()
        
        try {
            val jsonArray = JSONArray(json)
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                
                val notesArray = obj.getJSONArray("notes")
                val notes = mutableListOf<Note>()
                
                for (j in 0 until notesArray.length()) {
                    val noteObj = notesArray.getJSONObject(j)
                    notes.add(Note(
                        content = noteObj.getString("content"),
                        timestamp = noteObj.getLong("timestamp")
                    ))
                }
                
                meetings.add(Meeting(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    participants = obj.optString("participants", ""),
                    startTime = obj.getLong("startTime"),
                    endTime = obj.optLong("endTime", 0),
                    isActive = obj.getBoolean("isActive"),
                    summary = obj.optString("summary", ""),
                    notes = notes
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing meetings: ${e.message}")
        }
        
        return meetings
    }
    
    /**
     * Generate meeting summary using AI
     */
    private fun generateSummary(meeting: Meeting): String {
        // Simple summary generation
        // In production, this could use Gemini API
        
        val noteCount = meeting.notes.size
        
        return when {
            noteCount == 0 -> "No notes taken during this meeting"
            noteCount < 3 -> "Short meeting with $noteCount notes"
            else -> "Meeting with $noteCount notes recorded"
        }
    }
    
    /**
     * Format duration in milliseconds
     */
    private fun formatDuration(durationMs: Long): String {
        val minutes = durationMs / 60000
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        
        return when {
            hours > 0 -> "${hours}h ${remainingMinutes}m"
            else -> "${minutes}m"
        }
    }
    
    /**
     * Get voice command response
     */
    fun handleVoiceCommand(command: String, target: String?, value: String?): String {
        return when (command.lowercase()) {
            "start_meeting", "new_meeting" -> {
                val title = target ?: "Meeting"
                val meeting = startMeeting(title, value ?: "")
                "Meeting '${meeting.title}' started. What would you like to discuss?"
            }
            "add_note", "take_note" -> {
                val activeMeeting = getActiveMeeting()
                if (activeMeeting != null) {
                    if (addNote(activeMeeting.id, target ?: "")) {
                        "Note added"
                    } else {
                        "Could not add note"
                    }
                } else {
                    "No active meeting. Say 'start meeting' to begin"
                }
            }
            "end_meeting", "close_meeting" -> {
                val activeMeeting = getActiveMeeting()
                if (activeMeeting != null) {
                    val ended = endMeeting(activeMeeting.id)
                    "Meeting '${ended?.title}' ended. ${ended?.summary}"
                } else {
                    "No active meeting to end"
                }
            }
            "show_notes", "list_notes" -> {
                val meetings = getRecentMeetings(3)
                if (meetings.isEmpty()) {
                    "No meetings found"
                } else {
                    meetings.joinToString(". ") { "${it.title} - ${it.notes.size} notes" }
                }
            }
            else -> "Unknown meeting command"
        }
    }
}

data class Meeting(
    val id: String,
    val title: String,
    val participants: String,
    val startTime: Long,
    var endTime: Long = 0,
    var isActive: Boolean = true,
    var summary: String = "",
    val notes: MutableList<Note> = mutableListOf()
) {
    fun getDuration(): Long {
        return if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime
    }
}

data class Note(
    val content: String,
    val timestamp: Long
)
