package com.example.voicebrainlive.agent.connected

import com.example.voicebrainlive.agent.notion.ReadJson
import com.example.voicebrainlive.beta.BetaGeminiTextExchange
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

internal enum class LegacyReadOperation { SCHEMA, QUERY }
internal class LegacyHttpReply(val status:Int,val bytes:ByteArray,val contentType:String="application/json",val retryAfterSeconds:Long?=null) {
    override fun toString()="LegacyHttpReply(status=$status,content=<redacted>)"
}
internal fun interface LegacyReadExchange { suspend fun read(operation:LegacyReadOperation,body:String?):LegacyHttpReply }

/** Existing database API compatibility only. No page-write/delete/archive/search-workspace method exists. */
internal class NotionReadOnlyHttp(private val credential:ConnectionCredential,private val current:()->Boolean,
    private val calls:Call.Factory=readClient(),private val propertyIds:Set<String> = emptySet()):LegacyReadExchange {
    private val count=AtomicInteger()
    private fun guard(){connectionCheck(current()&&credential.isCurrent(),"NOTION_STALE_CONNECTION")}
    override suspend fun read(operation:LegacyReadOperation,body:String?):LegacyHttpReply {
        currentCoroutineContext().ensureActive();guard()
        connectionCheck(count.incrementAndGet()<=4,"NOTION_CALL_BUDGET")
        val db=notionUuid(credential.target)
        connectionCheck(propertyIds.size<=3&&propertyIds.all{id->id.length in 1..100&&id.none{it.isISOControl()}},"NOTION_INVALID_READ")
        val url=("https://api.notion.com/v1/databases/$db"+if(operation==LegacyReadOperation.QUERY)"/query" else "").toHttpUrl().newBuilder().apply{
            if(operation==LegacyReadOperation.QUERY)propertyIds.sorted().forEach{addQueryParameter("filter_properties",it)}
        }.build()
        val builder=Request.Builder().url(url)
            .header("Authorization","Bearer "+credential.requireSecret()).header("Notion-Version","2022-06-28").header("Accept","application/json")
        if(operation==LegacyReadOperation.SCHEMA){connectionCheck(body==null,"NOTION_INVALID_READ");builder.get()}
        else {
            connectionCheck(body!=null&&body.toByteArray(Charsets.UTF_8).size<=8192,"NOTION_INVALID_READ")
            val data=requireNotNull(body).toByteArray(Charsets.UTF_8)
            builder.post(object:RequestBody(){override fun contentType()="application/json; charset=utf-8".toMediaType()
                override fun contentLength()=data.size.toLong();override fun isOneShot()=true
                override fun writeTo(sink:BufferedSink){sink.write(data)}})
        }
        val call=try{calls.newCall(builder.build())}catch(_:Exception){throw ConnectionFailure("NOTION_NETWORK_FAILURE")}
        return suspendCancellableCoroutine{c->
            val delivered=AtomicBoolean()
            fun fail(code:String){if(delivered.compareAndSet(false,true)&&c.isActive)c.resumeWithException(ConnectionFailure(code))}
            c.invokeOnCancellation{call.cancel()}
            try{
                guard();if(!c.isActive){call.cancel();return@suspendCancellableCoroutine}
                call.enqueue(object:Callback{
                    override fun onFailure(call:Call,e:IOException){fail("NOTION_NETWORK_FAILURE")}
                    override fun onResponse(call:Call,response:Response){response.use{r->
                        if(!c.isActive||delivered.get())return
                        try{
                            guard()
                            val retry=r.header("Retry-After")?.toLongOrNull()?.takeIf{it in 0..86_400}
                            val type=r.body?.contentType()?.toString().orEmpty()
                            val bytes=if(r.code!=200)ByteArray(0)else{
                                connectionCheck(type.substringBefore(';').equals("application/json",true),"NOTION_INVALID_RESPONSE")
                                val b=r.body?:throw ConnectionFailure("NOTION_INVALID_RESPONSE")
                                connectionCheck(b.contentLength()<=1_000_000,"NOTION_RESPONSE_BUDGET")
                                val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                                b.byteStream().use{input->while(true){
                                    guard();if(!c.isActive){call.cancel();return}
                                    val n=input.read(buffer,0,minOf(buffer.size,1_000_001-out.size()))
                                    if(n<0)break
                                    out.write(buffer,0,n);connectionCheck(out.size()<=1_000_000,"NOTION_RESPONSE_BUDGET")
                                }};out.toByteArray()
                            }
                            guard()
                            if(delivered.compareAndSet(false,true)&&c.isActive)c.resume(LegacyHttpReply(r.code,bytes,type,retry))
                        }catch(e:ConnectionFailure){fail(e.code)}catch(_:Exception){fail("NOTION_INVALID_RESPONSE")}
                    }}
                })
            }catch(e:ConnectionFailure){call.cancel();fail(e.code)}catch(_:Exception){call.cancel();fail("NOTION_NETWORK_FAILURE")}
        }
    }
    companion object {
        /** OkHttp4.12 may follow 503 Retry-After:0 even with connection retries disabled.
         * Preserve the HTTP status/body, but disallow that automatic GET follow-up locally.
         */
        internal fun readClient():OkHttpClient=BetaGeminiTextExchange.lockedClient().newBuilder()
            .addNetworkInterceptor{chain->
                val r=chain.proceed(chain.request())
                if(r.code==503&&r.header("Retry-After")?.trim()?.toLongOrNull()==0L)
                    r.newBuilder().header("Retry-After","86400").build() else r
            }.build()
    }
}

