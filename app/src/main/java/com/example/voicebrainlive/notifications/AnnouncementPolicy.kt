package com.example.voicebrainlive.notifications

import java.security.MessageDigest
import java.util.UUID

/** Ephemeral private content. Never log/serialize this object or treat its text as a command. */
internal data class Announcement(val id:String,val key:String,val packageName:String,val app:String,
                                 val title:String,val text:String,val created:Long) {
    override fun toString()="Announcement(content=<redacted>)"
}

internal class AnnouncementQueue(private val now:()->Long,private val ids:()->String={UUID.randomUUID().toString()}) {
    private val waiting=linkedMapOf<String,Announcement>()
    private val seen=linkedMapOf<String,Long>()
    @Synchronized fun offer(key:String,pkg:String,app:String,title:String,text:String,selected:Set<String>):Announcement? {
        prune();if(pkg !in selected||key.isBlank()||pkg.length>180||app.isBlank())return null
        val content="$title $text"
        if(content.isBlank()||content.length>8000||secret.containsMatchIn(content))return null
        val hash=digest("$key\u0000$title\u0000$text")
        if(hash in seen||waiting.size>=8)return null
        seen[hash]=now();while(seen.size>32)seen.remove(seen.keys.first())
        return Announcement(ids(),key,pkg,app.take(60),title.take(100),text.take(480),now()).also{waiting[it.id]=it}
    }
    @Synchronized fun peek(id:String):Announcement? {prune();return waiting[id]}
    @Synchronized fun claim(id:String):Announcement? {prune();return waiting.remove(id)}
    @Synchronized fun removed(key:String){waiting.entries.removeAll{it.value.key==key}}
    @Synchronized fun clear(){waiting.clear();seen.clear()}
    private fun prune(){val n=now();waiting.entries.removeAll{n<it.value.created||n-it.value.created>120_000};seen.entries.removeAll{n<it.value||n-it.value>120_000}}
    companion object {
        internal val secret=Regex("(?i)\\b(?:otp|password|passcode|verification|authentication|security code|one.time)\\b|အတည်ပြုကုဒ်|လျှို့ဝှက်|비밀번호|인증번호|인증코드|\\b[0-9၀-၉]{4,8}\\b")
        fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}

/** Hardware drain is outside this clock policy; caller includes queued PCM and a conservative tail. */
internal class AnnouncementLifetime(private val now:()->Long) {
    private val started=now()
    private var generated=false
    private var completed=false
    private var tail=started
    private var quietSince:Long?=null
    fun audio(bytes:Int){require(bytes in 1..96_000);generated=true;tail=maxOf(tail,now())+bytes*1000L/48_000+20}
    fun turnComplete(){completed=true}
    fun shouldClose(queued:Boolean):Boolean {
        val n=now();if(n<started||n-started>=120_000)return true
        if(!generated)return n-started>=20_000
        if(!completed||queued||n<tail+500){quietSince=null;return false}
        val quiet=quietSince?:n.also{quietSince=it}
        return n-quiet>=15_000
    }
}
