package com.example.voicebrainlive.memory.confirmed

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.VoiceBrainApplication
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Additive local UI only; no model/tool APIs and no access to legacy memory storage. */
class ConfirmedMemoryActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var categoryFilter: Spinner
    private lateinit var rows: LinearLayout
    private lateinit var add: Button
    private lateinit var refresh: Button
    private lateinit var contextControl: Button
    private var store: ConfirmedMemoryStore? = null
    private var memories: List<ConfirmedMemory> = emptyList()
    private var busy = false
    private var ready = false
    private var currentEditor: AlertDialog? = null
    private var currentPreview: AlertDialog? = null
    private var currentConsent: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val page = vertical()
        val beta=application as? com.example.voicebrainlive.ui.InteractiveBetaSettings
        if(beta!=null)page.addView(beta.betaHeader(this,"Confirmed memories"))
        else {page.addView(button("Back") { finish() });page.addView(label("Confirmed memories",24f))}
        page.addView(label(if(beta!=null) "Confirmed by you · Titles hidden for screenshots. Open View / edit for details." else "Save information you choose to remember. This section stores it locally on this device. Do not enter passwords, tokens or other secrets. AI context requires both global consent and each record's checkbox; both default off. Expired or over-180-day-old confirmations are withheld until reviewed. Legacy memory is separate."))
        contextControl = button(contextControlText()) { changeContextConsent() }
        page.addView(contextControl)
        search = input("Search saved memories")
        page.addView(search)
        categoryFilter = spinner(listOf("All categories") + CATEGORIES.map(::categoryName), "Filter category")
        page.addView(categoryFilter)
        status = label("Loading...")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        page.addView(status)
        add = button("Add confirmed memory") { if (ready && !busy) edit(null) }
        refresh = button("Refresh") { if (!busy) load() }
        add.isEnabled = false
        page.addView(add)
        page.addView(button("JSON ဖိုင်မှ Memory ထည့်ရန်") {
            startActivity(android.content.Intent(this,MemoryJsonImportActivity::class.java))
        })
        page.addView(refresh)
        rows = vertical()
        page.addView(rows)
        setContentView(ScrollView(this).apply { addView(page); isFillViewport = true })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        categoryFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { render() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        load()
    }

    override fun onStop() {
        // Invalidate hidden callbacks. Reopening after Home/rotation needs a fresh exact preview.
        currentPreview?.dismiss(); currentPreview = null
        currentEditor?.dismiss(); currentEditor = null
        currentConsent?.dismiss(); currentConsent = null
        super.onStop()
    }

    private fun contextControlText() = if (ConfirmedMemoryContextConsent.isEnabled(this))
        "Disable confirmed memory in AI sessions" else "Enable confirmed memory in AI sessions"

    private fun changeContextConsent() {
        if (busy || currentEditor != null || currentPreview != null || currentConsent != null ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (ConfirmedMemoryContextConsent.isEnabled(this)) {
            val saved = ConfirmedMemoryContextConsent.setAfterReview(this, false)
            contextControl.text = contextControlText()
            status.text = if (saved) "Confirmed context disabled for future sessions. Already transmitted data is unaffected."
                else "Context preference could not be saved. Check its displayed state before starting a session."
            return
        }
        val dialog = AlertDialog.Builder(this).setTitle("Review AI context sharing")
            .setView(ScrollView(this).apply { addView(label(ConfirmedMemoryContextConsent.DISCLOSURE).apply { setTextIsSelectable(true) }) })
            .setNegativeButton("Keep local", null).setPositiveButton("Enable for next new session", null).create()
        currentConsent = dialog
        var consumed = false
        dialog.setOnDismissListener { if (currentConsent === dialog) currentConsent = null }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                filterTouchesWhenObscured = true
                setOnClickListener {
                    if (consumed || !foreground() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                        currentConsent !== dialog || !dialog.isShowing) return@setOnClickListener
                    consumed = true
                    isEnabled = false
                    val saved = ConfirmedMemoryContextConsent.setAfterReview(this@ConfirmedMemoryActivity, true)
                    dialog.dismiss()
                    contextControl.text = contextControlText()
                    status.text = if (saved) "Confirmed context enabled for the next new session; no data sent now."
                        else "Context preference could not be saved; refresh the displayed state."
                }
            }
        }
        dialog.show()
    }

    private fun load() {
        setBusy(true)
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val owner = application as? VoiceBrainApplication
                        ?: throw ConfirmedMemoryStoreException(ConfirmedMemoryFailure.STORAGE_UNAVAILABLE)
                    val owned = owner.confirmedMemoryStore
                    owned to owned.list(includeExpired = true)
                }
                store = loaded.first
                memories = loaded.second
                ready = true
                status.text = if(application is com.example.voicebrainlive.ui.InteractiveBetaSettings) "${memories.size} confirmed memories · Local storage" else "${memories.size} saved memories. Expired memories remain visible until you delete them."
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                ready = false
                memories = emptyList()
                status.text = "Memory storage could not be opened. Existing data is preserved. Refresh to retry; saving is disabled."
            } finally { setBusy(false); render() }
        }
    }

    private fun render() {
        if (!::rows.isInitialized) return
        rows.removeAllViews()
        if (!ready) return
        val query = search.text.toString()
        val selected = CATEGORIES.getOrNull(categoryFilter.selectedItemPosition - 1)
        val filtered = memories.filter {
            (selected == null || it.draft.category == selected) &&
                (query.isEmpty() || it.draft.title.contains(query, true) || it.draft.content.contains(query, true))
        }
        if (filtered.isEmpty()) rows.addView(label("No saved memories match this view."))
        filtered.forEach { item ->
            rows.addView(label(if(application is com.example.voicebrainlive.ui.InteractiveBetaSettings) "Saved ${categoryName(item.draft.category)} · private title hidden" else item.draft.title, 18f))
            val now = System.currentTimeMillis()
            val contextAge = if (item.isActive(now) && now - item.confirmedAt <= ConfirmedMemoryContext.REVIEW_AFTER_MS) "Active" else "Review needed / context inactive"
            rows.addView(label("${categoryName(item.draft.category)} · $contextAge\n${privacyName(item.draft.privacy)} · Record context: ${if (item.draft.includeInContext) "Eligible when global sharing is on" else "Off"}\nRetention: ${expiry(item.draft.expiresAt)}"))
            rows.addView(button("View / edit") { if (ready && !busy) edit(item) }.apply { isEnabled = !busy })
            rows.addView(button("Delete") { if (ready && !busy) confirmDelete(item) }.apply { isEnabled = !busy })
        }
    }

    private fun edit(existing: ConfirmedMemory?) {
        if (currentEditor != null || currentPreview != null || currentConsent != null || !foreground()) return
        val editor = vertical()
        val title = input("Title").apply { setText(existing?.draft?.title ?: "") }
        val content = input("Memory text").apply { minLines = 4; setText(existing?.draft?.content ?: "") }
        val category = spinner(CATEGORIES.map(::categoryName), "Memory category")
        category.setSelection(CATEGORIES.indexOf(existing?.draft?.category ?: ConfirmedMemoryCategory.FACT))
        val personal = CheckBox(this).apply { text = "Contains personal information"; isChecked = existing?.draft?.privacy == MemoryPrivacy.PERSONAL }
        val context = CheckBox(this).apply { text = "Allow future assistant context use: send this exact record to the selected AI provider at the next new session when global sharing is on"; isChecked = existing?.draft?.includeInContext ?: false }
        val retentionOptions = if (existing == null) listOf("Forever", "7 days", "30 days") else listOf("Keep current expiration", "Forever", "7 days", "30 days")
        val retention = spinner(retentionOptions, "Retention")
        editor.addView(label("Title and text are saved exactly as shown. Review the complete preview before saving. Context-off records stay local. Both context consents permit provider transmission at the next new session. After expiry or 180 days without confirmation, eligibility stops; records remain until deleted. Review and save a correction to renew confirmation. Deletion cannot remove provider data already sent."))
        editor.addView(title); editor.addView(content); editor.addView(category)
        editor.addView(personal); editor.addView(context); editor.addView(retention)
        val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "Add memory" else "Edit memory")
            .setView(ScrollView(this).apply { addView(editor) })
            .setNegativeButton("Cancel", null).setPositiveButton("Review exact preview", null).create()
        currentEditor = dialog
        dialog.setOnDismissListener { if (currentEditor === dialog) currentEditor = null }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy || !ready || !foreground() || currentEditor !== dialog || !dialog.isShowing || currentPreview != null) return@setOnClickListener
                val position = retention.selectedItemPosition
                val option = retentionOptions[position]
                val expires = when (option) {
                    "Keep current expiration" -> existing?.draft?.expiresAt
                    "7 days" -> System.currentTimeMillis() + 7L * DAY
                    "30 days" -> System.currentTimeMillis() + 30L * DAY
                    else -> null
                }
                val draft = MemoryDraft(title.text.toString(), content.text.toString(), CATEGORIES[category.selectedItemPosition],
                    existing?.draft?.source ?: ConfirmedMemorySource.USER_ENTERED,
                    if (personal.isChecked) MemoryPrivacy.PERSONAL else MemoryPrivacy.ORDINARY,
                    context.isChecked, expires)
                if (draft.title.isBlank() || draft.title.length > 160 || draft.content.isBlank() || draft.content.length > 4096 ||
                    (expires != null && expires <= System.currentTimeMillis())) {
                    content.error = "Enter a nonblank title (up to 160 characters) and memory (up to 4096); choose future retention for expired memories. Text has not been trimmed or shortened."
                    return@setOnClickListener
                }
                // Immutable exact draft; edits behind the preview cannot change the saved payload.
                preview(draft, existing, dialog)
            }
        }
        dialog.show()
    }

    private fun preview(draft: MemoryDraft, existing: ConfirmedMemory?, editor: AlertDialog) {
        val exact = previewView(draft)
        val dialog = AlertDialog.Builder(this).setTitle("Confirm exact memory")
            .setView(ScrollView(this).apply { addView(exact) })
            .setNegativeButton("Back to edit", null).setPositiveButton("Save this exact preview", null).create()
        currentPreview = dialog
        var consumed = false
        dialog.setOnDismissListener { if (currentPreview === dialog) currentPreview = null }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (consumed || busy || !ready || !foreground() || currentPreview !== dialog || !dialog.isShowing || currentEditor !== editor || !editor.isShowing) return@setOnClickListener
                consumed = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.dismiss(); editor.dismiss()
                mutate {
                    if (existing == null) it.save(draft, true)
                    else it.correct(existing.id, existing.revision, draft, true)
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(existing: ConfirmedMemory) {
        if (currentEditor != null || currentPreview != null || currentConsent != null || !foreground()) return
        val dialog = AlertDialog.Builder(this).setTitle("Delete this saved memory?")
            .setView(ScrollView(this).apply { addView(previewView(existing.draft)) })
            .setNegativeButton("Cancel", null).setPositiveButton("Delete this exact memory", null).create()
        currentPreview = dialog
        var consumed = false
        dialog.setOnDismissListener { if (currentPreview === dialog) currentPreview = null }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (consumed || busy || !ready || !foreground() || currentPreview !== dialog || !dialog.isShowing) return@setOnClickListener
                consumed = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.dismiss()
                mutate { owned ->
                    check(owned.forget(existing.id, existing.revision, true))
                }
            }
        }
        dialog.show()
    }

    private fun previewView(draft: MemoryDraft) = vertical().apply {
        addView(label("Title:")); addView(label(draft.title).apply { setTextIsSelectable(true) })
        addView(label("Full memory text:")); addView(label(draft.content).apply { setTextIsSelectable(true) })
        addView(label("Category: ${categoryName(draft.category)}\n${privacyName(draft.privacy)}\nSelected AI provider context: ${if (draft.includeInContext) "Eligible at next new session only when global sharing is enabled" else "Off / local only"}\nRetention: ${expiry(draft.expiresAt)}\nConfirmation requires review again after 180 days; deletion does not remove already transmitted provider data."))
    }

    private fun mutate(action: (ConfirmedMemoryStore) -> Unit) {
        val owned = store ?: return
        if (busy || !ready) return
        setBusy(true)
        lifecycleScope.launch {
            try {
                memories = withContext(Dispatchers.IO) { action(owned); owned.list(includeExpired = true) }
                status.text = "Saved memory list updated."
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                ready = false
                memories = emptyList()
                status.text = "The change could not be verified. Existing data is preserved. Refresh before trying again; a stale preview or unavailable storage may be the cause."
            } finally { setBusy(false); render() }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        if (::add.isInitialized) add.isEnabled = ready && !busy
        if (::refresh.isInitialized) refresh.isEnabled = !busy
        render()
    }
    private fun foreground() = !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private fun label(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size
        if(application is com.example.voicebrainlive.ui.InteractiveBetaSettings){includeFontPadding=true;setLineSpacing(2*resources.displayMetrics.density,1.1f)}
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }
    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
    }
    private fun spinner(values: List<String>, description: String) = Spinner(this).apply {
        adapter = ArrayAdapter(this@ConfirmedMemoryActivity, android.R.layout.simple_spinner_item, values)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        contentDescription = description
    }
    private fun expiry(time: Long?) = time?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Forever"
    private fun privacyName(value: MemoryPrivacy) = when (value) {
        MemoryPrivacy.PERSONAL -> "Personal information"
        MemoryPrivacy.ORDINARY -> "Ordinary information"
        MemoryPrivacy.SECRET -> "Restricted secret information"
    }
    private fun categoryName(value: ConfirmedMemoryCategory) = when (value) {
        ConfirmedMemoryCategory.FACT -> "Facts"
        ConfirmedMemoryCategory.PREFERENCE -> "Preferences"
        ConfirmedMemoryCategory.GOAL -> "Goals"
        ConfirmedMemoryCategory.ROUTINE -> "Routines"
        ConfirmedMemoryCategory.USER_NOTE -> "User notes"
    }
    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private val CATEGORIES = ConfirmedMemoryCategory.entries.toList()
    }
}
