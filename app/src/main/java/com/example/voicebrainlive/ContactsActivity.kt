package com.example.voicebrainlive

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.ContactsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicebrainlive.databinding.ActivityContactsBinding

class ContactsActivity : ComponentActivity() {

    private lateinit var binding: ActivityContactsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: ContactsAdapter
    private val contacts = mutableListOf<Contact>()

    data class Contact(val name: String, val phone: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        prefs = getSharedPreferences("quick_contacts", MODE_PRIVATE)

        setupViews()
        binding.btnExportContacts.setOnClickListener { exportContacts() }
        binding.btnImportContacts.setOnClickListener { importContactsLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        loadContacts()
    }

    private var pendingPhoneField: EditText? = null

    private val requestContactsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingPhoneField?.let { field ->
                pickContactLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }
        } else {
            Toast.makeText(this, getString(R.string.contacts_permission_required), Toast.LENGTH_SHORT).show()
            pendingPhoneField = null
        }
    }

    private var pendingCallContact: Contact? = null

    private val requestCallPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingCallContact?.let { contact ->
                launchCall(contact)
            }
        } else {
            Toast.makeText(this, getString(R.string.call_permission_required), Toast.LENGTH_SHORT).show()
        }
        pendingCallContact = null
    }

    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            pendingPhoneField = null
            return@registerForActivityResult
        }
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
                        pendingPhoneField?.setText(phone)
                        if (pendingPhoneField == binding.etPhone && name.isNotBlank()) {
                            binding.etName.setText(name)
                        }
                    }
                }
            } catch (e: Exception) { Log.w("ContactsActivity", "onActivityResult", e) }
            pendingPhoneField = null
        } ?: run { pendingPhoneField = null }
    }

    private val importContactsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val json = input.bufferedReader().readText()
                val arr = JSONArray(json)
                synchronized(com.example.voicebrainlive.commands.QuickContactStore.lock) {
                    com.example.voicebrainlive.commands.QuickContactStore.write(prefs) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val name = obj.optString("name", "").trim()
                            val phone = obj.optString("phone", "").replace(Regex("[^0-9+]"), "")
                            if (name.isNotBlank() && phone.isNotBlank()) {
                                val matches = com.example.voicebrainlive.commands.QuickContactStore.matching(prefs, name)
                                if (matches.size > 1) error("Ambiguous contact; resolve case variants before import.")
                                putString(matches.keys.singleOrNull() ?: name.lowercase(java.util.Locale.ROOT), phone)
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

    private fun setupViews() {
        binding.btnBack.setOnClickListener { finish() }

        binding.etPhone.setOnClickListener { launchContactPicker(binding.etPhone) }

        binding.btnAdd.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val phone = binding.etPhone.text.toString().trim()

            if (name.isBlank()) {
                Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (phone.isBlank()) {
                Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            addContact(name, phone)
            binding.etName.text?.clear()
            binding.etPhone.text?.clear()
            Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
        }

        adapter = ContactsAdapter(
            contacts,
            onCall = { contact -> callContact(contact) },
            onEdit = { contact -> showEditDialog(contact) },
            onDelete = { contact -> deleteContact(contact) }
        )
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = adapter
    }

    private fun loadContacts() {
        contacts.clear()
        prefs.all.forEach { entry ->
            val name = entry.key
            val phone = entry.value
            if (phone is String) {
                contacts.add(Contact(name, phone))
            }
        }
        contacts.sortBy { it.name }
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun addContact(name: String, phone: String) {
        val cleanName = name.trim()
        val cleanPhone = normalizePhoneForStorage(phone)
        val snapshot = com.example.voicebrainlive.commands.QuickContactStore.snapshot(prefs, cleanName)
        if (snapshot.keys.isNotEmpty() || !com.example.voicebrainlive.commands.QuickContactStore.compareAndWrite(prefs, snapshot) { putString(cleanName, cleanPhone) }) {
            Toast.makeText(this, "Contact exists or changed. Review the current contacts.", Toast.LENGTH_LONG).show()
            return
        }
        loadContacts()
    }

    private fun normalizePhoneForStorage(rawPhone: String): String {
        val cleaned = rawPhone.replace(Regex("[^0-9+]"), "")
        if (cleaned.startsWith("+") || cleaned.startsWith("0")) return cleaned
        if (cleaned.startsWith("82") && cleaned.length in 10..12) return "+$cleaned"
        if (cleaned.startsWith("959") && cleaned.length >= 11) return "+$cleaned"
        return cleaned
    }

    private fun deleteContact(contact: Contact) {
        val snapshot = com.example.voicebrainlive.commands.QuickContactStore.snapshot(prefs, contact.name)
        if (snapshot.keys.size != 1 || snapshot.keys[contact.name] != contact.phone ||
            !com.example.voicebrainlive.commands.QuickContactStore.compareAndWrite(prefs, snapshot) { remove(contact.name) }) {
            Toast.makeText(this, "Contact changed or ambiguous. Review it again.", Toast.LENGTH_LONG).show()
            loadContacts()
            return
        }
        loadContacts()
        Toast.makeText(this, "Deleted: ${contact.name}", Toast.LENGTH_SHORT).show()
    }

    private fun launchContactPicker(targetField: EditText) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingPhoneField = targetField
            requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        pendingPhoneField = targetField
        pickContactLauncher.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }

    private fun showEditDialog(contact: Contact) {
        val editLayout = layoutInflater.inflate(R.layout.dialog_edit_contact, null)
        val etName = editLayout.findViewById<EditText>(R.id.etEditName)
        val etPhone = editLayout.findViewById<EditText>(R.id.etEditPhone)
        etName.setText(contact.name)
        etPhone.setText(contact.phone)
        etPhone.setOnClickListener { launchContactPicker(etPhone) }

        AlertDialog.Builder(this)
            .setTitle("Edit Contact")
            .setView(editLayout)
            .setPositiveButton("Save") { _, _ ->
                val newName = etName.text.toString().trim()
                val newPhone = etPhone.text.toString().trim()
                if (newName.isBlank()) {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newPhone.isBlank()) {
                    Toast.makeText(this, "Phone cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                updateContact(contact, newName, newPhone)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateContact(oldContact: Contact, newName: String, newPhone: String) {
        val cleanName = newName.trim()
        val cleanPhone = normalizePhoneForStorage(newPhone)
        synchronized(com.example.voicebrainlive.commands.QuickContactStore.lock) {
            val snapshot = com.example.voicebrainlive.commands.QuickContactStore.snapshot(prefs, oldContact.name)
            val destination = com.example.voicebrainlive.commands.QuickContactStore.matching(prefs, cleanName)
            if (snapshot.keys.size != 1 || snapshot.keys[oldContact.name] != oldContact.phone ||
                destination.keys.any { it != oldContact.name } ||
                !com.example.voicebrainlive.commands.QuickContactStore.compareAndWrite(prefs, snapshot) {
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

    private fun callContact(contact: Contact) {
        val phoneNumber = contact.phone.replace(Regex("[^0-9+]") ,"")
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

    private val approvalSession = java.util.UUID.randomUUID().toString()

    private fun launchCall(contact: Contact) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val output = com.example.voicebrainlive.commands.SensitiveActionGuard.run(
                this@ContactsActivity, approvalSession, "make_call", contact.name, null
            ) { "BLOCKED: Legacy manual call disabled." }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(this@ContactsActivity, output, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.voicebrainlive.commands.ApprovalUiLease.resume(this)
    }

    override fun onPause() {
        com.example.voicebrainlive.commands.ApprovalUiLease.pause(this)
        super.onPause()
    }

    override fun onDestroy() {
        com.example.voicebrainlive.commands.SensitiveActionGuard.invalidate(approvalSession)
        super.onDestroy()
    }

    private fun updateEmptyState() {
        if (contacts.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvContacts.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvContacts.visibility = View.VISIBLE
        }
    }

    class ContactsAdapter(
        private val contacts: List<Contact>,
        private val onCall: (Contact) -> Unit,
        private val onEdit: (Contact) -> Unit,
        private val onDelete: (Contact) -> Unit
    ) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvPhone: TextView = view.findViewById(R.id.tvPhone)
            val btnCall: ImageButton = view.findViewById(R.id.btnCall)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = contacts[position]
            holder.tvName.text = "${position + 1}. ${contact.name.replaceFirstChar { it.uppercase() }}"
            holder.tvPhone.text = contact.phone
            holder.btnCall.setOnClickListener { onCall(contact) }
            holder.btnEdit.setOnClickListener { onEdit(contact) }
            holder.btnDelete.setOnClickListener { onDelete(contact) }
        }

        override fun getItemCount() = contacts.size
    }
}