internal data class LegacyNotionProperty(val id:String,val name:String,val type:String,val options:List<String> = emptyList())
internal class LegacyNotionSchema(val databaseId:String,val fields:List<LegacyNotionProperty>) {
    val title:LegacyNotionProperty get()=fields.singleOrNull{it.type=="title"}?:throw ConnectionFailure("NOTION_TITLE_SCHEMA_REQUIRED")
    fun validate(mapping:LegacyTaskMapping):LegacyTaskMapping {
        connectionCheck(mapping.databaseId==databaseId&&mapping.titleId==title.id,"NOTION_SCHEMA_CHANGED")
        val status=fields.singleOrNull{it.id==mapping.businessStatusId}?:throw ConnectionFailure("NOTION_SCHEMA_CHANGED")
        connectionCheck(status.type in setOf("status","select")&&mapping.unfinished.size in 1..10&&status.options.containsAll(mapping.unfinished),"BUSINESS_STATUS_MAPPING_REQUIRED")
        val sync=setOf("pending","synced","failed")
        connectionCheck(!(status.options.any{it.equals("synced",true)}&&status.options.all{it.lowercase(java.util.Locale.ROOT) in sync}),"SYNC_STATUS_IS_NOT_TASK_COMPLETION")
        if(mapping.priorityId!=null)connectionCheck(fields.any{it.id==mapping.priorityId&&it.type=="number"},"NOTION_SCHEMA_CHANGED")
        return mapping.copy(unfinished=java.util.Collections.unmodifiableSet(LinkedHashSet(mapping.unfinished)))
    }
    override fun toString()="LegacyNotionSchema(content=<redacted>)"
}
internal class LegacyNotionRecord(val pageId:String,val title:String,val content:String,val rawStatus:String?,val unfinished:Boolean?,val priority:Int,val truncated:Boolean) {
    override fun toString()="LegacyNotionRecord(content=<redacted>)"
}
internal class LegacyNotionSnapshot(val schema:LegacyNotionSchema,val records:List<LegacyNotionRecord>,val complete:Boolean,
    val query:String,val mapping:LegacyTaskMapping?,val evidence:String) {
    override fun toString()="LegacyNotionSnapshot(count=${records.size},complete=$complete,content=<redacted>)"
}

