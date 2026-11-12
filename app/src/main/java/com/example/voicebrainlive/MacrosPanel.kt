package com.example.voicebrainlive

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.voicebrainlive.MacrosActivity.MacroItem
import com.example.voicebrainlive.MacrosActivity.MacrosAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voicebrainlive.commands.AutomationCommandValidator
import com.example.voicebrainlive.commands.MacroDefinitions
import com.example.voicebrainlive.databinding.ActivityMacrosBinding

/** Same macro preferences and validation for the hub and legacy entry point. */
internal class MacrosPanel(context: Context, private val binding: ActivityMacrosBinding) : android.content.ContextWrapper(context) {
    private var dialog: AlertDialog? = null
    fun pause() { dialog?.dismiss(); dialog = null }
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: MacrosAdapter
    init {
        if (ThemeUtils.getTheme(this) == "light") binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        prefs = getSharedPreferences("custom_macros", Context.MODE_PRIVATE)

        // Opening a list must not create executable definitions or restore deleted macros.

        binding.btnAddMacro.setOnClickListener { showCreateOrEditDialog(null) }

        // Setup voice creation
        val showGuide = prefs.getBoolean("show_macro_guide", true)
        binding.layoutVoiceGuide.visibility = if (showGuide) View.VISIBLE else View.GONE
        binding.btnDismissGuide.setOnClickListener {
            prefs.edit().putBoolean("show_macro_guide", false).apply()
            binding.layoutVoiceGuide.visibility = View.GONE
        }

        val startVoiceCreate = {
            val intent = android.content.Intent(this, AssistantActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_TEXT_INPUT, "မက်ခရိုအသစ်ဆောက်ပေး")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
        binding.btnVoiceCreate.setOnClickListener { startVoiceCreate() }
        binding.btnTryVoiceCreate.setOnClickListener { startVoiceCreate() }

        adapter = MacrosAdapter(
            items = emptyList(),
            onClick = { item -> showCreateOrEditDialog(item) },
            onDelete = { item -> confirmDelete(item) }
        )

        binding.recyclerMacros.layoutManager = LinearLayoutManager(this)
        binding.recyclerMacros.adapter = adapter
    }
    fun loadMacros() {
        val list = MacroDefinitions.entries(prefs.all).map { (k, v) -> MacroItem(k, v) }
            .sortedBy { it.name }
        adapter.updateList(list)

        if (list.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerMacros.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerMacros.visibility = View.VISIBLE
        }
    }

    fun showCreateOrEditDialog(item: MacroItem?) {
        val isEdit = item != null
        val title = if (isEdit) "မက်ခရို ပြင်ဆင်ရန်" else "မက်ခရိုအသစ် ဖန်တီးရန်"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val tvLabelName = TextView(this).apply {
            text = "မက်ခရိုအမည် (သို့) အသံပြောရမည့် စကားစု:"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosPanel) == "light") Color.BLACK else Color.WHITE)
            textSize = 14f
        }
        val etName = EditText(this).apply {
            tag = "macro_name"
            hint = "ဥပမာ - စာကြည့်ချိန်"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosPanel) == "light") Color.BLACK else Color.WHITE)
            setHintTextColor(Color.GRAY)
            if (isEdit) {
                setText(item?.name)
                isEnabled = false
            }
        }

        val tvLabelSequence = TextView(this).apply {
            text = "လုပ်ဆောင်မည့် ကွန်မန်ဒ်များ (; ဖြင့် ခွဲပေးပါ):"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosPanel) == "light") Color.BLACK else Color.WHITE)
            textSize = 14f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val etSequence = EditText(this).apply {
            tag = "macro_sequence"
            hint = "ဥပမာ - set_focus_mode:30;set_ringer_silent"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosPanel) == "light") Color.BLACK else Color.WHITE)
            setHintTextColor(Color.GRAY)
            if (isEdit) {
                setText(item?.sequence)
            }
        }

        container.addView(tvLabelName)
        container.addView(etName)
        container.addView(tvLabelSequence)
        container.addView(etSequence)
        val preview = TextView(this).apply {
            tag = "macro_step_preview"
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            textSize = 14f
        }
        container.addView(preview)
        fun updatePreview() { preview.text = "လုပ်မည့် အဆင့်များ\n" + MacroDefinitions.preview(etSequence.text.toString()) }
        etSequence.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        updatePreview()

        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(container) })
            .setPositiveButton("သိမ်းမည်", null)
            .setNegativeButton("မသိမ်းပါ") { dialog, _ -> dialog.dismiss() }
            .create().also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = etName.text.toString().trim()
                    val sequence = etSequence.text.toString().trim()

                    if (name.isBlank() || sequence.isBlank()) {
                        Toast.makeText(this, "အချက်အလက် ပြည့်စုံစွာ ဖြည့်ပေးပါ။", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (MacroDefinitions.normalize(name) == MacroDefinitions.GUIDE_KEY) {
                        etName.error = "ဤအမည်ကို အက်ပ်ဆက်တင်အတွက် သီးသန့်ထားပါသည်။"
                        return@setOnClickListener
                    }
                    if (name.length > 60) {
                        Toast.makeText(this, "မက်ခရိုအမည်သည် 60 characters ထက် မကျော်ရပါ။", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    if (!isEdit && prefs.all.keys.any { MacroDefinitions.normalize(it) == MacroDefinitions.normalize(name) }) {
                        etName.error = "ဤအမည် ရှိပြီးသားပါ။ မူလမက်ခရိုကို နှိပ်ပြီး ပြင်နိုင်သည်။"
                        return@setOnClickListener
                    }
                    val validation = AutomationCommandValidator.validateSequence(sequence)
                    if (!validation.valid) {
                        Toast.makeText(this, validation.message, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }

                    prefs.edit().putString(if (isEdit) name else MacroDefinitions.normalize(name), sequence).apply()
                    Toast.makeText(this, "မက်ခရိုကို သိမ်းဆည်းလိုက်ပါပြီ။", Toast.LENGTH_SHORT).show()
                    loadMacros()
                    dialog.dismiss()
                }
            }
    }

    private fun confirmDelete(item: MacroItem) {
        dialog = AlertDialog.Builder(this)
            .setTitle("မက်ခရိုဖျက်ရန်")
            .setMessage("မက်ခရို '${item.name}' ကို ဖျက်ချင်ပါသလား။")
            .setPositiveButton("ဖျက်မည်") { _, _ ->
                prefs.edit().remove(item.name).apply()
                Toast.makeText(this, "မက်ခရို ဖျက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
                loadMacros()
            }
            .setNegativeButton("မဖျက်ပါ") { d, _ -> d.dismiss() }
            .show()
    }

}
