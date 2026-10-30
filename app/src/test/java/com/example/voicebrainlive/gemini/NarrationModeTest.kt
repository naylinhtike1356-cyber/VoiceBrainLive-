package com.example.voicebrainlive.gemini

import com.example.voicebrainlive.commands.ConnectionEpoch
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class NarrationModeTest {
    private fun frame()=JSONObject().put("setup",JSONObject().put("generationConfig",JSONObject()).put("tools",JSONArray().put("unsafe")).put("systemInstruction","old private context"))
    @Test fun narrationHasNoToolsNoPersonalContextAndFiniteOutput(){val c=GeminiLiveClient("synthetic-key");c.configureNarrationOnly();val f=frame();c.constrainNarrationSetup(f);val s=f.getJSONObject("setup");assertFalse(s.has("tools"));assertEquals(512,s.getJSONObject("generationConfig").getInt("maxOutputTokens"));assertFalse(s.toString().contains("old private context"));assertTrue(s.toString().contains("untrusted"));c.disconnect()}
    @Test fun ordinaryConversationSetupIsUnchanged(){val c=GeminiLiveClient("synthetic-key");val f=frame();val before=f.toString();c.constrainNarrationSetup(f);assertEquals(before,f.toString());c.disconnect()}
    @Test fun modelCannotInvokeAnyEffectInNarrationMode(){
        var calls=0;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val c=GeminiLiveClient("synthetic-key",scope){_,_,_->calls++;"BAD"};c.configureNarrationOnly()
        val epochOwner=ReflectionHelpers.getField<ConnectionEpoch>(c,"connectionEpoch");val epoch=epochOwner.open(scope.coroutineContext[Job])
        val method=c.javaClass.getDeclaredMethod("enqueueFunctionCall",JSONObject::class.java,ConnectionEpoch.Token::class.java).apply{isAccessible=true}
        method.invoke(c,JSONObject().put("id","attempt").put("name","execute_phone_command").put("args",JSONObject().put("command_type","make_call").put("target","0990000000")),epoch)
        assertEquals(0,calls);c.disconnect();scope.cancel()
    }
    @Test fun narrationDoesNotChangeModelOrRetry(){
        val c=GeminiLiveClient("synthetic-key",model="models/test-reviewed");c.configureNarrationOnly()
        val fallback=c.javaClass.getDeclaredMethod("checkAndTriggerModelFallback",String::class.java).apply{isAccessible=true};fallback.invoke(c,"404 model not found")
        val reconnect=c.javaClass.getDeclaredMethod("scheduleReconnect").apply{isAccessible=true};reconnect.invoke(c)
        assertEquals("models/test-reviewed",ReflectionHelpers.getField<String>(c,"model"));assertEquals(0,ReflectionHelpers.getField<Int>(c,"reconnectAttempt"));c.disconnect()
    }
    @Test fun narrationTextCannotImplicitlyConnectOrRetainPendingPrivateInput(){
        // Empty credentials guarantee no host network even if the guard regresses.
        val c=GeminiLiveClient("");c.configureNarrationOnly();val errors=mutableListOf<String>();c.onError={errors.add(it)}
        c.sendTextInput("synthetic private announcement")
        assertEquals(listOf(GeminiLiveClient.ERROR_NARRATION_NOT_READY),errors)
        assertNull(ReflectionHelpers.getField<String?>(c,"pendingTextInput"))
        assertNull(ReflectionHelpers.getField<okhttp3.WebSocket?>(c,"webSocket"));c.disconnect()
    }
    @Test fun failedNarrationAttemptRemainsSingleUseAfterDisconnect(){
        val c=GeminiLiveClient("");c.configureNarrationOnly();val errors=mutableListOf<String>();c.onError={errors.add(it)}
        c.connect();assertEquals(listOf(GeminiLiveClient.ERROR_API_KEY_REQUIRED),errors)
        c.disconnect();c.connect()
        assertEquals(listOf(GeminiLiveClient.ERROR_API_KEY_REQUIRED,GeminiLiveClient.ERROR_NARRATION_SINGLE_USE),errors)
        assertNull(ReflectionHelpers.getField<okhttp3.WebSocket?>(c,"webSocket"));c.disconnect()
    }
}