/** Adapter for the user's already configured legacy database; never marks synthetic vault pins as real. */
internal class LegacyNotionReader(private val database:String,private val exchange:LegacyReadExchange,
    private val current:()->Boolean={true}) {
    private fun guard(){connectionCheck(current(),"NOTION_STALE_CONNECTION")}
    private suspend fun request(op:LegacyReadOperation,body:String?):Map<String,Any?> {
        guard()
        val reply=try{withTimeout(30_000){exchange.read(op,body)}}catch(c:CancellationException){throw c}
            catch(e:ConnectionFailure){throw e}catch(_:Exception){throw ConnectionFailure("NOTION_NETWORK_FAILURE")}
        try{
            currentCoroutineContext().ensureActive();guard()
            when(reply.status){
                200->Unit
                401->throw ConnectionFailure("NOTION_AUTHENTICATION")
                403,404->throw ConnectionFailure("NOTION_PERMISSION_OR_DATABASE_UNAVAILABLE")
                429,529->throw ConnectionFailure("NOTION_RATE_LIMITED",reply.retryAfterSeconds)
                400->throw ConnectionFailure("NOTION_SCHEMA_OR_LEGACY_API_UNSUPPORTED")
                else->throw ConnectionFailure("NOTION_PROVIDER_UNAVAILABLE")
            }
            connectionCheck(reply.contentType.substringBefore(';').equals("application/json",true)&&reply.bytes.size in 1..1_000_000,"NOTION_INVALID_RESPONSE")
            return ReadJson.objectValue(ReadJson.decode(reply.bytes))
        }catch(e:ConnectionFailure){throw e}catch(_:Exception){throw ConnectionFailure("NOTION_INVALID_RESPONSE")}
        finally{reply.bytes.fill(0)}
    }
    suspend fun schema():LegacyNotionSchema {
        val raw=request(LegacyReadOperation.SCHEMA,null)
        try{
            connectionCheck(raw["object"]=="database"&&notionUuid(ReadJson.string(raw["id"]))==database&&raw["archived"]!=true&&raw["in_trash"]!=true,"NOTION_WRONG_DATABASE")
            val fields=ReadJson.objectValue(raw["properties"]).map{(name,value)->
                val p=ReadJson.objectValue(value);val id=ReadJson.string(p["id"]);val type=ReadJson.string(p["type"])
                connectionCheck(id.isNotBlank()&&id.length<=100&&name.length<=200,"NOTION_INVALID_RESPONSE")
                val options=if(type in setOf("status","select"))ReadJson.array(ReadJson.objectValue(p[type])["options"]).map{
                    ReadJson.string(ReadJson.objectValue(it)["name"]).also{s->connectionCheck(s.isNotBlank()&&s.length<=80,"NOTION_INVALID_RESPONSE")}
                }else emptyList()
                LegacyNotionProperty(id,name,type,options)
            }
            connectionCheck(fields.map{it.id}.toSet().size==fields.size,"NOTION_INVALID_RESPONSE")
            return LegacyNotionSchema(database,fields).also{it.title;guard()}
        }catch(e:ConnectionFailure){throw e}catch(_:Exception){throw ConnectionFailure("NOTION_INVALID_RESPONSE")}
    }
    suspend fun read(schema:LegacyNotionSchema,mapping:LegacyTaskMapping?=null,query:String="",includeContent:Boolean=true):LegacyNotionSnapshot {
        guard();connectionCheck(schema.databaseId==database&&query.length<=100&&query.none{it.isISOControl()},"NOTION_INVALID_READ")
        val checked=mapping?.let(schema::validate)
        var cursor:String?=null;val cursors=mutableSetOf<String>();val ids=mutableSetOf<String>();val rows=mutableListOf<LegacyNotionRecord>()
        repeat(3){
            val filters=JSONArray()
            if(query.isNotBlank())filters.put(JSONObject().put("property",schema.title.id).put("title",JSONObject().put("contains",query)))
            if(checked!=null){
                val type=schema.fields.single{it.id==checked.businessStatusId}.type
                filters.put(JSONObject().put("or",JSONArray().apply{checked.unfinished.sorted().forEach{s->put(JSONObject().put("property",checked.businessStatusId).put(type,JSONObject().put("equals",s)))}}))
            }
            val body=JSONObject().put("page_size",25).put("sorts",JSONArray().put(JSONObject().put("timestamp","last_edited_time").put("direction","descending")))
            if(filters.length()>0)body.put("filter",JSONObject().put("and",filters))
            cursor?.let{body.put("start_cursor",it)}
            val page=request(LegacyReadOperation.QUERY,body.toString())
            try{
                connectionCheck(page["object"]=="list","NOTION_INVALID_RESPONSE")
                val result=ReadJson.array(page["results"]);connectionCheck(result.size<=25,"NOTION_RESPONSE_BUDGET")
                for(item in result){
                    val p=ReadJson.objectValue(item)
                    connectionCheck(p["object"]=="page"&&p["archived"]!=true&&p["in_trash"]!=true,"NOTION_INVALID_RESPONSE")
                    val parent=ReadJson.objectValue(p["parent"])
                    connectionCheck(parent["type"]=="database_id"&&notionUuid(ReadJson.string(parent["database_id"]))==database,"NOTION_WRONG_DATABASE")
                    val id=notionUuid(ReadJson.string(p["id"]));connectionCheck(ids.add(id),"NOTION_DUPLICATE_PAGE")
                    val properties=ReadJson.objectValue(p["properties"]).values.map(ReadJson::objectValue)
                    fun field(property:LegacyNotionProperty):Map<String,Any?> {
                        val value=properties.singleOrNull{it["id"]==property.id}?:throw ConnectionFailure("NOTION_SCHEMA_CHANGED")
                        connectionCheck(value["type"]==property.type,"NOTION_SCHEMA_CHANGED");return value
                    }
                    fun text(property:LegacyNotionProperty,limit:Int):Pair<String,Boolean>{
                        val f=field(property);val parts=ReadJson.array(f[property.type]);val all=parts.take(25).joinToString(""){ReadJson.string(ReadJson.objectValue(it)["plain_text"])}
                        return all.take(limit) to (parts.size>25||all.length>limit||f["has_more"]==true)
                    }
                    val title=text(schema.title,500)
                    if(checked!=null)connectionCheck(title.first.isNotBlank(),"NOTION_UNTITLED_TASK")
                    val content=if(includeContent)schema.fields.firstOrNull{it.name.equals("Content",true)&&it.type=="rich_text"}?.let{text(it,2000)}?: ("" to false) else ("" to false)
                    val statusField=checked?.let{m->schema.fields.single{it.id==m.businessStatusId}}
                        ?:schema.fields.firstOrNull{it.name.equals("Status",true)&&it.type in setOf("status","select")}
                    val status=statusField?.let{f->field(f)[f.type]?.let{ReadJson.string(ReadJson.objectValue(it)["name"])}}
                    val unfinished=checked?.let{status!=null&&status in it.unfinished}
                    val priority=checked?.priorityId?.let{pid->val value=field(schema.fields.single{it.id==pid})["number"]
                        if(value==null)0 else (value as? BigDecimal)?.intValueExact()?.also{n->connectionCheck(n in 0..100,"NOTION_PRIORITY_INVALID")}
                            ?:throw ConnectionFailure("NOTION_PRIORITY_INVALID") }?:0
                    if(checked==null||unfinished==true)rows+=LegacyNotionRecord(id,title.first,content.first,status,unfinished,priority,title.second||content.second)
                }
                val more=page["has_more"] as? Boolean?:throw ConnectionFailure("NOTION_INVALID_RESPONSE")
                connectionCheck(page.containsKey("next_cursor"),"NOTION_PAGINATION_INVALID")
                val next=page["next_cursor"]?.let(ReadJson::string)
                connectionCheck(if(more)next!=null&&next.length in 1..1024 else next==null,"NOTION_PAGINATION_INVALID")
                guard()
                if(!more)return LegacyNotionSnapshot(schema,rows.toList(),true,query,checked,if(exchange is NotionReadOnlyHttp)"NOTION_HTTP_READ" else "HOST_FIXTURE")
                connectionCheck(cursors.add(requireNotNull(next)),"NOTION_PAGINATION_INVALID");cursor=next
            }catch(e:ConnectionFailure){throw e}catch(_:Exception){throw ConnectionFailure("NOTION_INVALID_RESPONSE")}
        }
        return LegacyNotionSnapshot(schema,rows.toList(),false,query,checked,if(exchange is NotionReadOnlyHttp)"NOTION_HTTP_READ" else "HOST_FIXTURE")
    }
}
