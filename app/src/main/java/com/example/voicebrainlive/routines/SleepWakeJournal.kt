package com.example.voicebrainlive.routines

import java.io.*
import java.util.Base64
import java.util.UUID

internal enum class SleepWakeKind { MEDIA_STOP, WAKE }
internal enum class SleepWakeState { SCHEDULED, RUNNING, REQUESTED, DISMISSED, CANCELLED, BLOCKED, EXPIRED }
internal data class SleepWakeNow(val wall:Long,val elapsed:Long,val boot:Int)
internal data class SleepWakeEntry(val id:String,val kind:SleepWakeKind,val due:Long,val elapsedDue:Long,
    val boot:Int,val state:SleepWakeState,val clockRequested:Boolean=false)
internal interface SleepWakePersistence { fun read():String?; fun write(value:String):Boolean }
internal class SleepWakeFailure(val code:String):IllegalStateException(code)
internal fun swCheck(ok:Boolean,code:String="INVALID_ALARM"){if(!ok)throw SleepWakeFailure(code)}

/** Closed local alarm metadata only, in the existing encrypted vault. No agent payload or grants.
 * This does not alter the daily Routine schema or the existing WorkManager task engine.
 */
internal class SleepWakeJournal(private val disk:SleepWakePersistence,private val clock:()->SleepWakeNow,
    private val ids:()->String={UUID.randomUUID().toString()}) {
    @Synchronized fun history():List<SleepWakeEntry> = load()
    @Synchronized fun add(kind:SleepWakeKind,due:Long,elapsedDue:Long):Pair<SleepWakeEntry,Boolean> {
        val now=clock();swCheck(due>now.wall&&due-now.wall<=86_400_000L)
        if(kind==SleepWakeKind.MEDIA_STOP)swCheck(elapsedDue>now.elapsed&&elapsedDue-now.elapsed<=10_800_000L)
        val old=load()
        // Repeated model callbacks for the same deadline cannot restart music or reset the timer.
        old.firstOrNull{it.kind==kind&&it.state==SleepWakeState.SCHEDULED&&kotlin.math.abs(it.due-due)<15_000L}
            ?.let{return it to false}
        val kept=old.filter{it.state in setOf(SleepWakeState.SCHEDULED,SleepWakeState.RUNNING)}+old.filter{it.state !in setOf(SleepWakeState.SCHEDULED,SleepWakeState.RUNNING)}.takeLast(10)
        swCheck(kept.size<30,"ALARM_LIMIT")
        val e=SleepWakeEntry(ids(),kind,due,elapsedDue,now.boot,SleepWakeState.SCHEDULED)
        val rows=if(kind==SleepWakeKind.MEDIA_STOP)kept.map{if(it.kind==kind&&it.state==SleepWakeState.SCHEDULED)it.copy(state=SleepWakeState.CANCELLED)else it} else kept
        save(rows+e);return e to true
    }
    @Synchronized fun claim(id:String):SleepWakeEntry? {
        val rows=load();val e=rows.singleOrNull{it.id==id}?:return null
        if(e.state!=SleepWakeState.SCHEDULED)return null
        val now=clock()
        val age=if(e.kind==SleepWakeKind.MEDIA_STOP){
            if(e.boot!=now.boot){replace(rows,e,e.copy(state=SleepWakeState.EXPIRED));return null}
            now.elapsed-e.elapsedDue
        }else now.wall-e.due
        if(age<0)return null
        if(age>600_000){replace(rows,e,e.copy(state=SleepWakeState.EXPIRED));return null}
        return replace(rows,e,e.copy(state=SleepWakeState.RUNNING))
    }
    @Synchronized fun mark(id:String,state:SleepWakeState):SleepWakeEntry? {
        val rows=load();val e=rows.singleOrNull{it.id==id}?:return null
        if(e.state in setOf(SleepWakeState.DISMISSED,SleepWakeState.CANCELLED,SleepWakeState.EXPIRED))return e
        swCheck(state in setOf(SleepWakeState.REQUESTED,SleepWakeState.DISMISSED,SleepWakeState.CANCELLED,SleepWakeState.BLOCKED,SleepWakeState.EXPIRED))
        return replace(rows,e,e.copy(state=state))
    }
    @Synchronized fun clockRequested(id:String){val rows=load();val e=rows.single{it.id==id};replace(rows,e,e.copy(clockRequested=true))}
    private fun replace(rows:List<SleepWakeEntry>,old:SleepWakeEntry,next:SleepWakeEntry):SleepWakeEntry{save(rows.map{if(it.id==old.id)next else it});return next}
    private fun load():List<SleepWakeEntry> = try{decode(disk.read())}catch(e:SleepWakeFailure){throw e}catch(_:Exception){throw SleepWakeFailure("SECURE_STORAGE_UNAVAILABLE")}
    private fun save(rows:List<SleepWakeEntry>){
        val text=encode(rows)
        try{swCheck(disk.write(text)&&disk.read()==text,"STORAGE_WRITE_UNVERIFIED")}
        catch(e:SleepWakeFailure){throw e}catch(_:Exception){throw SleepWakeFailure("SECURE_STORAGE_UNAVAILABLE")}
    }
    companion object {
        fun encode(rows:List<SleepWakeEntry>):String {
            validate(rows);val b=ByteArrayOutputStream()
            DataOutputStream(b).use{o->o.writeInt(1);o.writeInt(rows.size);rows.forEach{e->o.writeUTF(e.id);o.writeUTF(e.kind.name);o.writeLong(e.due);o.writeLong(e.elapsedDue);o.writeInt(e.boot);o.writeUTF(e.state.name);o.writeBoolean(e.clockRequested)}}
            return Base64.getEncoder().encodeToString(b.toByteArray())
        }
        fun decode(value:String?):List<SleepWakeEntry> {
            if(value==null)return emptyList()
            try{
                swCheck(value.length<=20_000,"DATA_UNREADABLE")
                return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use{i->
                    swCheck(i.readInt()==1,"UNSUPPORTED_SCHEMA");val n=i.readInt();swCheck(n in 0..30,"DATA_UNREADABLE")
                    val rows=List(n){SleepWakeEntry(i.readUTF(),SleepWakeKind.valueOf(i.readUTF()),i.readLong(),i.readLong(),i.readInt(),SleepWakeState.valueOf(i.readUTF()),i.readBoolean())}
                    swCheck(i.available()==0,"DATA_UNREADABLE");validate(rows);rows
                }
            }catch(e:SleepWakeFailure){throw e}catch(_:Exception){throw SleepWakeFailure("DATA_UNREADABLE")}
        }
        private fun validate(rows:List<SleepWakeEntry>){
            swCheck(rows.size<=30&&rows.map{it.id}.toSet().size==rows.size)
            rows.forEach{swCheck(it.id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))&&it.due>0&&it.elapsedDue>=0&&it.boot>=0)}
        }
    }
}
