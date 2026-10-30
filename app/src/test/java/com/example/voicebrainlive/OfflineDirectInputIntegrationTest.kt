package com.example.voicebrainlive

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.example.voicebrainlive.commands.ChatUiBridge
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

class DirectInputTestApplication:VoiceBrainApplication(){override val automaticStartupAllowed=false}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=DirectInputTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineDirectInputIntegrationTest {
    private val app get()=RuntimeEnvironment.getApplication() as DirectInputTestApplication
    // Preserve MediaHandler's documented 0..150 volume scale (150 maps to Android max).
    private val audio get()=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val store=ViewModelStore()
    private suspend fun until(check:()->Boolean){withTimeout(15_000){while(!check()){Shadows.shadowOf(Looper.getMainLooper()).idle();delay(10)}}}
    @After fun cleanup(){ChatUiBridge.onAssistantMessage=null;ChatUiBridge.onUserMessage=null;store.clear();Shadows.shadowOf(Looper.getMainLooper()).idle()}
    @Test fun ordinaryChatUsesRealRegistryBeforeAnyGeminiInitialization()=runBlocking<Unit>{
        val vm=MainActivityViewModel(app);store.put("main",vm)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,1,0)
        var reply="";ChatUiBridge.onAssistantMessage={reply=it}
        vm.processUserText("အသံ ၅၀ ရာခိုင်နှုန်းထားပေးပါ")
        until{reply.isNotBlank()}
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*50/150,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse(vm.isClientInitialized);assertEquals(reply,vm.transcript.value)
    }
    @Test fun offlineSpeechTextUsesSameRegistryAndCannotInitializeProvider()=runBlocking<Unit>{
        val vm=MainActivityViewModel(app);store.put("main",vm)
        val result=vm.executeLocalTextCommand("အသံ ၂၀ ရာခိုင်နှုန်းထားပေးပါ")
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*20/150,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(result.isNotBlank());assertFalse(vm.isClientInitialized)
    }
    @Test fun assistantTextUsesExistingExecutorWithoutOpeningProviderSocket()=runBlocking<Unit>{
        val vm=AssistantViewModel(app);store.put("assistant",vm)
        var reply="";ChatUiBridge.onAssistantMessage={reply=it}
        vm.sendTextInput("အသံ ၄၀ ရာခိုင်နှုန်းထားပေးပါ")
        until{reply.isNotBlank()}
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*40/150,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertFalse(vm.geminiClient.isConnected())
        val socket=vm.geminiClient.javaClass.getDeclaredField("webSocket").apply{isAccessible=true}.get(vm.geminiClient)
        assertNull(socket)
    }
}
