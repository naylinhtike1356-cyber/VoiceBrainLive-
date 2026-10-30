package com.example.voicebrainlive.beta

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.security.SecureSettingsStorage
import kotlinx.coroutines.*

/** Only these BETA-owned fields are touched. Encrypted factory throws; never fallback to plain preferences. */
class BetaPilotSettings(private val encrypted:()->SharedPreferences) {
    fun configured():Boolean {val p=encrypted();return validModel(p.getString("beta_pilot_model","").orEmpty())&&validKey(p.getString("beta_pilot_key","").orEmpty())&&p.getBoolean("beta_pilot_setup_consent",false)}
    fun save(model:String,key:String,reviewed:Boolean):Boolean {
        require(reviewed&&validModel(model)&&validKey(key))
        val p=encrypted()
        return p.edit().putString("beta_pilot_model",model).putString("beta_pilot_key",key)
            .putBoolean("beta_pilot_setup_consent",true).commit() &&
            p.getString("beta_pilot_model",null)==model && p.getString("beta_pilot_key",null)==key && configured()
    }
    companion object {
        fun validModel(s:String)=s.matches(Regex("[A-Za-z0-9._-]{1,80}"))
        fun validKey(s:String)=BetaTextProtocol.validKey(s)
    }
}

class BetaPilotActivity:AppCompatActivity(){
    private var reviewDialog: AlertDialog? = null
    private val reviewInputs = mutableListOf<EditText>()
    override fun onCreate(state:Bundle?){ThemeUtils.applyTheme(this);super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val p=BetaUi.page(this,"AI Provider")
        p.addView(BetaUi.text(this,"OFF · Not connected").apply{tag="pilot_status"})
        p.addView(BetaUi.text(this,"Gemini text pilot · preparation only. Local tasks and memory work without a provider."))
        p.addView(Switch(this).apply {text="Cloud AI disabled";isChecked=false;isEnabled=false;tag="pilot_enable";contentDescription="Cloud AI unavailable until a separately approved pilot"})
        p.addView(BetaUi.button(this,"Review a text request · offline","pilot_review_request"){reviewRequest()})
        p.addView(BetaUi.button(this,"Setup and privacy details","pilot_requirements"){
            AlertDialog.Builder(this).setTitle("Before a live pilot")
                .setMessage("Cloud AI is OFF. This APK has no Internet permission and its provider factory has no active transport. No key is requested here.\n\nA later approved test needs your supported Gemini project/model/quota, one reviewed non-sensitive text request, and a maximum spend limit. API charges and provider retention terms may apply. No audio, private memories or task history should be sent.\n\nThe prepared restricted HTTPS adapter must be separately approved and connected before use. Never share a key in chat or screenshots. Public distribution should prefer a trusted backend with server-side keys. ChatGPT Plus is not API access.")
                .setPositiveButton("Close",null).show().window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        })
        p.addView(BetaUi.text(this,"Voice input uses your device's speech service. AI text and live AI voice are separate capabilities. No connection has been tested."))
    }

    private fun reviewRequest() {
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun input(label:String,tagName:String,max:Int)=EditText(this).apply{
            hint=label;tag=tagName;contentDescription=label
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters=arrayOf(android.text.InputFilter.LengthFilter(max))
            isSaveEnabled=false;isSaveFromParentEnabled=false
            importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setPadding(BetaUi.dp(context,16),BetaUi.dp(context,12),BetaUi.dp(context,16),BetaUi.dp(context,12))
            reviewInputs.add(this);form.addView(this)
        }
        val model=input("Model ID from your Gemini project","pilot_review_model",80)
        val goal=input("One non-sensitive text request","pilot_review_goal",2000)
        form.addView(BetaUi.text(this,"Offline preview only. No key, memory, task history or audio is included. Nothing is sent or saved."))
        reviewDialog=AlertDialog.Builder(this).setTitle("Review before a future AI test")
            .setView(ScrollView(this).apply{addView(form)})
            .setNegativeButton("Cancel",null).setPositiveButton("Preview",null).create().also{dialog->
                dialog.setOnDismissListener{reviewInputs.forEach{it.text.clear()};reviewInputs.clear()}
                dialog.show();dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if(!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || reviewDialog!==dialog || !dialog.isShowing)return@setOnClickListener
                    val m=model.text.toString().trim();val g=goal.text.toString().trim()
                    if(!BetaPilotSettings.validModel(m)){model.error="Enter a valid model ID; availability is not checked here";return@setOnClickListener}
                    if(g.isBlank()){goal.error="Enter a non-sensitive request";return@setOnClickListener}
                    val request=BetaTextProtocol.request(m,g)
                    // Clear before opening another window; do not depend on a deferred dismiss callback.
                    reviewInputs.forEach{it.text.clear()};reviewInputs.clear()
                    dialog.dismiss()
                    reviewDialog=AlertDialog.Builder(this).setTitle("Not sent · AI remains OFF")
                        .setView(ScrollView(this).apply{addView(BetaUi.text(this@BetaPilotActivity,
                            "Model: ${request.model}\n\nText:\n$g\n\nA future call would also send static planning instructions (read/local tools only). No private memories, saved tasks, chat history, credentials or audio are in this preview.\n\nMaximum output: 1024 tokens. Actual charges depend on your model/project. Previewing is not permission to connect or execute tools."))})
                        .setPositiveButton("Close",null).show().also{it.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)}
                }
            }
    }
    override fun onPause(){reviewDialog?.dismiss();reviewDialog=null;reviewInputs.forEach{it.text.clear()};reviewInputs.clear();super.onPause()}
}
