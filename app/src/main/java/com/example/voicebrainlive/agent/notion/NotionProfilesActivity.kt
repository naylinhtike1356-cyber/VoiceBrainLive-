package com.example.voicebrainlive.agent.notion

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.VoiceBrainApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Multiple named local fixture profiles. All rendered states explicitly retain their provenance. */
class NotionProfilesActivity : AppCompatActivity() {
    private var service: DirectNotionProfiles? = null
    private var snapshot: ProfileVaultView? = null
    private var epoch = 0L
    private var busy = false
    private var dialog: AlertDialog? = null
    private lateinit var name: EditText
    private lateinit var create: Button
    private lateinit var refresh: Button
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this); super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        service = (application as? VoiceBrainApplication)?.directNotionProfiles
        val page = vertical()
        page.addView(button("Back", "notion_back") { finish() })
        page.addView(label("Notion profile foundation — local simulation"))
        page.addView(label("Real accounts: NOT CONNECTED. Create multiple named local fixture profiles, review their exact sample schema and choose a profile for future synthetic study previews. No Notion request, account verification, SmartNote code, private note, message or remote write is used. Do not enter real tokens here. Pending/Synced is sync state; it is not unfinished-task status."))
        page.addView(label("Local fixture profile name"))
        name = EditText(this).apply {
            tag = "notion_name"; contentDescription = "Local fixture profile name"; setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(80)); minHeight = dp(48)
        }
        page.addView(name)
        create = button("Create disabled local fixture draft", "notion_create") {
            val exact = name.text.toString().trim()
            if (!opaqueText(exact, 80)) status.text = "Enter a profile name of 1–80 characters."
            else perform { it.createSyntheticDraft(exact); "Local fixture draft stored. It is not an account connection. Review its sample schema below." }
        }
        refresh = button("Refresh stored profiles", "notion_refresh") { reload() }
        page.addView(create); page.addView(refresh)
        status = label("Loading stored profile metadata…").apply {
            tag = "notion_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        page.addView(status); rows = vertical(); page.addView(rows)
        setContentView(ScrollView(this).apply { addView(page); isFillViewport = true })
        controls()
    }
    override fun onStart() { super.onStart(); epoch++; busy = false }
    override fun onResume() { super.onResume(); reload() }
    override fun onStop() { epoch++; dialog?.dismiss(); dialog = null; super.onStop() }
    private fun visible() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun reload() = perform { "Stored profiles loaded. Real accounts remain NOT CONNECTED." }
    private fun current(p: ConnectionProfileView) = visible() && !busy && snapshot?.profiles?.any {
        it.profileId == p.profileId && it.revision == p.revision && it.status == p.status
    } == true
    private fun perform(action: suspend (DirectNotionProfiles) -> String) {
        val api = service ?: run { status.text = "Profile storage unavailable."; controls(); return }
        if (busy || !visible()) return
        val owner = epoch; busy = true; controls()
        lifecycleScope.launch {
            if (owner != epoch || !visible()) return@launch
            var message = "Request did not finish. Review current stored profiles before trying again."
            try { message = action(api) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (safe: ProfileStoreException) { message = "Request did not finish (${safe.failure.name}). Existing data is preserved." }
            catch (_: Exception) { }
            var loaded: ProfileVaultView? = null
            try { loaded = api.views() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Stored profiles could not be read. Changes are disabled; existing data is preserved." }
            if (owner != epoch || !visible()) return@launch
            busy = false; snapshot = loaded; status.text = message; render(); controls()
        }
    }
    private fun controls() {
        if (!::create.isInitialized) return
        val enabled = service != null && snapshot != null && !busy
        name.isEnabled = enabled; create.isEnabled = enabled && (snapshot?.profiles?.size ?: 32) < 32
        refresh.isEnabled = service != null && !busy
        if (::rows.isInitialized) for (i in 0 until rows.childCount) (rows.getChildAt(i) as? Button)?.isEnabled = enabled
    }
    private fun render() {
        rows.removeAllViews()
        val data = snapshot ?: run { rows.addView(label("Profile history unavailable; this is not an empty writable vault.")); return }
        rows.addView(label("Vault revision ${data.revision}; selected future profile ${data.selectedProfileId ?: "none"}. A selection never changes an already captured plan."))
        data.cooldowns.forEach { rows.addView(label("Synthetic rate-limit protection: retry hint ${it.retryAfterSeconds ?: "unknown"} seconds; until ${it.until}; review required ${it.requiresReview}. No automatic retry.")) }
        if (data.profiles.isEmpty()) rows.addView(label("No local fixture profiles. Actual Notion accounts are not connected."))
        data.profiles.forEach { p ->
            rows.addView(label(describe(p)))
            fun action(title: String, key: String, run: () -> Unit) {
                rows.addView(button(title, "notion_${key}_${p.profileId}") { if (current(p)) run() })
            }
            if (p.status == DirectProfileStatus.DRAFT) action("Review sample schema", "review") {
                confirm(p, "Verify this synthetic fixture only?", describe(p) +
                    "\nThis checks an invented local fixture. It does not verify a real account, schema or sharing permission.", "Verify fixture") {
                    it.verifySynthetic(p.profileId, p.revision); "Synthetic fixture review recorded. No account was connected."
                }
            }
            if (p.status != DirectProfileStatus.REVOKED) {
                action("Select for a future synthetic draft", "select") {
                    perform { it.select(p.profileId, p.revision); "Future selection stored. Existing captured plans keep their exact profile." }
                }
                action("Disable this local profile", "disable") {
                    perform { it.disable(p.profileId, p.revision); "Disable request finished; current stored state is shown." }
                }
                action("Revoke this local profile", "revoke") {
                    confirm(p, "Revoke this local profile?", describe(p) +
                        "\nLocal reads will be rejected. No external provider data or credential is deleted.", "Revoke profile") {
                        it.revoke(p.profileId, p.revision); "Revocation request finished; current stored state is shown."
                    }
                }
            }
        }
    }
    private fun confirm(p: ConnectionProfileView, title: String, message: String, positive: String,
        action: suspend (DirectNotionProfiles) -> String) {
        if (!current(p)) return
        dialog?.dismiss(); val owner = epoch; var consumed = false
        val preview = AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("Cancel", null).setPositiveButton(positive, null).create()
        dialog = preview; preview.setOnDismissListener { if (dialog === preview) dialog = null }
        preview.setOnShowListener {
            preview.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            preview.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!consumed && dialog === preview && owner == epoch && current(p)) {
                    consumed = true; dialog = null; preview.dismiss(); perform(action)
                }
            }
        }
        preview.show()
    }
    private fun describe(p: ConnectionProfileView) = "${p.displayName}\nLocal profile ${p.profileId}; revision ${p.revision}; ${p.status}\n" +
        "Evidence: ${p.evidence ?: "unverified local draft"}; REAL ACCOUNT NOT CONNECTED\n" +
        "Local namespace ${p.namespace.namespaceId}; provider workspace unknown\n" +
        "Sample database ${p.resource.databaseId}; sample data source ${p.resource.dataSourceId}\n" +
        "API ${p.apiVersion}; schema revision ${p.schema.revision}; fingerprint ${p.schema.fingerprint}\n" +
        "Task field ${p.schema.businessStatus.propertyId}; unfinished ${p.schema.unfinishedValues.joinToString()}; sync field ${p.schema.syncStatus?.propertyId ?: "none"}\n" +
        "Credential reference ${p.credential.credentialId} version ${p.credential.version}; secret is not displayed."
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
    private fun label(value: String) = TextView(this).apply { text = value; textSize = 17f; setTextIsSelectable(true) }
    private fun button(value: String, key: String, run: () -> Unit) = Button(this).apply {
        text = value; tag = key; contentDescription = value; minHeight = dp(48); minimumHeight = dp(48); setOnClickListener { run() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
