package com.example.voicebrainlive.memory.confirmed

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal enum class ImportDisposition { NEW, DUPLICATE, CONFLICT }
internal data class ImportedMemory(val draft:MemoryDraft,val disposition:ImportDisposition) {
    override fun toString()="ImportedMemory(content=<redacted>)"
}
internal data class MemoryImportPreview(val sourceHash:String,val candidates:List<ImportedMemory>) {
    override fun toString()="MemoryImportPreview(content=<redacted>)"
}

/** Strict local JSON, not model inference. File fields cannot confirm facts, share context, or grant tools. */
internal object MemoryJsonImport {
    const val MAX_BYTES=1_048_576
    private val secrets=Regex("(?i)\\b(?:password|passcode|api[_ -]?key|access[_ -]?token|refresh[_ -]?token)\\b|AIza[\\w-]{20,}|sk-(?:proj-)?[\\w-]{16,}|လျှို့ဝှက်နံပါတ်|비밀번호")
    private val footer=Regex("\\n\\n\\[JSON source [a-f0-9]{64}; item [0-9]{1,3}\\]$")
    private fun plain(s:String)=footer.replace(s,"")
    fun editableContent(d:MemoryDraft)=plain(d.content)
    fun edited(d:MemoryDraft,title:String,content:String):MemoryDraft {
        val provenance=footer.find(d.content)?.value?:throw IllegalArgumentException("Import provenance missing")
        return d.copy(title=title,content=content+provenance).also(::validate)
    }
    private fun titleKey(s:String)=Normalizer.normalize(s,Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)
    fun disposition(d:MemoryDraft,existing:List<MemoryDraft>):ImportDisposition {
        val matches=existing.filter{it.category==d.category&&titleKey(it.title)==titleKey(d.title)}
        return when {matches.isEmpty()->ImportDisposition.NEW;matches.all{plain(it.content)==plain(d.content)&&it.privacy==d.privacy}->ImportDisposition.DUPLICATE;else->ImportDisposition.CONFLICT}
    }
    fun validate(d:MemoryDraft){
        require(d.title.isNotBlank()&&d.title.length<=160&&d.content.isNotBlank()&&d.content.length<=4096)
        require(d.privacy!=MemoryPrivacy.SECRET&&!d.includeInContext&&d.source==ConfirmedMemorySource.USER_CONFIRMED_SUGGESTION)
        require(!secrets.containsMatchIn(d.title+" "+d.content)) { "Secret-like content must not be imported as ordinary memory" }
    }
    fun parse(bytes:ByteArray,existing:List<ConfirmedMemory>):MemoryImportPreview {
        require(bytes.size in 1..MAX_BYTES)
        val json=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val drafts=mutableListOf<MemoryDraft>()
        JsonReader(StringReader(json)).use { r ->
            r.isLenient=false;val seen=mutableSetOf<String>();r.beginObject()
            while(r.hasNext()){
                val name=r.nextName();require(seen.add(name))
                when(name){
                    "schemaVersion"->{require(r.peek()==JsonToken.NUMBER&&r.nextString()=="1")}
                    "memories"->{r.beginArray();while(r.hasNext()){
                        require(drafts.size<100);val fields=mutableMapOf<String,String>();r.beginObject()
                        while(r.hasNext()){
                            val field=r.nextName();require(field in setOf("title","content","category","privacy")&&field !in fields&&r.peek()==JsonToken.STRING)
                            fields[field]=r.nextString()
                        };r.endObject();require(fields.keys.containsAll(setOf("title","content","category")))
                        val content=fields.getValue("content");require(content.length<=3900)
                        val d=MemoryDraft(fields.getValue("title"),content+"\n\n[JSON source $hash; item ${drafts.size+1}]",
                            ConfirmedMemoryCategory.valueOf(fields.getValue("category")),ConfirmedMemorySource.USER_CONFIRMED_SUGGESTION,
                            fields["privacy"]?.let{MemoryPrivacy.valueOf(it)}?:MemoryPrivacy.ORDINARY,includeInContext=false)
                        validate(d);drafts.add(d)
                    };r.endArray()}
                    else->throw IllegalArgumentException("Unsupported memory import field")
                }
            };r.endObject();require(seen==setOf("schemaVersion","memories")&&r.peek()==JsonToken.END_DOCUMENT&&drafts.isNotEmpty())
        }
        val reviewed=existing.map{it.draft}.toMutableList()
        return MemoryImportPreview(hash,drafts.map{d->ImportedMemory(d,disposition(d,reviewed)).also{reviewed.add(d)}})
    }
}
