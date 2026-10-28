package com.example.voicebrainlive.agent.connected

import android.content.Context
import android.content.SharedPreferences
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.beta.BetaTextProtocol
import com.example.voicebrainlive.security.SecureSettingsStorage
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class ConnectionFailure(val code:String,val retryAfterSeconds:Long?=null):IllegalStateException(code)
internal fun connectionCheck(ok:Boolean,code:String){if(!ok)throw ConnectionFailure(code)}
internal fun connectionHash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
internal fun notionUuid(raw:String):String {
    val s=raw.trim()
    connectionCheck(s.matches(Regex("[0-9a-fA-F]{32}|[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")),"NOTION_DATABASE_ID_REQUIRED")
    return s.replace("-","").lowercase(java.util.Locale.ROOT)
}
internal class ConnectionCredential(val target:String,private val secret:String,private val latest:()->Pair<String,String>) {
    private val stamp=connectionHash(target+"\n"+secret)
    fun isCurrent():Boolean=try{val p=latest();connectionHash(p.first+"\n"+p.second)==stamp}catch(_:Exception){false}
    /** Change-detection digest only. It is not a key, approval token or execution authority. */
    fun scheduleBinding():String=stamp
    fun requireSecret():String{connectionCheck(isCurrent(),"CONNECTION_SETTINGS_CHANGED");return secret}
    override fun toString()="ConnectionCredential(<redacted>)"
}
internal data class LegacyTaskMapping(val databaseId:String,val titleId:String,val businessStatusId:String,
    val unfinished:Set<String>,val priorityId:String?=null) {
    override fun toString()="LegacyTaskMapping(scope=<redacted>)"
}

