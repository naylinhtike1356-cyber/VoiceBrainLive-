package com.example.voicebrainlive.beta

import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import java.util.UUID

/** Shared user-written todos in the existing encrypted v1 store. This is NOT an agent tool/executor.
 * A dormant read-only step preserves the existing schema; it is never executed by this adapter.
 * User-marked completion is explicit metadata with zero fabricated tool calls/results.
 */
class BetaLocalTasks(private val store:EncryptedBrainStore, private val now:()->Long=System::currentTimeMillis,
    private val owner:()->Boolean={true}) {
    companion object {
        fun isLocal(t:BrainTask)=LocalUserTaskRecord.matches(t)
        fun title(t:BrainTask)=t.definition.plan.objective.substringBefore('\n')
        fun notes(t:BrainTask)=t.definition.plan.objective.substringAfter('\n',"")
        fun visible(tasks:List<BrainTask>):List<BrainTask>{
            val replaced=tasks.filter(::isLocal).mapNotNull{it.definition.plan.parentTaskId}.toSet()
            return tasks.filter{it.definition.plan.taskId !in replaced}.sortedByDescending{it.definition.plan.createdAtMillis}
        }
        fun result(t:BrainTask)=if(!isLocal(t))BetaLocalAgent.describe(t) else when(t.snapshot.status){
            AgentStatus.REPORTED->"You marked this task done. No automated action or tool ran."
            AgentStatus.CANCELLED->"You cancelled this task. Its record is retained."
            else->"Saved on this BETA. Not completed. No automated action has run."
        }
    }
    private fun checkOwner(){if(!owner())throw BrainFailure("STALE_BETA_VIEW")}
    private suspend fun draft(title:String,notes:String,parent:String?=null):BrainTask{
        checkOwner()
        require(title.isNotBlank()&&title.length<=160&&!title.contains('\n')&&notes.length<=1000){"INVALID_TASK_TEXT"}
        val at=now();val id="todo_"+UUID.randomUUID().toString()
        val model=BrainGoalModel{_,_->BrainInterpretation.Candidate(listOf(BrainProposedStep("local","get_current_time",expected=BrainOutput.CLOCK)))}
        val memory=BrainMemoryAccess({false},{emptyList()},{at}).capture("")
        val generated=BrainPlanner(model,{at}).plan(BrainGoalRequest(id,title.trim()+if(notes.isBlank())"" else "\n"+notes,"beta_user_tasks"),memory)
        val p=generated.plan.copy(parentTaskId=parent,steps=generated.plan.steps.map{it.copy(command=it.command.copy(source="beta_user_task"))})
        val definition=generated.copy(plan=p)
        return BrainTask(1,"beta_user_tasks",definition,AgentTaskSnapshot(p.taskId,p.parentTaskId,p.objective,AgentStatus.DRAFT,at,at,p.deadlineMillis,p.idempotencyKey,0,emptyList()),reason="USER_CREATED_TASK")
    }
    suspend fun create(title:String,notes:String=""):BrainTask{val t=draft(title,notes);checkOwner();return store.create(t)}
    suspend fun edit(expected:BrainTask,title:String,notes:String=""):BrainTask{
        require(isLocal(expected)&&expected.snapshot.status==AgentStatus.DRAFT){"LOCAL_DRAFT_REQUIRED"}
        val next=draft(title,notes,expected.definition.plan.taskId);checkOwner()
        return store.reviseLocalTask(expected,next,next.definition.plan.createdAtMillis)
    }
    fun mark(expected:BrainTask,done:Boolean):BrainTask{
        checkOwner();require(isLocal(expected)&&expected.snapshot.status==AgentStatus.DRAFT){"LOCAL_DRAFT_REQUIRED"}
        return store.update(expected.definition.plan.taskId,expected.revision,expected.ownerSession){current->
            checkOwner();require(current==expected&&isLocal(current)){"CHANGED_LOCAL_TASK"}
            current.copy(revision=current.revision+1,reason=if(done)"USER_MARKED_DONE" else "USER_CANCELLED_TASK",
                snapshot=current.snapshot.copy(status=if(done)AgentStatus.REPORTED else AgentStatus.CANCELLED,updatedAtMillis=maxOf(now(),current.snapshot.updatedAtMillis)))
        }
    }
}
