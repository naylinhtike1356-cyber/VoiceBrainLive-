package com.example.voicebrainlive.memory.confirmed

import android.net.Uri
import android.os.Looper
import android.view.WindowManager
import android.widget.*
import com.example.voicebrainlive.VoiceBrainApplication
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.ByteArrayInputStream

internal class ImportUiStore:ConfirmedMemoryStore {
    var snapshot=ConfirmedMemorySnapshot();var writes=0;private var id=0
    private val repo=ConfirmedMemoryRepository(object:ConfirmedMemoryPersistence{
        override fun read()=snapshot
        override fun writeAtomically(snapshot:ConfirmedMemorySnapshot){this@ImportUiStore.snapshot=snapshot;writes++}
    },{100L},{"memory-${++id}"})
    override fun list(includeExpired:Boolean)=repo.list(includeExpired)
    override fun save(draft:MemoryDraft,confirmedExactPreview:Boolean)=repo.save(draft,confirmedExactPreview)
    override fun correct(id:String,revision:Long,draft:MemoryDraft,confirmedExactPreview:Boolean)=repo.correct(id,revision,draft,confirmedExactPreview)
    override fun forget(id:String,revision:Long,confirmedExactPreview:Boolean)=repo.forget(id,revision,confirmedExactPreview)
    override fun importConfirmed(drafts:List<MemoryDraft>,expected:List<ConfirmedMemory>,confirmedExactPreview:Boolean)=repo.importConfirmed(drafts,expected,confirmedExactPreview)
}
internal class ImportUiApplication:VoiceBrainApplication(){override val automaticStartupAllowed=false;override val confirmedMemoryStore=ImportUiStore()}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ImportUiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MemoryJsonImportActivityTest {
    private val app get()=RuntimeEnvironment.getApplication() as ImportUiApplication
    private suspend fun until(done:()->Boolean){withTimeout(20_000){while(!done()){Shadows.shadowOf(Looper.getMainLooper()).idle();delay(10)}}}
    private fun load(a:MemoryJsonImportActivity,json:String){
        val uri=Uri.parse("content://host-fixture/memory.json")
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(json.toByteArray()))
        a.javaClass.getDeclaredMethod("loadFile",Uri::class.java).apply{isAccessible=true}.invoke(a,uri)
    }
    @Test fun actualLocalFilePreviewRequiresConsentAndSavesConfirmedContextOff():Unit=runBlocking{
        val c=Robolectric.buildActivity(MemoryJsonImportActivity::class.java).setup();val a=c.get();val root=a.window.decorView
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        load(a,"""{"schemaVersion":1,"memories":[{"category":"GOAL","title":"Study","content":"မြန်မာ 한국어"}]}""")
        until{root.findViewWithTag<TextView>("memory_import_status").text.contains("အသစ်")}
        assertEquals(0,app.confirmedMemoryStore.writes)
        root.findViewWithTag<Button>("memory_import_save").performClick();assertEquals(0,app.confirmedMemoryStore.writes)
        root.findViewWithTag<CheckBox>("memory_import_consent").isChecked=true;root.findViewWithTag<Button>("memory_import_save").performClick()
        until{root.findViewWithTag<TextView>("memory_import_status").text.contains("ပြန်ဖတ်စစ်ပြီး")}
        val saved=app.confirmedMemoryStore.list(true).single();assertFalse(saved.draft.includeInContext);assertTrue(saved.draft.content.contains("JSON source"));assertEquals(1,app.confirmedMemoryStore.writes)
        c.pause().stop().destroy()
    }
    @Test fun malformedOrAuthorizingFileCannotSaveAnyRecord():Unit=runBlocking{
        val c=Robolectric.buildActivity(MemoryJsonImportActivity::class.java).setup();val a=c.get()
        load(a,"""{"schemaVersion":1,"approved":true,"memories":[]}""")
        until{a.window.decorView.findViewWithTag<TextView>("memory_import_status").text.contains("ခွဲဖတ်မရပါ")}
        assertTrue(app.confirmedMemoryStore.list(true).isEmpty());assertEquals(0,app.confirmedMemoryStore.writes);c.pause().stop().destroy()
    }
}