/** Same encrypted secure_settings and legacy key names. Additive preferences, no credential migration. */
internal class ConnectedSettings(private val encrypted:()->SharedPreferences) {
    private fun <T> access(block:(SharedPreferences)->T):T=try{block(encrypted())}
        catch(e:ConnectionFailure){throw e}
        catch(_:Exception){throw ConnectionFailure("SECURE_SETTINGS_UNAVAILABLE")}
    fun textModel():String=access{it.getString(MODEL,"").orEmpty()}
    fun hasGeminiKey():Boolean=access{it.getString(SettingsActivity.KEY_GEMINI_API,"").orEmpty().isNotBlank()}
    fun hasNotion():Boolean=access{it.getString(SettingsActivity.KEY_NOTION_TOKEN,"").orEmpty().isNotBlank()&&it.getString(SettingsActivity.KEY_NOTION_DATABASE_ID,"").orEmpty().isNotBlank()}
    fun saveModel(raw:String):Boolean=access{p->
        val model=raw.trim().removePrefix("models/")
        connectionCheck(model.matches(Regex("[A-Za-z0-9._-]{1,80}"))&&!model.contains("live",true)&&!model.contains("audio",true),"TEXT_MODEL_REQUIRED")
        p.edit().putString(MODEL,model).commit()&&p.getString(MODEL,null)==model
    }
    fun saveGeminiKey(value:String,reviewed:Boolean):Boolean=access{p->
        connectionCheck(reviewed&&BetaTextProtocol.validKey(value),"REVIEW_EXACT_CREDENTIAL")
        p.edit().putString(SettingsActivity.KEY_GEMINI_API,value).commit()&&p.getString(SettingsActivity.KEY_GEMINI_API,null)==value
    }
    fun saveExistingNotion(token:String?,database:String,reviewed:Boolean):Boolean=access{p->
        connectionCheck(reviewed,"REVIEW_EXACT_CONNECTION")
        val db=notionUuid(database)
        val supplied=token?.takeIf{it.isNotBlank()}
        if(supplied!=null)connectionCheck(validNotionKey(supplied),"INVALID_NOTION_CREDENTIAL")
        val old=p.getString(SettingsActivity.KEY_NOTION_TOKEN,"").orEmpty()
        connectionCheck(validNotionKey(supplied?:old),"NOTION_CREDENTIAL_REQUIRED")
        val e=p.edit().putString(SettingsActivity.KEY_NOTION_DATABASE_ID,db)
        if(supplied!=null)e.putString(SettingsActivity.KEY_NOTION_TOKEN,supplied)
        e.commit()&&p.getString(SettingsActivity.KEY_NOTION_DATABASE_ID,null)==db&&p.getString(SettingsActivity.KEY_NOTION_TOKEN,null)==(supplied?:old)
    }
    fun notionDatabase():String=access{it.getString(SettingsActivity.KEY_NOTION_DATABASE_ID,"").orEmpty()}
    private fun geminiPair():Pair<String,String> = access{p->
        val model=p.getString(MODEL,"").orEmpty();val key=p.getString(SettingsActivity.KEY_GEMINI_API,"").orEmpty()
        connectionCheck(model.matches(Regex("[A-Za-z0-9._-]{1,80}"))&&!model.contains("live",true)&&!model.contains("audio",true),"TEXT_MODEL_REQUIRED")
        connectionCheck(BetaTextProtocol.validKey(key),"GEMINI_CREDENTIAL_REQUIRED")
        model to key // No BuildConfig or plaintext fallback.
    }
    private fun notionPair():Pair<String,String> = access{p->
        val key=p.getString(SettingsActivity.KEY_NOTION_TOKEN,"").orEmpty()
        connectionCheck(validNotionKey(key),"NOTION_CREDENTIAL_REQUIRED")
        notionUuid(p.getString(SettingsActivity.KEY_NOTION_DATABASE_ID,"").orEmpty()) to key
    }
    fun gemini():ConnectionCredential {val p=geminiPair();return ConnectionCredential(p.first,p.second,::geminiPair)}
    fun notion():ConnectionCredential {val p=notionPair();return ConnectionCredential(p.first,p.second,::notionPair)}
    fun saveMapping(value:LegacyTaskMapping,credential:ConnectionCredential,reviewed:Boolean):Boolean=access{p->
        connectionCheck(reviewed&&credential.isCurrent()&&value.databaseId==credential.target,"REVIEW_CURRENT_MAPPING")
        connectionCheck(value.titleId.isNotBlank()&&value.businessStatusId.isNotBlank()&&value.titleId!=value.businessStatusId&&
            value.unfinished.size in 1..10&&value.unfinished.all{it.isNotBlank()&&it.length<=80},"BUSINESS_STATUS_MAPPING_REQUIRED")
        val json=JSONObject().put("version",1).put("database",value.databaseId).put("title",value.titleId)
            .put("business",value.businessStatusId).put("unfinished",JSONArray(value.unfinished.sorted()))
            .put("priority",value.priorityId?:JSONObject.NULL).toString()
        p.edit().putString(MAPPING,json).commit()&&p.getString(MAPPING,null)==json&&credential.isCurrent()
    }
    fun mapping(database:String):LegacyTaskMapping?=access{p->
        val raw=p.getString(MAPPING,null)?:return@access null
        try{
            val j=JSONObject(raw);if(j.getInt("version")!=1||j.getString("database")!=database)return@access null
            val a=j.getJSONArray("unfinished");connectionCheck(a.length() in 1..10,"BUSINESS_STATUS_MAPPING_REQUIRED")
            LegacyTaskMapping(database,j.getString("title"),j.getString("business"),
                java.util.Collections.unmodifiableSet((0 until a.length()).map{a.getString(it)}.toSet()),j.optString("priority").takeIf{it.isNotBlank()&&it!="null"})
        }catch(_:Exception){throw ConnectionFailure("MAPPING_UNAVAILABLE")}
    }
    companion object {
        const val MODEL="agent_text_model_v1"
        const val MAPPING="agent_legacy_notion_mapping_v1"
        fun validNotionKey(s:String)=s.length in 1..512&&s.all{it.code in 33..126}
        fun android(context:Context):ConnectedSettings{
            val p by lazy{SecureSettingsStorage.require(context.applicationContext)}
            return ConnectedSettings{p}
        }
    }
}
