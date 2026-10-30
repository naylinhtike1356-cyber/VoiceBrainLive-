package com.example.voicebrainlive.agent.brain

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

/** Ephemeral local display, deliberately separate from conversation history and LastResponseStore. */
internal class AgentConversationDialog(private val activity: Activity, private val userInput: (String) -> Unit) {
    private var dialog: AlertDialog? = null
    private var content: TextView? = null
    private var reviewId: String? = null
    fun show(text: String) {
        reviewId = Regex("^Agent plan ([A-Za-z0-9_-]{1,80}) — DRAFT\\n").find(text)?.groupValues?.get(1)
        if (dialog == null) {
            val view = TextView(activity).apply {
                textSize = 17f; setPadding(24, 16, 24, 16); setTextIsSelectable(true)
                isSaveEnabled = false; isSaveFromParentEnabled = false
                contentDescription = "Local agent plan and activity; sensitive actions are proposals only"
            }
            content = view
            dialog = AlertDialog.Builder(activity).setTitle("Agent plan · local review")
                .setView(ScrollView(activity).apply { isSaveEnabled = false; addView(view) })
                .setPositiveButton("Run read steps", null).setNeutralButton("Cancel work", null)
                .setNegativeButton("Close", null).create().also { popup ->
                    popup.setOnDismissListener { content?.text = ""; content = null; reviewId = null; dialog = null }
                    popup.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    popup.show()
                    popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val id = reviewId ?: return@setOnClickListener
                        reviewId = null; popup.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        userInput("/agent-run $id")
                    }
                    popup.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { userInput("/agent-cancel") }
                }
        }
        content?.text = text
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = reviewId != null
    }
    fun close() { content?.text = ""; dialog?.dismiss(); content = null; dialog = null; reviewId = null }
}
