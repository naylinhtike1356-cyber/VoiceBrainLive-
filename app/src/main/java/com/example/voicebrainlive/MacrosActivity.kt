package com.example.voicebrainlive

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.commands.AutomationCommandValidator
import com.example.voicebrainlive.databinding.ActivityMacrosBinding

class MacrosActivity : ComponentActivity() {
    private lateinit var panel: MacrosPanel
    data class MacroItem(val name: String, val sequence: String)
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        val binding = ActivityMacrosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        panel = MacrosPanel(this, binding)
    }
    override fun onResume() { super.onResume(); panel.loadMacros() }
    override fun onPause() { panel.pause(); super.onPause() }
    class MacrosAdapter(
        private var items: List<MacroItem>,
        private val onClick: (MacroItem) -> Unit,
        private val onDelete: (MacroItem) -> Unit
    ) : RecyclerView.Adapter<MacrosAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvMacroName)
            val tvSequence: TextView = view.findViewById(R.id.tvMacroSequence)
            val tvStatus: TextView = view.findViewById(R.id.tvMacroStatus)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_macro, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvStatus.setText(if (AutomationCommandValidator.validateSequence(item.sequence).valid) R.string.hub_macro_active else R.string.hub_macro_invalid)
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
