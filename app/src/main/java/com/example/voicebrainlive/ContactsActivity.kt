package com.example.voicebrainlive

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.commands.ApprovalUiLease
import com.example.voicebrainlive.commands.QuickContactStore
import com.example.voicebrainlive.commands.SensitiveActionGuard
import com.example.voicebrainlive.ui.components.ContactsScreen
import com.example.voicebrainlive.ui.theme.VoiceBrainLiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class ContactsActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences
    private var contacts by mutableStateOf<List<Contact>>(emptyList())
    private var draftName by mutableStateOf("")
    private var draftPhone by mutableStateOf("")
    private var pendingCallContact: Contact? = null
    private val approvalSession = UUID.randomUUID().toString()

    data class Contact(val name: String, val phone: String)

    private val requestContactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pickContactLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            } else {
                Toast.makeText(this, getString(R.string.contacts_permission_required), Toast.LENGTH_SHORT).show()
            }
        }

    private val requestCallPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingCallContact?.let { launchCall(it) }
            } else {
                Toast.makeText(this, getString(R.string.call_permission_required), Toast.LENGTH_SHORT).show()
            }
            pendingCallContact = null
        }

    private val pickContactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            result.data?.data?.let { uri ->
                try {
                    contentResolver.query(
                        uri,
                        arrayOf(
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                        ),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val phone = cursor.getString(0)?.replace(Regex("[^0-9+]"), "") ?: ""
                            val name = cursor.getString(1)?.trim() ?: ""
                            draftPhone = phone
                            if (name.isNotBlank() && draftName.isBlank()) {
                                draftName = name
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ContactsActivity", "onActivityResult", e)
                }
            }
        }

    private val importContactsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val arr = JSONArray(input.bufferedReader().readText())
                    synchronized(QuickContactStore.lock) {
                        QuickContactStore.write(prefs) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val name = obj.optString("name", "").trim()
                                val phone = obj.optString("phone", "").replace(Regex("[^0-9+]"), "")
                                if (name.isNotBlank() && phone.isNotBlank()) {
                                    val matches = QuickContactStore.matching(prefs, name)
                                    if (matches.size > 1) error("Ambiguous contact; resolve case variants before import.")
                                    putString(matches.keys.singleOrNull() ?: name.lowercase(Locale.ROOT), phone)
                                }
                            }
                        }
                    }
                    loadContacts()
                    Toast.makeText(this@ContactsActivity, getString(R.string.import_success), Toast.LENGTH_SHORT).show()
                } ?: run { Toast.makeText(this@ContactsActivity, getString(R.string.import_error), Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Toast.makeText(this@ContactsActivity, getString(R.string.import_error) + ": " + e.message, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("quick_contacts", MODE_PRIVATE)
        loadContacts()

        setContent {
            VoiceBrainLiveTheme(darkTheme = ThemeUtils.getTheme(this) != "light", dynamicColor = false) {
                ContactsScreen(
                    contacts = contacts,
                    draftName = draftName,
                    draftPhone = draftPhone,
                    onDraftNameChange = { draftName = it },
                    onDraftPhoneChange = { draftPhone = it },
                    onPickPhoneForAdd = { launchContactPicker() },
                    onAddContact = {
                        val name = draftName.trim()
                        val phone = draftPhone.trim()
                        when {
                            name.isBlank() -> Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                            phone.isBlank() -> Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
                            else -> addContact(name, phone)
                        }
                    },
                    onBack = { finish() },
                    onExport = { exportContacts() },
                    onImport = { importContactsLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    onCall = { contact -> callContact(contact) },
                    onUpdate = { oldContact, newName, newPhone -> updateContact(oldContact, newName, newPhone) },
                    onDelete = { contact -> deleteContact(contact) }
                )
            }
        }
    }

    private fun loadContacts() {
        val loaded = mutableListOf<Contact>()
        prefs.all.forEach { entry ->
            val phone = entry.value
            if (phone is String) {
                loaded.add(Contact(entry.key, phone))
            }
        }
        loaded.sortBy { it.name }
        contacts = loaded
    }

    private fun addContact(name: String, phone: String) {
        val cleanName = name.trim()
        val cleanPhone = normalizePhoneForStorage(phone)
        val snapshot = QuickContactStore.snapshot(prefs, cleanName)
        if (snapshot.keys.isNotEmpty() || !QuickContactStore.compareAndWrite(prefs, snapshot) { putString(cleanName, cleanPhone) }) {
            Toast.makeText(this, "Contact exists or changed. Review the current contacts.", Toast.LENGTH_LONG).show()
            return
        }
        draftName = ""
        draftPhone = ""
        loadContacts()
        Toast.makeText(this, "Contact added: $cleanName", Toast.LENGTH_SHORT).show()
    }

    private fun normalizePhoneForStorage(rawPhone: String): String {
        val cleaned = rawPhone.replace(Regex("[^0-9+]"), "")
        if (cleaned.startsWith("+") || cleaned.startsWith("0")) return cleaned
        if (cleaned.startsWith("82") && cleaned.length in 10..12) return "+$cleaned"
        if (cleaned.startsWith("959") && cleaned.length >= 11) return "+$cleaned"
        return cleaned
    }

    private fun deleteContact(contact: Contact) {
        val snapshot = QuickContactStore.snapshot(prefs, contact.name)
        if (snapshot.keys.size != 1 || snapshot.keys[contact.name] != contact.phone ||
            !QuickContactStore.compareAndWrite(prefs, snapshot) { remove(contact.name) }) {
            Toast.makeText(this, "Contact changed or ambiguous. Review it again.", Toast.LENGTH_LONG).show()
            loadContacts()
            return
        }
        loadContacts()
        Toast.makeText(this, "Deleted: ${contact.name}", Toast.LENGTH_SHORT).show()
    }

    private fun launchContactPicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        pickContactLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }

    private fun updateContact(oldContact: Contact, newName: String, newPhone: String) {
        val cleanName = newName.trim()
        val cleanPhone = normalizePhoneForStorage(newPhone)
        synchronized(QuickContactStore.lock) {
            val snapshot = QuickContactStore.snapshot(prefs, oldContact.name)
            val destination = QuickContactStore.matching(prefs, cleanName)
            if (snapshot.keys.size != 1 || snapshot.keys[oldContact.name] != oldContact.phone ||
                destination.keys.any { it != oldContact.name } ||
                !QuickContactStore.compareAndWrite(prefs, snapshot) {
                    remove(oldContact.name)
                    putString(cleanName, cleanPhone)
                }) {
                Toast.makeText(this, "Contact changed or ambiguous. Review it again.", Toast.LENGTH_LONG).show()
                loadContacts()
                return
            }
        }
        loadContacts()
        Toast.makeText(this, "Updated: $cleanName", Toast.LENGTH_SHORT).show()
    }

    private fun exportContacts() {
        val arr = JSONArray()
        prefs.all.entries.forEach { entry ->
            if (entry.value is String) arr.put(JSONObject().apply {
                put("name", entry.key)
                put("phone", entry.value)
            })
        }
        val file = File(cacheDir, "quick_contacts_export.json")
        FileOutputStream(file).use { it.write(arr.toString().toByteArray()) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, "Voice Brain Contacts Export")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, getString(R.string.export_contacts)) })
        Toast.makeText(this, getString(R.string.export_success), Toast.LENGTH_SHORT).show()
    }

    private fun callContact(contact: Contact) {
        val phoneNumber = contact.phone.replace(Regex("[^0-9+]"), "")
        if (phoneNumber.isBlank()) {
            Toast.makeText(this, getString(R.string.invalid_phone_number), Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallContact = contact
            requestCallPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            return
        }
        launchCall(contact)
    }

    private fun launchCall(contact: Contact) {
        lifecycleScope.launch(Dispatchers.IO) {
            val output = SensitiveActionGuard.run(
                this@ContactsActivity, approvalSession, "make_call", contact.name, null
            ) { "BLOCKED: Legacy manual call disabled." }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ContactsActivity, output, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ApprovalUiLease.resume(this)
    }

    override fun onPause() {
        ApprovalUiLease.pause(this)
        super.onPause()
    }

    override fun onDestroy() {
        SensitiveActionGuard.invalidate(approvalSession)
        super.onDestroy()
    }
}
