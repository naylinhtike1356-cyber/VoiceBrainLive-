package com.example.voicebrainlive.memory.confirmed

import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.VoiceBrainApplication
import com.example.voicebrainlive.agent.connected.ConnectedUi
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Explicit SAF choice -> local strict parse -> exact review -> single atomic confirmed-store append. */
class MemoryJsonImportActivity:AppCompatActivity(){
    private lateinit var status:TextView
    private lateinit var rows:LinearLayout
    private lateinit var consent:CheckBox
    private var baseline:List<ConfirmedMemory> = emptyList()
    private var candidates:List<ImportedMemory> = emptyList()
    private var selected=mutableSetOf<Int>()
    private var busy=false
    @Volatile private var generation=0L
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(::loadFile)}
    private val store get()=(application as VoiceBrainApplication).confirmedMemoryStore
    override fun onCreate(savedInstanceState:Bundle?){
        ConnectedUi.theme(this);super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body=ConnectedUi.page(this,"JSON ဖိုင်မှ Memory ထည့်ရန်")
        body.addView(ConnectedUi.text(this,"ဖိုင်ကို စက်ထဲမှာသာ ခွဲဖတ်မည်။ AI ခန့်မှန်းချက် မဟုတ်ပါ။ ပြထားသောအချက်များကို စစ်ပြီး အတည်ပြုမှ သိမ်းမည်။ အရင် memory များကို အစားမထိုး၊ AI session ထဲသုံးခွင့်ကိုလည်း မဖွင့်ပါ။"))
        body.addView(ConnectedUi.button(this,"JSON ဖိုင်ရွေးရန်","memory_import_choose"){if(!busy)picker.launch(arrayOf("application/json","text/plain"))})
        body.addView(ConnectedUi.button(this,"လက်ခံသည့် JSON နမူနာ","memory_import_example"){
            AlertDialog.Builder(this).setTitle("နမူနာပုံစံ — သင့်အချက်အလက် မဟုတ်ပါ")
                .setMessage("{\n  \"schemaVersion\": 1,\n  \"memories\": [\n    {\"category\": \"PREFERENCE\",\n     \"title\": \"သင်ရွေးသော ခေါင်းစဉ်\",\n     \"content\": \"သင်အတည်ပြုလိုသော အချက်\"}\n  ]\n}\n\ncategory: FACT, PREFERENCE, GOAL, ROUTINE, USER_NOTE\nအများဆုံး 1 MiB / အချက် 100။ Password/API key မထည့်ပါနှင့်။")
                .setPositiveButton("ပိတ်ရန်",null).show()
        })
        status=ConnectedUi.text(this,"ဖိုင် မရွေးရသေးပါ။").apply{tag="memory_import_status"};body.addView(status)
        rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};body.addView(rows)
        consent=CheckBox(this).apply{text="ရွေးထားသော preview အတိအကျကို စစ်ပြီး မှန်ကန်သော memory အဖြစ် သိမ်းရန် အတည်ပြုသည်။";tag="memory_import_consent"};body.addView(consent)
        body.addView(ConnectedUi.button(this,"ရွေးထားသော Memory များ သိမ်းရန်","memory_import_save"){save()})
        body.addView(ConnectedUi.button(this,"မသိမ်းဘဲ ပြန်ထွက်ရန်","memory_import_cancel"){finish()})
    }
    private fun loadFile(uri:Uri){
        if(busy)return
        generation++;val captured=generation;busy=true;consent.isChecked=false;rows.removeAllViews();candidates=emptyList();selected.clear()
        status.text="JSON ကို ခွဲဖတ်နေသည်…"
        lifecycleScope.launch {try{
            val parsed=withContext(Dispatchers.IO){
                val existing=store.list(true).toList()
                val bytes=contentResolver.openInputStream(uri)?.use {input->
                    val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){ensureActive();val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MemoryJsonImport.MAX_BYTES);out.write(buffer,0,n)}
                    out.toByteArray()
                }?:throw IllegalArgumentException("File unavailable")
                existing to MemoryJsonImport.parse(bytes,existing)
            }
            if(generation!=captured)return@launch
            baseline=parsed.first;candidates=parsed.second.candidates;render()
        }catch(c:CancellationException){throw c}catch(_:Exception){if(generation==captured)status.text="ဖိုင်ကို လုံခြုံစွာ ခွဲဖတ်မရပါ။ JSON ပုံစံ/အရွယ်အစား/secret ပါဝင်မှု သို့မဟုတ် memory သိမ်းဆည်းခွင့်ကို စစ်ပါ။ မူလ memory မပြောင်းပါ။"}
        finally{if(generation==captured)busy=false}}
    }
    private fun render(){
        consent.isChecked=false;rows.removeAllViews();selected.clear()
        val prior=baseline.map{it.draft}.toMutableList()
        candidates=candidates.map{it.copy(disposition=MemoryJsonImport.disposition(it.draft,prior)).also{row->prior.add(row.draft)}}
        status.text="အသစ် ${candidates.count{it.disposition==ImportDisposition.NEW}} · ထပ်နေ ${candidates.count{it.disposition==ImportDisposition.DUPLICATE}} · မကိုက်ညီ ${candidates.count{it.disposition==ImportDisposition.CONFLICT}}\nမကိုက်ညီသော memory ကို အလိုအလျောက် အစားမထိုးပါ။"
        candidates.forEachIndexed{i,row->
            val choose=CheckBox(this).apply{
                text="${row.draft.category} · ${row.draft.title}";isEnabled=row.disposition==ImportDisposition.NEW
                isChecked=isEnabled;if(isEnabled)selected.add(i);tag="memory_import_item_$i"
                setOnCheckedChangeListener{_,on->if(on)selected.add(i)else selected.remove(i);consent.isChecked=false}
            };rows.addView(choose)
            rows.addView(ConnectedUi.text(this,row.draft.content))
            rows.addView(ConnectedUi.button(this,"ဤအချက်ကို ပြင်ဆင်ရန်","memory_import_edit_$i"){if(!busy)edit(i)})
        }
    }
    private fun edit(index:Int){
        val old=candidates[index].draft
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val title=ConnectedUi.input(this,"ခေါင်းစဉ်","memory_import_title",160).apply{setText(old.title)};body.addView(title)
        val content=ConnectedUi.input(this,"အကြောင်းအရာ","memory_import_content",3900).apply{setText(MemoryJsonImport.editableContent(old));minLines=4};body.addView(content)
        val dialog=AlertDialog.Builder(this).setTitle("မသိမ်းရသေးသော Preview ကို ပြင်ရန်").setView(ScrollView(this).apply{addView(body)})
            .setNegativeButton("မပြင်တော့ပါ",null).setPositiveButton("Preview ပြင်ရန်",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{val d=MemoryJsonImport.edited(old,title.text.toString(),content.text.toString())
                candidates=candidates.mapIndexed{i,row->if(i==index)row.copy(draft=d)else row};dialog.dismiss();render()
            }catch(_:Exception){content.error="ခေါင်းစဉ်၊ အရွယ်အစားနှင့် secret ပါဝင်မှုကို စစ်ပါ"}
        }};dialog.show();ConnectedUi.protect(dialog)
    }
    private fun save(){
        if(busy)return
        if(!consent.isChecked){consent.error="အပြည့်အစုံဖတ်ပြီး အရင်အတည်ပြုပါ";return}
        val drafts=selected.sorted().map{candidates[it]}.filter{it.disposition==ImportDisposition.NEW}.map{it.draft.copy()}
        if(drafts.isEmpty()){status.text="သိမ်းမည့် memory ကို ရွေးပါ။";return}
        val expected=baseline.toList();val captured=generation;busy=true
        lifecycleScope.launch{try{
            val saved=withContext(Dispatchers.IO){drafts.forEach{MemoryJsonImport.validate(it)};ensureActive();check(generation==captured);store.importConfirmed(drafts,expected,true)}
            if(generation==captured){candidates=emptyList();selected.clear();rows.removeAllViews();consent.isChecked=false;status.text="Memory ${saved.size} ခုကို သိမ်းပြီး ပြန်ဖတ်စစ်ပြီးပါပြီ။ AI သို့ အသုံးပြုခွင့် မဖွင့်ထားပါ။"}
        }catch(c:CancellationException){throw c}catch(_:Exception){if(generation==captured){consent.isChecked=false;status.text="သိမ်းပြီးကြောင်း မအတည်ပြုနိုင်ပါ။ Memory ပြန်ဖတ်ပြီးမှ ထပ်စမ်းပါ။ Changed/conflicting records ကို အလိုအလျောက် မအစားထိုးပါ။"}}
        finally{if(generation==captured)busy=false}}
    }
    override fun onStop(){generation++;busy=false;consent.isChecked=false;super.onStop()}
}
