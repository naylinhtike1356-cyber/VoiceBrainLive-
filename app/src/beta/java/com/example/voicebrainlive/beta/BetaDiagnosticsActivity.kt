package com.example.voicebrainlive.beta

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.agent.*
import com.example.voicebrainlive.agent.brain.*
import com.example.voicebrainlive.memory.confirmed.*
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*

/** Uses canonical stores with isolated synthetic records and real disk read-back, never the user's rows. */
object BetaSelfTest {
    suspend fun run(directory:File):List<String> {
        val results=mutableListOf<String>()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        val run=UUID.randomUUID().toString();directory.mkdirs()
        val taskFile=File(directory,"$run-task.enc");val memoryFile=File(directory,"$run-memory.enc")
        fun bytes(f:File)=AndroidAtomicMemoryBytes(f) {}
        fun store()=EncryptedBrainStore(bytes(taskFile)){key}
        fun memories()=ConfirmedMemoryRepository(EncryptedConfirmedMemoryPersistence(bytes(memoryFile)){key},System::currentTimeMillis){UUID.randomUUID().toString()}
        val reads=object:AgentReadPort{
            override suspend fun clock()=AgentObservation(AgentStatus.SUCCEEDED,AgentData.Clock("SELF-TEST synthetic clock"))
            override suspend fun battery()=AgentObservation(AgentStatus.SUCCEEDED,AgentData.Battery(50))
            override suspend fun tasks(connection:AgentConnectionPin)=error("not an external source")
        }
        fun agent()=BetaLocalAgent(store(),reads,BrainMemoryAccess({false},{emptyList()},System::currentTimeMillis),{true})
        try {
            val a=agent();val p=a.preview("SELF-TEST scratch task",BetaRecipe.STATUS)
            val saved=a.saveReviewed(p.definition.plan.taskId,p.definition.digest)
            check(store().list().single().definition==saved.definition)
            results+="PASS · task saved and reopened from encrypted scratch file"
            a.cancelReviewed(saved);check(store().list().single().snapshot.status==AgentStatus.CANCELLED)
            val edited=a.preview("SELF-TEST corrected task",BetaRecipe.STATUS);val next=a.saveReviewed(edited.definition.plan.taskId,edited.definition.digest)
            check(store().list().size==2 && store().list().any{it.definition.plan.objective=="SELF-TEST corrected task"})
            results+="PASS · task cancellation and edit persisted; history retained"
            check(agent().restoreForReview(next.definition.plan.taskId).snapshot.status==AgentStatus.BLOCKED)
            results+="PASS · fresh agent restores for review without execution permission"
            val draft=MemoryDraft("SELF-TEST", "Synthetic memory",ConfirmedMemoryCategory.USER_NOTE,ConfirmedMemorySource.USER_ENTERED)
            val denied=runCatching{memories().save(draft,false)}.isFailure;check(denied&&memories().list().isEmpty())
            val m=memories().save(draft,true);check(memories().list().single()==m)
            val corrected=memories().correct(m.id,m.revision,draft.copy(content="Corrected synthetic memory"),true)
            check(memories().list().single()==corrected)
            check(memories().forget(corrected.id,corrected.revision,true)&&memories().list().isEmpty())
            results+="PASS · memory consent, save, correction and deletion read back"
            check(taskFile.readBytes().toString(Charsets.UTF_8).contains("SELF-TEST").not())
            results+="PASS · task payload is not plaintext on disk"
        }catch(c:CancellationException){throw c}
        catch(_:Exception){results+="FAIL · scratch workflow could not be verified; no user record was modified"}
        finally {
            // Only this run's two generated synthetic files; never recurse or touch user stores/artifacts.
            listOf(taskFile,memoryFile).forEach{if(it.exists()&&!it.delete())results+="FAIL · scratch cleanup incomplete"}
        }
        return results
    }
}
class BetaDiagnosticsActivity:AppCompatActivity(){
    private var job:Job?=null
    private var testState="NOT RUN"
    private var visible=false
    private var epoch=0L
    private var review:android.app.AlertDialog?=null
    override fun onResume(){super.onResume();visible=true;epoch++}
    override fun onPause(){visible=false;epoch++;review?.dismiss();review=null;super.onPause()}
    override fun onCreate(state:Bundle?){ThemeUtils.applyTheme(this);super.onCreate(state)
        val p=BetaUi.page(this,"Diagnostics & About")
        p.addView(BetaUi.text(this,"${com.example.voicebrainlive.BuildConfig.VERSION_NAME} (${com.example.voicebrainlive.BuildConfig.VERSION_CODE})"))
        p.addView(BetaUi.text(this,"Local plans: at most 8 saved checkpoints including retained history. Review expires after 60 seconds. Reopen a task to inspect or cancel; replan before running. No automatic deletion."))
        p.addView(BetaUi.text(this,"Speech-to-text = voice input. Text AI = separate approved provider setup. AI voice conversation = not verified. Notion and background effects are disabled."))
        val status=BetaUi.text(this,"NOT RUN · Self-test uses temporary synthetic records, not your memories or tasks. Native Keystore and physical UI checks remain manual.").apply{tag="self_test_result"};p.addView(status)
        p.addView(BetaUi.button(this,"Run local storage self-test","beta_self_test"){
            if(job!=null)return@button
            testState="RUNNING";status.text="RUNNING · Checking isolated scratch workflows"
            job=lifecycleScope.launch{
                val rows=withContext(Dispatchers.IO){BetaSelfTest.run(File(noBackupFilesDir,"beta-self-test"))}
                testState=if(rows.any{it.startsWith("FAIL")})"FAIL" else "PASS"
                status.text=rows.joinToString("\n")+"\nManual checks still required: screen layout, microphone and permanent Keystore after restart."
                job=null
            }
        })
        p.addView(BetaUi.button(this,"Inspect saved local data","beta_store_check"){
            if(job!=null)return@button
            job=lifecycleScope.launch{
                val text=withContext(Dispatchers.IO){
                    val a=application as BetaApplication
                    val t=runCatching{a.betaStore().list().size}.fold({"PASS · $it saved task checkpoints readable"},{"FAIL · Task storage unavailable; records preserved"})
                    val m=runCatching{a.confirmedMemoryStore.list(true).size}.fold({"PASS · $it confirmed memories readable"},{"FAIL · Memory storage unavailable; records preserved"})
                    "$t\n$m\nRead-only check; this does not verify every UI action."
                }
                status.text=text;job=null
            }
        })
        p.addView(BetaUi.button(this,"Share Diagnostic Report","beta_share_report"){
            if(!visible)return@button
            val lease=epoch
            val summary=BetaDiagnosticSummary.create("Diagnostics",testState)
            review=android.app.AlertDialog.Builder(this).setTitle("Review before sharing")
                .setView(android.widget.ScrollView(this).apply{addView(BetaUi.text(this@BetaDiagnosticsActivity,summary))})
                .setNegativeButton("Cancel",null).setPositiveButton("Choose app…"){_,_->
                    if(!visible||epoch!=lease)return@setPositiveButton
                    startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,summary),"Share sanitized report"))
                }.show()
        })
        p.addView(BetaUi.button(this,"Optional simulated integrations","beta_simulations"){
            android.app.AlertDialog.Builder(this).setTitle("Simulations only")
                .setItems(arrayOf("Notion sample profiles", "Manual scheduler samples")){_,i->
                    BetaUi.open(this,if(i==0)com.example.voicebrainlive.agent.notion.NotionProfilesActivity::class.java else com.example.voicebrainlive.agent.proactive.ProactiveRulesActivity::class.java)
                }.setNegativeButton("Close",null).show()
        })
    }
}
