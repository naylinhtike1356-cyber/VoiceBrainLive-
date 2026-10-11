package com.example.voicebrainlive

import com.example.voicebrainlive.commands.ActionDispatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionDispatcherTest {

    @Test
    fun resolve_standardCommandFields() {
        val args = JSONObject()
            .put("command_type", "play_spotify")
            .put("target", "Lo-fi")
            .put("value", "study focus lofi")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("play_spotify", resolved.commandType)
        assertEquals("Lo-fi", resolved.target)
        assertEquals("study focus lofi", resolved.value)
    }

    @Test
    fun resolve_blankCommandType_returnsEmptyCommand() {
        val resolved = ActionDispatcher.resolve(JSONObject().put("target", "spotify"))

        assertEquals("", resolved.commandType)
        assertNull(resolved.target)
        assertNull(resolved.value)
    }

    @Test
    fun resolve_fallbackToName_whenCommandTypeIsMissing() {
        val args = JSONObject()
            .put("name", "get_weather")
            .put("target", "Seoul")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("get_weather", resolved.commandType)
        assertEquals("Seoul", resolved.target)
    }

    @Test
    fun resolve_fallbackToCommand_whenCommandTypeAndNameAreMissing() {
        val args = JSONObject()
            .put("command", "get_battery_status")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("get_battery_status", resolved.commandType)
    }

    @Test
    fun resolve_fallbackToQuery_whenTargetIsMissing() {
        val args = JSONObject()
            .put("command_type", "web_search")
            .put("query", "news about Myanmar")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("web_search", resolved.commandType)
        assertEquals("news about Myanmar", resolved.target)
    }

    @Test
    fun resolve_fallbackToNameAsTarget_whenTargetIsMissingAndNameIsDistinct() {
        val args = JSONObject()
            .put("command_type", "make_call")
            .put("name", "Aung Aung")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("make_call", resolved.commandType)
        assertEquals("Aung Aung", resolved.target)
    }

    @Test
    fun resolve_reroutesMusicLikeCallAwayFromPhoneCall() {
        val args = JSONObject()
            .put("command_type", "make_call")
            .put("target", "spotify music")
            .put("value", "lofi playlist")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("play_spotify", resolved.commandType)
        assertEquals("spotify music", resolved.target)
        assertEquals("lofi playlist", resolved.value)
    }

    @Test
    fun resolve_musicCommandWithArtist() {
        val args = JSONObject()
            .put("command_type", "play_spotify")
            .put("artist", "Taylor Swift")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("play_spotify", resolved.commandType)
        assertEquals("Taylor Swift", resolved.target)
        assertEquals("Taylor Swift", resolved.value)
    }

    @Test
    fun resolve_musicCommandWithGenre() {
        val args = JSONObject()
            .put("command_type", "play_spotify")
            .put("genre", "lofi")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("play_spotify", resolved.commandType)
        assertEquals("lofi", resolved.target)
        assertEquals("lofi hip hop chill", resolved.value)
    }

    @Test
    fun resolve_musicCommandWithMood() {
        val args = JSONObject()
            .put("command_type", "play_spotify")
            .put("mood", "မြူး")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("play_spotify", resolved.commandType)
        assertEquals("မြူး", resolved.target)
        assertEquals("feel good upbeat pop", resolved.value)
    }

    @Test
    fun resolve_musicCommandWithArtistAndGenre() {
        val args = JSONObject()
            .put("command_type", "play_spotify")
            .put("artist", "Taylor Swift")
            .put("genre", "rock")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("play_spotify", resolved.commandType)
        assertEquals("rock Taylor Swift", resolved.target)
        assertEquals("Taylor Swift rock hits", resolved.value)
    }

    @Test
    fun resolve_explicitBurmeseCallWithGenericVerb_staysPhoneCall() {
        val args = JSONObject()
            .put("command_type", "make_call")
            .put("target", "မမ")
            .put("value", "ဖွင့်")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("make_call", resolved.commandType)
        assertEquals("မမ", resolved.target)
        assertEquals("ဖွင့်", resolved.value)
    }

    @Test
    fun resolve_callContactWithoutMusicTerms_staysPhoneCall() {
        val args = JSONObject()
            .put("command_type", "call_contact")
            .put("recipient", "မောင်မောင်")

        val resolved = ActionDispatcher.resolve(args)

        assertEquals("call_contact", resolved.commandType)
        assertEquals("မောင်မောင်", resolved.target)
    }

    @Test
    fun isExplicitReplyRequest_acceptsExplicitVoiceCommands() {
        // User prefix pattern
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("ပြန်ပြောလိုက် အခုအပြင်မှာ"))
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("ပြန်ပို့လိုက်ပါ အလုပ်များနေတယ်"))
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("မက်ဆင်ဂျာကနေ ပြန်ပြော အိုကေ လို့"))
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("reply I am driving right now"))

        // User suffix pattern
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("နာရီဝက်နေရင် ပြန်ဆက်မယ် လို့ ပြန်ပြောလိုက်"))
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("အခု မအားသေးဘူး လို့ ပြန်ပို့လိုက်ပါ"))
    }

    @Test
    fun isExplicitReplyRequest_rejectsPassiveAnnouncementsAndEcho() {
        // Audio echo from AI notification announcements
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("မင်းမင်း က စာပို့ထားပါတယ်ခင်ဗျာ"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("စာပို့ထားတယ်"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("စာပို့ထားတာ"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("နိုတီဝင်တယ်"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("notification"))
    }

    @Test
    fun isExplicitReplyRequest_rejectsDismissalsAndNegations() {
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("မပို့နဲ့"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("မပြောပါနဲ့"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("ရပြီ"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("တော်ပြီ"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("ပိတ်လိုက်"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("don't reply"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("cancel"))
    }

    @Test
    fun isExplicitReplyRequest_rejectsBlankOrEmpty() {
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest(""))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("   "))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest(null))
    }

    @Test
    fun cleanReplyMessage_stripsWrappersCorrectly() {
        assertEquals("အခု အပြင်ရောက်နေတယ်", ActionDispatcher.cleanReplyMessage("အခု အပြင်ရောက်နေတယ် လို့ ပြန်ပြောလိုက်"))
        assertEquals("နာရီဝက်နေရင် ပြန်ဆက်မယ်", ActionDispatcher.cleanReplyMessage("ပြန်ပြောလိုက်ပါ နာရီဝက်နေရင် ပြန်ဆက်မယ်"))
        assertEquals("Hello world", ActionDispatcher.cleanReplyMessage("\"Hello world\""))
    }

    @Test
    fun cleanReplyMessage_stripsBurmeseSuffixesWithoutSpaces() {
        assertEquals("ဟုတ်တယ်", ActionDispatcher.cleanReplyMessage("ဟုတ်တယ်လို့ပြန်ပြောလိုက်ပါ"))
        assertEquals("မအားသေးဘူး", ActionDispatcher.cleanReplyMessage("မအားသေးဘူးလို့ပြောလိုက်"))
        assertEquals("မနက်ဖြန်လာခဲ့မယ်", ActionDispatcher.cleanReplyMessage("မနက်ဖြန်လာခဲ့မယ်လို့ပြောပေးပါ"))
        assertEquals("အိုကေ", ActionDispatcher.cleanReplyMessage("အိုကေလို့ပို့လိုက်ပါ"))
    }

    @Test
    fun cleanReplyMessage_stripsBurmesePrefixes() {
        assertEquals("အိုကေ", ActionDispatcher.cleanReplyMessage("မက်ဆင်ဂျာကနေ ပြန်ပြော အိုကေ"))
        assertEquals("မနက်ဖြန်မှ လာခဲ့မယ်", ActionDispatcher.cleanReplyMessage("စာပြန်ပို့ပေး မနက်ဖြန်မှ လာခဲ့မယ်"))
        assertEquals("လာနေပြီ", ActionDispatcher.cleanReplyMessage("ပြန်ပို့လိုက် လာနေပြီ"))
    }

    @Test
    fun resolve_sendMessenger_extractsAlternativeFields() {
        val argsMessage = JSONObject().put("command_type", "send_messenger").put("message", "ဟုတ်တယ်")
        assertEquals("ဟုတ်တယ်", ActionDispatcher.resolve(argsMessage).value)

        val argsText = JSONObject().put("command_type", "send_messenger").put("text", "မအားဘူး")
        assertEquals("မအားဘူး", ActionDispatcher.resolve(argsText).value)

        val argsContent = JSONObject().put("command_type", "send_messenger").put("content", "ရောက်ပြီ")
        assertEquals("ရောက်ပြီ", ActionDispatcher.resolve(argsContent).value)
    }

    @Test
    fun resolve_sendMessenger_doesNotInferPayloadOrAmbientRecipient_whenValueMissing() {
        val args = JSONObject()
            .put("command_type", "send_messenger")
            .put("target", "ဟုတ်တယ်လို့ပြန်ပြောလိုက်ပါ")

        val resolved = ActionDispatcher.resolve(args)
        assertEquals("ဟုတ်တယ်လို့ပြန်ပြောလိုက်ပါ", resolved.target)
        assertNull(resolved.value)
    }

    @Test
    fun isExplicitReplyRequest_acceptsBurmeseSpeechVariants() {
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("ဟုတ်တယ် လို့ ပြောလိုက်ပါ"))
        org.junit.Assert.assertTrue(ActionDispatcher.isExplicitReplyRequest("အခုလာနေပြီ လို့ ပို့ပေး"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("မလိုဘူး"))
    }

    @Test
    fun isExplicitReplyRequest_rejectsExtendedDismissals() {
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("မပြောနဲ့"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("တော်ပါပြီ"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("ပိတ်တော့"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("မလိုတော့ဘူး"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("မပြောတော့ဘူး"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("don't send"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("stop"))
        org.junit.Assert.assertFalse(ActionDispatcher.isExplicitReplyRequest("no"))
    }

    @Test
    fun cleanReplyMessage_stripsAiPleasantryPrefixes() {
        assertEquals("ငါ အလုပ်များနေတယ်", ActionDispatcher.cleanReplyMessage("မင်္ဂလာပါခင်ဗျာ ငါ အလုပ်များနေတယ်"))
        assertEquals("အခု လာနေပြီ", ActionDispatcher.cleanReplyMessage("မင်္ဂလာပါ အခု လာနေပြီ"))
        assertEquals("မနက်ဖြန်မှ တွေ့မယ်", ActionDispatcher.cleanReplyMessage("ဟုတ်ကဲ့ပါခင်ဗျာ မနက်ဖြန်မှ တွေ့မယ်"))
        assertEquals("အပြင်မှာ မိုးရွာနေတယ်", ActionDispatcher.cleanReplyMessage("သခင်က အခုလိုပြောခိုင်းလိုက်ပါတယ် အပြင်မှာ မိုးရွာနေတယ်"))
    }

    @Test
    fun cleanReplyMessage_preservesGreetingWhenItIsEntireMessage() {
        assertEquals("မင်္ဂလာပါ", ActionDispatcher.cleanReplyMessage("မင်္ဂလာပါ"))
    }

    @Test
    fun cleanReplyMessage_stripsEnglishAndDirectBurmesePrefixes() {
        assertEquals("I will be late", ActionDispatcher.cleanReplyMessage("tell him I will be late"))
        assertEquals("see you tomorrow", ActionDispatcher.cleanReplyMessage("say to her see you tomorrow"))
        assertEquals("အခု လာခဲ့မယ်", ActionDispatcher.cleanReplyMessage("သူ့ကို ပြန်ပြောလိုက်ပါ အခု လာခဲ့မယ်"))
        assertEquals("အိုကေပါပြီ", ActionDispatcher.cleanReplyMessage("သူ့ဆီ ပြန်ပို့ပေးပါ အိုကေပါပြီ"))
    }
}
