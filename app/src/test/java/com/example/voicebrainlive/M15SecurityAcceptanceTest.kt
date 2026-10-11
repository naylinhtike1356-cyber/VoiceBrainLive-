package com.example.voicebrainlive

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import com.example.voicebrainlive.commands.ActionTargetResolver
import com.example.voicebrainlive.commands.CommandSafetyPolicy
import com.example.voicebrainlive.commands.PhoneCommandExecutor
import com.example.voicebrainlive.commands.SensitiveActionGuard
import com.example.voicebrainlive.commands.handlers.CallCommands
import com.example.voicebrainlive.commands.handlers.ToolRegistryImpl
import com.example.voicebrainlive.memory.BehaviorMemory
import com.example.voicebrainlive.messenger.UnifiedNotificationListenerService
import com.example.voicebrainlive.usecases.AutomationCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Independent acceptance contracts. Known security failures intentionally remain visible.
 * Robolectric-only contacts, telecom, preferences and reply tokens. No device or network effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class M15SecurityAcceptanceTest {
    class FakeContacts : ContentProvider() {
        companion object { var beforeQuery: (() -> Unit)? = null }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(projection ?: emptyArray()).apply {
                beforeQuery?.invoke()
                addRow((projection ?: emptyArray()).map { column ->
                    when (column) {
                        ContactsContract.CommonDataKinds.Phone.NUMBER -> "01012345678"
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID -> 42L
                        else -> "Alice"
                    }
                })
            }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test fun searchPhoneContacts_directRegistryExecutorAutomationMacro_mustNotCallWithoutApproval() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SensitiveActionGuard.uiVisible = false
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        Robolectric.setupContentProvider(FakeContacts::class.java, ContactsContract.AUTHORITY)
        val telecom = shadowOf(app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
        telecom.setCallPhonePermission(true)
        SettingsActivity.getSecurePrefs(app).edit().putBoolean(SettingsActivity.KEY_TEST_CALL_MODE, false).commit()
        BehaviorMemory.recordCall(app, "Alice") // A historical call is context, never current consent.
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val before = telecom.allOutgoingCalls.size
        app.getSharedPreferences("custom_macros", Context.MODE_PRIVATE).edit()
            .putString("m15-contact-search", "search_phone_contacts:Alice").commit()
        val outputs = listOf(
            CallCommands(app).execute("search_phone_contacts", "Alice", null),
            ToolRegistryImpl(app, prefs).executeCommandAsync("search_phone_contacts", "Alice", null),
            PhoneCommandExecutor(app).executeAsync("search_phone_contacts", "Alice", null),
            AutomationCommandRunner(app).run("command", "search_phone_contacts:Alice"),
            PhoneCommandExecutor(app).executeAsync("run_macro", "m15-contact-search", null)
        )
        assertEquals("Fixture must reach all five calling paths", 5, outputs.count { !it.isNullOrBlank() })
        assertEquals("No approval was requested; fake telecom recorded unauthorized calls", before, telecom.allOutgoingCalls.size)
    }

    @Test @Config(sdk = [35])
    fun mutableReplyToken_updatedDestinationMustInvalidateReviewedTarget() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildService(UnifiedNotificationListenerService::class.java).create()
        val service = controller.get()
        service.onListenerConnected()
        val notifications = shadowOf(service as android.service.notification.NotificationListenerService)
        fun token(conversation: String) = PendingIntent.getBroadcast(app, 71015,
            Intent("m15.fake.reply").setPackage(app.packageName).putExtra("conversation_id", conversation),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            val original = token("Alice-conversation")
            val action = Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Reply", original)
                .addRemoteInput(RemoteInput.Builder("reply").build()).build()
            val notification = Notification.Builder(app, "fake-only").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Alice").setContentText("incoming").addAction(action).build()
            notifications.addActiveNotification(StatusBarNotification(app.packageName, app.packageName, 71015,
                null, 0, 0, 0, notification, android.os.Process.myUserHandle(), 100L))
            val reviewed = UnifiedNotificationListenerService.resolveExactReply("Alice", app.packageName)!!
            val updated = token("Bob-conversation")
            assertEquals("UPDATE_CURRENT retains token identity", original, updated)
            assertEquals("Bob-conversation", shadowOf(original).savedIntent.getStringExtra("conversation_id"))
            assertFalse("Changed effective recipient must require fresh approval",
                UnifiedNotificationListenerService.isExactReplyCurrent(reviewed))
        } finally {
            service.cancelAllNotifications()
            service.onListenerDisconnected()
            controller.destroy()
        }
    }

    @Test fun quickContact_newCaseVariantMustInvalidatePreparedAddition() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val prepared = ActionTargetResolver.prepare(app, "add_contact", "alice", "01012345678")!!
        prefs.edit().putString("Alice", "01022223333").commit() // Isolated manual-writer simulation.
        assertFalse("Target is now ambiguous despite the lower-case key remaining absent", prepared.isCurrent())
    }

    @Test fun disconnectDuringTargetResolution_mustNotRegisterNewPendingApproval() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val session = "m15-disconnected-session"
        SensitiveActionGuard.uiVisible = false
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
        app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE).edit().clear().commit()
        Robolectric.setupContentProvider(FakeContacts::class.java, ContactsContract.AUTHORITY)
        FakeContacts.beforeQuery = { SensitiveActionGuard.invalidate(session) }
        var id: String? = null
        try {
            val result = SensitiveActionGuard.run(app, session, "send_sms", "Alice", "exact fixture body") {
                error("Sensitive legacy callback must never execute")
            }
            if (result.startsWith("NEEDS_APPROVAL:")) {
                id = result.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
            }
            assertTrue("A disconnected session must not acquire a new approval after resolution: $result",
                result.startsWith("BLOCKED"))
        } finally {
            FakeContacts.beforeQuery = null
            id?.let { SensitiveActionGuard.cancel(app, it) }
            SensitiveActionGuard.invalidate(session)
        }
    }

    @Test fun safeMacroCreation_canOverwriteExistingDefinition_withoutApproval() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)
        prefs.edit().putString("existing", "get_time").commit()
        assertEquals(CommandSafetyPolicy.Classification.SAFE, CommandSafetyPolicy.classify("create_macro"))
        val result = PhoneCommandExecutor(app).executeAsync("create_macro", "existing", "get_date")
        assertFalse(result.startsWith("NEEDS_APPROVAL"))
        assertEquals("get_date", prefs.getString("existing", null))
        // Characterization only: this PASS documents a mutation, not approval of the policy.
    }
}
