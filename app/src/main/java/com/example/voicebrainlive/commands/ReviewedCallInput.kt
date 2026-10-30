package com.example.voicebrainlive.commands

import java.text.Normalizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Direct USER input -> existing common guard. Recognizing a request is never permission to call. */
object ReviewedCallInput {
    private val forbidden=Regex("[?？\"“”'‘’;\n\r]|မခေါ်|မဆက်|လား|မနက်ဖြန်|နောက်မှ|ပြီးတော့|ပြီးရင်|နှင့်|နဲ့|\\b(?:not|don't|tomorrow|later|and|or|then|if|when)\\b",RegexOption.IGNORE_CASE)
    private val burmese=Regex("(.+?)(?:\\s*ကို)?\\s*ဖုန်း\\s*(?:ခေါ်|ဆက်)(?:ပေး(?:လိုက်)?(?:ပါ)?(?:ဦး|နော်)?|လိုက်(?:ပါ)?|ပါ)?")
    private val english=Regex("(?:call|phone)\\s+(.+)",RegexOption.IGNORE_CASE)
    fun target(text:String):String? {
        if(text.length !in 1..180||forbidden.containsMatchIn(text))return null
        val s=Normalizer.normalize(text,Normalizer.Form.NFC).trim().trimEnd('။','.','!').trim()
        val raw=(burmese.matchEntire(s)?.groupValues?.get(1)?:english.matchEntire(s)?.groupValues?.get(1))?.trim()?:return null
        if(raw.isBlank()||raw.length>80||raw.any{it in "{}[]=<>:/\\"})return null
        if(raw.any{it.isDigit()||it in '၀'..'၉'})return raw.takeIf{ActionTargetResolver.phone(it)!=null}
        return raw.takeIf{it.all{c->c.isLetter()||c.isWhitespace()||c in "-._"||c.code in 0x1000..0x109f}&&it.none{c->c in "။၊"}}
    }
    suspend fun execute(text:String,current:()->Boolean={true},dispatch:suspend(String,String?,String?)->String):String? {
        val target=target(text)?:return null
        check(CommandSafetyPolicy.classify("make_call")==CommandSafetyPolicy.Classification.SENSITIVE)
        currentCoroutineContext().ensureActive()
        if(!current())return "BLOCKED:STALE_CALL_INPUT"
        // No token, confirmation, biometric result or callable number can be supplied by a model here.
        return try{dispatch("make_call",target,null)}
        catch(c:kotlinx.coroutines.CancellationException){throw c}
        catch(_:Exception){"FAILED:CALL_REQUEST_UNAVAILABLE"}
    }
}
