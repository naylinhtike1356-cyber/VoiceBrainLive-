package com.example.voicebrainlive

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.commands.AutomationCommandValidator
import com.example.voicebrainlive.databinding.ActivityMacrosBinding

class MacrosActivity : ComponentActivity() {

    private lateinit var binding: ActivityMacrosBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: MacrosAdapter

    data class MacroItem(val name: String, val sequence: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMacrosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        prefs = getSharedPreferences("custom_macros", Context.MODE_PRIVATE)

        // Pre-populate defaults if empty
        ensureDefaultMacros()

        binding.btnBack.setOnClickListener { finish() }
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

    override fun onResume() {
        super.onResume()
        loadMacros()
    }


    private fun ensureDefaultMacros() {
        val all = prefs.all
        if (all.isEmpty()) {
            prefs.edit().apply {
                putString("study mode", "set_focus_mode:30;set_ringer_silent")
                putString("စာကြည့်ချိန်", "set_focus_mode:30;set_ringer_silent")
                putString("night mode", "set_ringer_silent;turn_off_flashlight")
                putString("အိပ်တော့မယ်", "set_ringer_silent;turn_off_flashlight")
                putString("morning routine", "get_weather;my_schedule;volume_up")
                putString("မနက်ခင်း", "get_weather;my_schedule;volume_up")
                putString("meeting mode", "set_ringer_vibrate;mute")
                putString("အစည်းအဝေး", "set_ringer_vibrate;mute")
                apply()
            }
        }
    }

    private fun loadMacros() {
        val list = prefs.all.map { (k, v) -> MacroItem(k, v?.toString() ?: "") }
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

    private fun showCreateOrEditDialog(item: MacroItem?) {
        val isEdit = item != null
        val title = if (isEdit) "မက်ခရို ပြင်ဆင်ရန်" else "မက်ခရိုအသစ် ဖန်တီးရန်"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        val tvLabelName = TextView(this).apply {
            text = "မက်ခရိုအမည် (သို့) အသံပြောရမည့် စကားစု:"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosActivity) == "light") Color.BLACK else Color.WHITE)
            textSize = 14f
        }
        val etName = EditText(this).apply {
            hint = "ဥပမာ - စာကြည့်ချိန်"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosActivity) == "light") Color.BLACK else Color.WHITE)
            setHintTextColor(Color.GRAY)
            if (isEdit) {
                setText(item?.name)
                isEnabled = false
            }
        }

        val tvLabelSequence = TextView(this).apply {
            text = "လုပ်ဆောင်မည့် ကွန်မန်ဒ်များ (; ဖြင့် ခွဲပေးပါ):"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosActivity) == "light") Color.BLACK else Color.WHITE)
            textSize = 14f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val etSequence = EditText(this).apply {
            hint = "ဥပမာ - set_focus_mode:30;set_ringer_silent"
            setTextColor(if (ThemeUtils.getTheme(this@MacrosActivity) == "light") Color.BLACK else Color.WHITE)
            setHintTextColor(Color.GRAY)
            if (isEdit) {
                setText(item?.sequence)
            }
        }

        container.addView(tvLabelName)
        container.addView(etName)
        container.addView(tvLabelSequence)
        container.addView(etSequence)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("သိမ်းမည်") { dialog, _ ->
                val name = etName.text.toString().trim()
                val sequence = etSequence.text.toString().trim()

                if (name.isBlank() || sequence.isBlank()) {
                    Toast.makeText(this, "အချက်အလက် ပြည့်စုံစွာ ဖြည့်ပေးပါ။", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.length > 60) {
                    Toast.makeText(this, "မက်ခရိုအမည်သည် 60 characters ထက် မကျော်ရပါ။", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val validation = AutomationCommandValidator.validateSequence(sequence)
                if (!validation.valid) {
                    Toast.makeText(this, validation.message, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                prefs.edit().putString(name, sequence).apply()
                Toast.makeText(this, "မက်ခရိုကို သိမ်းဆည်းလိုက်ပါပြီ။", Toast.LENGTH_SHORT).show()
                loadMacros()
                dialog.dismiss()
            }
            .setNegativeButton("မသိမ်းပါ") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun confirmDelete(item: MacroItem) {
        AlertDialog.Builder(this)
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

    class MacrosAdapter(
        private var items: List<MacroItem>,
        private val onClick: (MacroItem) -> Unit,
        private val onDelete: (MacroItem) -> Unit
    ) : RecyclerView.Adapter<MacrosAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvMacroName)
            val tvSequence: TextView = view.findViewById(R.id.tvMacroSequence)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_macro, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvSequence.text = item.sequence.replace(";", "; ")
            holder.itemView.setOnClickListener { onClick(item) }
            holder.btnDelete.setOnClickListener { onDelete(item) }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newList: List<MacroItem>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
