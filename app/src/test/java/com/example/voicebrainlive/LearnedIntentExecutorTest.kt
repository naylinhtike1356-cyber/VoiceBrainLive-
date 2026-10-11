package com.example.voicebrainlive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.learning.IntentPatternLearner
import com.example.voicebrainlive.learning.LearnedIntentExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LearnedIntentExecutorTest {

    private lateinit var context: Context
    private val phrase = "ကော်ဖီဖွင့်"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        IntentPatternLearner.clearPatterns(context)
    }

    private fun seedPattern(confirmed: Boolean) {
        IntentPatternLearner.recordPattern(
            context = context,
            phrase = phrase,
            commandType = "play_music",
            target = "coffee jazz",
            confirmedByUser = confirmed
        )
        if (confirmed) {
            IntentPatternLearner.confirmPattern(context, phrase)
        }
    }

    @Test
    fun tryExecute_runsWhenPatternIsConfident() {
        seedPattern(confirmed = true)
        val executor = com.example.voicebrainlive.commands.PhoneCommandExecutor(context)
        val result = runBlocking {
            LearnedIntentExecutor.tryExecute(context, phrase, executor)
        }
        assertNotNull(result)
    }

    @Test
    fun tryExecute_returnsNull_forUnknownPhrase() {
        val executor = com.example.voicebrainlive.commands.PhoneCommandExecutor(context)
        val result = runBlocking {
            LearnedIntentExecutor.tryExecute(context, "အမည်မသိစကား xyz", executor)
        }
        assertNull(result)
    }

    @Test
    fun tryExecuteConfirmedVoice_requiresConfirmedPattern() {
        val executor = com.example.voicebrainlive.commands.PhoneCommandExecutor(context)
        seedPattern(confirmed = false)
        val withoutConfirm = runBlocking {
            LearnedIntentExecutor.tryExecuteConfirmedVoice(context, phrase, executor)
        }
        assertNull(withoutConfirm)

        IntentPatternLearner.confirmPattern(context, phrase)
        val withConfirm = runBlocking {
            LearnedIntentExecutor.tryExecuteConfirmedVoice(context, phrase, executor)
        }
        assertNotNull(withConfirm)
    }
}
