package com.example.voicebrainlive.memory

import android.content.Context
import com.example.voicebrainlive.routines.RoutineStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Open, versioned JSON backup & restore for the user's personal memory, notes,
 * conversation history, and routines. Designed for multi-decade portability across devices.
 */
object PortableMemoryBackup {

    const val SCHEMA_VERSION = 1

    data class ImportSummary(
        val factsRestored: Int,
        val notesRestored: Int,
        val turnsRestored: Int,
        val routinesRestored: Int
    )

    suspend fun exportJson(context: Context): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val db = VoiceBrainDatabase.getInstance(appContext)

        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("exportedAtMs", System.currentTimeMillis())
            put("app", "VoiceBrainLive")
        }

        val factsObj = JSONObject()
        PersistentFactsStorage.getAllFacts(appContext).forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                factsObj.put(key, value)
            }
        }
        root.put("facts", factsObj)

        val notesArr = JSONArray()
        db.noteDao().getAllNotes().forEach { note ->
            notesArr.put(JSONObject().apply {
                put("title", note.title)
                put("content", note.content)
                put("timestamp", note.timestamp)
            })
        }
        root.put("notes", notesArr)

        val turnsArr = JSONArray()
        db.conversationTurnDao().getAllTurns().forEach { turn ->
            turnsArr.put(JSONObject().apply {
                put("sessionId", turn.sessionId)
                put("role", turn.role)
                put("content", turn.content)
                put("timestamp", turn.timestamp)
            })
        }
        root.put("conversationTurns", turnsArr)

        val routinesArr = JSONArray()
        RoutineStorage.getAll(appContext).forEach { r ->
            routinesArr.put(JSONObject().apply {
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
                put("name", r.name)
                put("triggerPhrase", r.triggerPhrase)
                put("ringerMode", r.ringerMode)
                put("volumePercent", r.volumePercent)
                put("sleepTimerMinutes", r.sleepTimerMinutes)
                put("repeatDays", JSONArray(r.repeatDays))
                put("actionsSequence", r.actionsSequence)
            })
        }
        root.put("routines", routinesArr)

        root.toString(2)
    }

    suspend fun importJson(
        context: Context,
        rawJson: String,
        replaceExisting: Boolean = false
    ): ImportSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val root = JSONObject(rawJson)
        val version = root.optInt("schemaVersion", -1)
        require(version == SCHEMA_VERSION) { "Unsupported backup schemaVersion: $version" }

        val db = VoiceBrainDatabase.getInstance(appContext)
        if (replaceExisting) {
            PersistentFactsStorage.clearFacts(appContext)
            db.noteDao().clearAllNotes()
            db.conversationTurnDao().deleteAll()
            RoutineStorage.clearAll(appContext)
        }

        var factsRestored = 0
        root.optJSONObject("facts")?.let { factsObj ->
            factsObj.keys().forEach { key ->
                val value = factsObj.optString(key, "")
                if (PersistentFactsStorage.addOrUpdateFact(appContext, key, value)) {
                    factsRestored++
                }
            }
        }

        var notesRestored = 0
        val existingNotes = db.noteDao().getAllNotes()
            .map { Triple(it.title, it.content, it.timestamp) }
            .toHashSet()
        root.optJSONArray("notes")?.let { notesArr ->
            for (i in 0 until notesArr.length()) {
                val obj = notesArr.optJSONObject(i) ?: continue
                val title = obj.optString("title", "")
                val content = obj.optString("content", "")
                val ts = obj.optLong("timestamp", 0L)
                if (title.isBlank() && content.isBlank()) continue
                val key = Triple(title, content, ts)
                if (existingNotes.add(key)) {
                    db.noteDao().insertNote(NoteEntity(title = title, content = content, timestamp = ts))
                    notesRestored++
                }
            }
        }

        var turnsRestored = 0
        val existingTurns = db.conversationTurnDao().getAllTurns()
            .map { "${it.sessionId}|${it.role}|${it.content}|${it.timestamp}" }
            .toHashSet()
        root.optJSONArray("conversationTurns")?.let { turnsArr ->
            for (i in 0 until turnsArr.length()) {
                val obj = turnsArr.optJSONObject(i) ?: continue
                val sessionId = obj.optString("sessionId", "")
                val role = obj.optString("role", "model")
                val content = obj.optString("content", "")
                val ts = obj.optLong("timestamp", 0L)
                if (sessionId.isBlank() || content.isBlank()) continue
                val key = "$sessionId|$role|$content|$ts"
                if (existingTurns.add(key)) {
                    db.conversationTurnDao().insert(
                        ConversationTurnEntity(
                            sessionId = sessionId,
                            role = role,
                            content = content,
                            timestamp = ts
                        )
                    )
                    turnsRestored++
                }
            }
        }

        var routinesRestored = 0
        val existingRoutineKeys = RoutineStorage.getAll(appContext)
            .map { "${it.type}|${it.hour}|${it.minute}|${it.name}|${it.message}" }
            .toHashSet()
        root.optJSONArray("routines")?.let { routinesArr ->
            for (i in 0 until routinesArr.length()) {
                val obj = routinesArr.optJSONObject(i) ?: continue
                val daysArr = obj.optJSONArray("repeatDays")
                val repeatDays = if (daysArr != null) {
                    (0 until daysArr.length()).map { daysArr.getInt(it) }
                } else emptyList()

                val item = RoutineStorage.RoutineItem(
                    id = 0, // Allocate clean ID on target device
                    type = obj.optString("type", "reminder"),
                    hour = obj.optInt("hour", 7),
                    minute = obj.optInt("minute", 0),
                    message = obj.optString("message", ""),
                    dayOfWeek = obj.optInt("dayOfWeek", 0),
                    musicQuery = obj.optString("musicQuery", ""),
                    reminderText = obj.optString("reminderText", ""),
                    reminderMinutesBefore = obj.optInt("reminderMinutesBefore", 0),
                    enabled = obj.optBoolean("enabled", true),
                    useLiveAgent = obj.optBoolean("useLiveAgent", true),
                    name = obj.optString("name", ""),
                    triggerPhrase = obj.optString("triggerPhrase", ""),
                    ringerMode = obj.optString("ringerMode", ""),
                    volumePercent = obj.optInt("volumePercent", -1),
                    sleepTimerMinutes = obj.optInt("sleepTimerMinutes", 0),
                    repeatDays = repeatDays,
                    actionsSequence = obj.optString("actionsSequence", "")
                )
                val key = "${item.type}|${item.hour}|${item.minute}|${item.name}|${item.message}"
                if (existingRoutineKeys.add(key)) {
                    RoutineStorage.saveRoutine(appContext, item)
                    routinesRestored++
                }
            }
        }

        SessionContextProvider.invalidateCache(appContext)
        ImportSummary(factsRestored, notesRestored, turnsRestored, routinesRestored)
    }
}
