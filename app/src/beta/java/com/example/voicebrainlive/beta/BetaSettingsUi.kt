package com.example.voicebrainlive.beta

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.lifecycle.*
import com.example.voicebrainlive.*
import com.example.voicebrainlive.memory.confirmed.*
import com.example.voicebrainlive.updates.AppUpdateActivity

/** Short BETA-only settings; technical controls are deliberately collapsed. */
object BetaSettingsUi {
    fun install(a:SettingsActivity){
        val body=BetaUi.page(a,BetaWords.get(a,"settings"))
        fun word(k:String)=BetaWords.get(a,k)
        fun label(value:String,tag:String)=BetaUi.text(a,value,13f).apply{this.tag=tag}.also{body.addView(it)}
        fun section(key:String){body.addView(BetaUi.text(a,word(key),14f).apply{setPadding(0,BetaUi.dp(a,12),0,BetaUi.dp(a,2))})}
        fun button(value:String,tag:String,action:()->Unit)=BetaUi.button(a,value,tag){if(a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))action()}.apply{
            layoutParams=LinearLayout.LayoutParams(-1,-2);gravity=android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            val v=android.util.TypedValue();a.theme.resolveAttribute(android.R.attr.selectableItemBackground,v,true);setBackgroundResource(v.resourceId)
        }.also{body.addView(it)}
        fun open(type:Class<*>)=a.startActivity(Intent(a,type).setPackage(a.packageName))
        val prefs=a.getSharedPreferences("beta_options",0)
        section("general")
        button(word("theme")+" · "+ThemeUtils.getTheme(a),"beta_theme"){
            val next=if(ThemeUtils.getTheme(a)=="light")"dark" else "light"
            if(a.getSharedPreferences("app_prefs",0).edit().putString("theme",next).commit()&&ThemeUtils.getTheme(a)==next)a.recreate()
            else Toast.makeText(a,"Theme could not be saved",Toast.LENGTH_LONG).show()
        }
        label(word("language"),"beta_ui_language_title")
        val uiCodes=listOf("my","en","ko")
        fun adapter(values:List<String>)=ArrayAdapter(a,android.R.layout.simple_spinner_item,values).apply{setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
        body.addView(Spinner(a).apply{
            tag="beta_ui_language";contentDescription=word("language");minimumHeight=BetaUi.dp(a,48)
            this.adapter=adapter(listOf("မြန်မာ","English","한국어"));setSelection(uiCodes.indexOf(BetaWords.language(a)).coerceAtLeast(0))
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                override fun onNothingSelected(p:AdapterView<*>?){}
                override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long){
                    val changed=BetaWords.language(a)!=uiCodes[i]
                    if(!prefs.edit().putString("ui_language",uiCodes[i]).commit())Toast.makeText(a,"Language could not be saved",Toast.LENGTH_LONG).show()
                    else if(changed)a.recreate()
                }
            }
        })
        section("ai")
        label("AI OFF / UNAVAILABLE","beta_provider_status")
        button(word("provider"),"beta_pilot"){open(BetaPilotActivity::class.java)}
        section("personal")
        button(word("memory"),"beta_link_"+R.id.btnConfirmedMemories){open(ConfirmedMemoryActivity::class.java)}.id=R.id.btnConfirmedMemories
        val context=label("","beta_memory_consent")
        fun consent(){context.text=word("memoryuse")+": "+if(ConfirmedMemoryContextConsent.isEnabled(a))"On" else "Off"}
        consent()
        var epoch=0L;var foreground=false;var dialog:AlertDialog?=null
        a.lifecycle.addObserver(object:DefaultLifecycleObserver{
            override fun onResume(owner:LifecycleOwner){epoch++;foreground=true;consent()}
            override fun onPause(owner:LifecycleOwner){epoch++;foreground=false;dialog?.dismiss();dialog=null}
        })
        // Consent review is an explicit local preference, never provider/network activation.
        button(word("memoryuse"),"beta_consent_review"){
            val lease=epoch
            dialog=AlertDialog.Builder(a).setTitle("Use confirmed memories locally?")
                .setMessage("Only records you confirmed and permitted may be used. Cloud AI remains off.\n\n"+ConfirmedMemoryContextConsent.DISCLOSURE)
                .setNegativeButton("Keep unchanged",null).setPositiveButton("Enable after review"){_,_->if(foreground&&epoch==lease){ConfirmedMemoryContextConsent.setAfterReview(a,true);consent()}}
                .show().also{it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        }
        section("app")
        button(word("about"),"beta_link_"+R.id.btnAppUpdates){open(AppUpdateActivity::class.java)}.id=R.id.btnAppUpdates
        button(word("help"),"beta_help"){open(BetaHelpActivity::class.java)}
        button(word("diagnostics"),"beta_diagnostics"){open(BetaDiagnosticsActivity::class.java)}
        val optional=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;tag="beta_optional_settings"}
        button(word("more"),"beta_settings_more"){optional.visibility=if(optional.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        body.addView(optional)
        optional.addView(BetaUi.text(a,word("voice"),14f).apply{tag="beta_language_title"})
        val codes=listOf("my-MM","ko-KR","en-US")
        optional.addView(Spinner(a).apply{
            tag="beta_language";contentDescription=word("voice");minimumHeight=BetaUi.dp(a,48)
            this.adapter=adapter(listOf("မြန်မာ","한국어","English"));setSelection(codes.indexOf(prefs.getString("speech_language","my-MM")).coerceAtLeast(0))
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                override fun onNothingSelected(p:AdapterView<*>?){}
                override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long){if(!prefs.edit().putString("speech_language",codes[i]).commit())Toast.makeText(a,"Language could not be saved",Toast.LENGTH_LONG).show()}
            }
        })
        optional.addView(BetaUi.button(a,"Memory use · Off","beta_consent_off"){ConfirmedMemoryContextConsent.setAfterReview(a,false);consent()})
        optional.addView(BetaUi.button(a,word("tasks"),"beta_link_"+R.id.btnAgentTasks){open(BetaTaskBoardActivity::class.java)}.apply{id=R.id.btnAgentTasks})
        optional.addView(BetaUi.button(a,word("notion")+" · "+word("notconnected"),"beta_notion"){open(BetaNotionActivity::class.java)})
    }
}
