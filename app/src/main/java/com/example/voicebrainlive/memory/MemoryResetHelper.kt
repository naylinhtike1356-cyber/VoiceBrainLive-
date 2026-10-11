package com.example.voicebrainlive.memory

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.ReminderHelper
import com.example.voicebrainlive.UserPreferences
import com.example.voicebrainlive.learning.FeedbackLearning
import com.example.voicebrainlive.learning.IntentPatternLearner
import com.example.voicebrainlive.learning.PersonalProfileEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clears all user-generated assistant memory.
 *
 * Room-backed tables are cleared in one database transaction. File and
 * SharedPreferences stores cannot participate in that Room transaction, so
 * they are cleared with per-store status and verified afterward. This avoids
 * reporting success when only part of the memory was deleted.
 */
object MemoryResetHelper {
    private const val TAG = "MemoryResetHelper"

    data class ResetResult(
        val success: Boolean,
        val clearedStores: List<String>,
        val failedStores: List<String>,
        val verificationFailures: List<String>
    ) {
        val message: String
            get() = if (success) {
                "Memory အားလုံးကို အောင်မြင်စွာ ရှင်းလင်းပြီးပါပြီ။"
            } else {
                "Memory reset မပြည့်စုံပါ။ မရှင်းလင်းနိုင်သေးသောအပိုင်း: " +
                    (failedStores + verificationFailures).distinct().joinToString(", ")
            }
    }

    suspend fun resetAllMemory(context: Context): ResetResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cleared = mutableListOf<String>()
        val failed = mutableListOf<String>()

        fun clearStore(name: String, action: () -> Unit) {
            try {
                action()
                cleared += name
            } catch (e: Exception) {
                failed += name
                Log.e(TAG, "Failed to clear $name", e)
            }
        }

        Log.i(TAG, "Starting full memory reset")

        // Atomic for the Room-backed conversation, embeddings, and notes tables.
        clearStore("room_memory") {
            VoiceBrainDatabase.getInstance(appContext).runInTransaction {
                VoiceBrainDatabase.getInstance(appContext).clearAllTables()
            }
        }

        clearStore("session_summaries") { SummarizationSessionHelper.clearSummaries(appContext) }
        clearStore("behavior_memory") { BehaviorMemory.clearAll(appContext) }
        clearStore("intent_patterns") { IntentPatternLearner.clearPatterns(appContext) }
        clearStore("feedback_learning") { FeedbackLearning(appContext).clearAll() }
        clearStore("persistent_facts") { PersistentFactsStorage.clearFacts(appContext) }
        clearStore("personal_profile") { PersonalProfileEngine.clearProfile(appContext) }
        clearStore("user_preferences") { UserPreferences.clearAll(appContext) }
        clearStore("vector_cache") { VectorStore.clearCache() }
        clearStore("voice_notes_legacy") {
            appContext.getSharedPreferences("voice_notes", Context.MODE_PRIVATE).edit().clear().commit()
        }
        clearStore("voice_reminders") { ReminderHelper.clearAllReminders(appContext) }

        SessionContextProvider.invalidateCache(appContext)

        val verificationFailures = mutableListOf<String>()
        fun verifyEmpty(name: String, check: () -> Boolean) {
            try {
                if (!check()) verificationFailures += name
            } catch (e: Exception) {
                verificationFailures += name
                Log.e(TAG, "Verification failed for $name", e)
            }
        }

        verifyEmpty("session_summaries") { SummarizationSessionHelper.getSummaries(appContext, 1).isEmpty() }
        verifyEmpty("persistent_facts") { PersistentFactsStorage.getAllFacts(appContext).isEmpty() }
        verifyEmpty("recent_conversation") { SimpleConversationStorage.getRecentForContext(appContext, 1).isEmpty() }
        try {
            if (NoteRepository(appContext).getAllNotes().isNotEmpty()) verificationFailures += "notes"
        } catch (e: Exception) {
            verificationFailures += "notes"
            Log.e(TAG, "Verification failed for notes", e)
        }
        verifyEmpty("context_cache") { SessionContextProvider.getPersistentContext(appContext).isEmpty() }

        val success = failed.isEmpty() && verificationFailures.isEmpty()
        Log.i(TAG, "Memory reset finished: success=$success cleared=${cleared.size} failed=${failed.size} verificationFailures=${verificationFailures.size}")
        ResetResult(success, cleared, failed, verificationFailures)
    }
}

