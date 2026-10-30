package com.example.voicebrainlive.agent.connected

import android.content.Context
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.memory.confirmed.AtomicMemoryBytes
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.json.JSONArray
import org.json.JSONObject

internal class ConnectedFakeCalls:Call.Factory {
    val requests=CopyOnWriteArrayList<Request>();val calls=CopyOnWriteArrayList<ConnectedFakeCall>()
    @Volatile var responder:(ConnectedFakeCall)->Unit={it.respond(200,"{}")}
    override fun newCall(request:Request):Call {requests.add(request);return ConnectedFakeCall(request){responder(it)}.also{calls.add(it)}}
}
internal class ConnectedFakeCall(private val r:Request,private val action:(ConnectedFakeCall)->Unit):Call {
    @Volatile var callback:Callback?=null
    @Volatile var cancelled=false
    override fun request()=r
    override fun execute():Response=throw AssertionError("Only asynchronous HTTP is allowed")
    override fun enqueue(responseCallback:Callback){check(callback==null);callback=responseCallback;action(this)}
    override fun cancel(){cancelled=true}
    override fun isExecuted()=callback!=null
    override fun isCanceled()=cancelled
    override fun timeout()=Timeout()
    override fun clone():Call=throw AssertionError("No automatic retry")
    fun respond(status:Int,text:String,type:String="application/json",headers:Headers=Headers.Builder().build()){
        requireNotNull(callback).onResponse(this,Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(status).message("host fixture")
            .headers(headers).body(text.toResponseBody(type.toMediaType())).build())
    }
    fun fail(){requireNotNull(callback).onFailure(this,IOException("fixture transport failure must be sanitized"))}
}
internal fun connectedEnvelope(text:String,finish:String="STOP",tokens:String="21"):String {
    val root=JsonObject().apply{add("candidates",JsonArray().apply{add(JsonObject().apply{
        addProperty("finishReason",finish);add("content",JsonObject().apply{add("parts",JsonArray().apply{add(JsonObject().apply{addProperty("text",text)})})})
    })})}
    return root.toString().dropLast(1)+",\"usageMetadata\":{\"totalTokenCount\":$tokens}}"
}
internal fun connectedPlan(goal:String,tool:String="get_current_time"):String=JsonObject().apply{
    addProperty("version",1);addProperty("goal",goal)
    add("steps",JsonArray().apply{add(JsonObject().apply{addProperty("id","clock");addProperty("tool",tool)
        add("dependencies",JsonArray());add("arguments",JsonObject());addProperty("expected","CLOCK")})})
}.toString()
internal fun connectedStudyPlan(goal:String):String {
    val root=JsonObject().apply{addProperty("version",1);addProperty("goal",goal)}
    val steps=JsonArray()
    fun step(id:String,tool:String,dep:String?,expected:String,args:Map<String,String> = emptyMap()){
        steps.add(JsonObject().apply{addProperty("id",id);addProperty("tool",tool);addProperty("expected",expected)
            add("dependencies",JsonArray().apply{dep?.let{add(it)}});add("arguments",JsonObject().apply{args.forEach{(k,v)->addProperty(k,v)}})})
    }
    step("tasks","agent_read_tasks",null,"TASKS")
    step("top","agent_prioritize_tasks","tasks","PRIORITIES",mapOf("sourceStep" to "tasks"))
    step("draft","agent_study_draft","top","DRAFT",mapOf("sourceStep" to "top","minutes" to "20"))
    root.add("steps",steps);return root.toString()
}
internal object ConnectedNotionFixture {
    const val DB="11111111111111111111111111111111"
    const val OTHER="22222222222222222222222222222222"
    val mapping=LegacyTaskMapping(DB,"title","business",setOf("Todo"),"priority")
    fun schema():String {
        fun property(id:String,type:String,options:List<String> = emptyList())=JSONObject().put("id",id).put("type",type).apply{
            if(type in setOf("status","select"))put(type,JSONObject().put("options",JSONArray(options.map{JSONObject().put("name",it)})))
        }
        return JSONObject().put("object","database").put("id",DB).put("properties",JSONObject()
            .put("Name",property("title","title")).put("Content",property("content","rich_text"))
            .put("Task state",property("business","status",listOf("Todo","Done")))
            .put("Status",property("sync","select",listOf("Pending","Synced","Failed")))
            .put("Priority",property("priority","number"))).toString()
    }
    fun row(id:Int=1,title:String="PRIVATE_NOTION_NOTE",business:String?="Todo",sync:String="Synced",db:String=DB,priority:Int=80):JSONObject {
        fun text(id:String,type:String,text:String)=JSONObject().put("id",id).put("type",type).put(type,JSONArray().put(JSONObject().put("plain_text",text)))
        return JSONObject().put("object","page").put("id",id.toString(16).padStart(32,'0'))
            .put("archived",false).put("parent",JSONObject().put("type","database_id").put("database_id",db))
            .put("properties",JSONObject().put("Name",text("title","title",title)).put("Content",text("content","rich_text","PRIVATE_NOTION_CONTENT"))
                .put("Task state",JSONObject().put("id","business").put("type","status").put("status",business?.let{JSONObject().put("name",it)}?:JSONObject.NULL))
                .put("Status",JSONObject().put("id","sync").put("type","select").put("select",JSONObject().put("name",sync)))
                .put("Priority",JSONObject().put("id","priority").put("type","number").put("number",priority)))
    }
    fun page(rows:List<JSONObject> = listOf(row()),more:Boolean=false,cursor:String?=null)=JSONObject().put("object","list")
        .put("results",JSONArray(rows)).put("has_more",more).put("next_cursor",cursor?:JSONObject.NULL).toString()
}
internal class ConnectedFixture(context:Context) {
    val prefs=context.getSharedPreferences("host-connected-fixture-only",0)
    val settings=ConnectedSettings{prefs}
    val http=ConnectedFakeCalls()
    val notionCalls=CopyOnWriteArrayList<Pair<LegacyReadOperation,String?>>()
    @Volatile var notionReply:(LegacyReadOperation,String?)->LegacyHttpReply={op,_->LegacyHttpReply(200,
        (if(op==LegacyReadOperation.SCHEMA)ConnectedNotionFixture.schema()else ConnectedNotionFixture.page()).toByteArray())}
    var bytes:ByteArray?=null
    private val aes=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
    val store=EncryptedBrainStore(object:AtomicMemoryBytes{
        override fun readOrNull()=bytes?.copyOf()
        override fun replaceAtomically(bytes:ByteArray){this@ConnectedFixture.bytes=bytes.copyOf()}
    }){aes}
    val reads=AtomicInteger()
    val memoryReads=AtomicInteger()
    var clock:()->Long=System::currentTimeMillis
    init{prefs.edit().putString(SettingsActivity.KEY_GEMINI_API,"host-connected-gemini-key")
        .putString(SettingsActivity.KEY_GEMINI_MODEL,"models/existing-live-model")
        .putString(SettingsActivity.KEY_NOTION_TOKEN,"host-connected-notion-token")
        .putString(SettingsActivity.KEY_NOTION_DATABASE_ID,ConnectedNotionFixture.DB)
        .putString(ConnectedSettings.MODEL,"host-text-model").commit()
        http.responder={it.respond(200,connectedEnvelope("Actual host fixture answer"))}
    }
    fun workspace(owner:()->Boolean={true})=ConnectedWorkspace(settings,store,object:AgentReadPort{
        override suspend fun clock():AgentObservation{reads.incrementAndGet();return AgentObservation(AgentStatus.SUCCEEDED,AgentData.Clock("HOST_CLOCK_READ"))}
        override suspend fun battery():AgentObservation{reads.incrementAndGet();return AgentObservation(AgentStatus.SUCCEEDED,AgentData.Battery(88))}
        override suspend fun tasks(connection:AgentConnectionPin):AgentObservation=error("Local/captured task adapter owns reads")
    },BrainMemoryAccess({false},{memoryReads.incrementAndGet();error("Unapproved memories must not be read")},clock),owner,clock,http,
        notionFactory={_,_->LegacyReadExchange{op,body->notionCalls.add(op to body);notionReply(op,body)}})
}
internal class ConnectedHostApplication:VoiceBrainApplication(),ConnectedWorkspaceHost {
    override val automaticStartupAllowed=false
    lateinit var fixture:ConnectedFixture
    override fun onCreate(){super.onCreate();fixture=ConnectedFixture(this)}
    override fun connectedWorkspace(current:()->Boolean)=fixture.workspace(current)
    override fun connectedSettings()=fixture.settings
}
