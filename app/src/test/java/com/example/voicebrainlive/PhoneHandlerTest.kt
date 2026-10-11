package com.example.voicebrainlive

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import com.example.voicebrainlive.commands.handlers.PhoneHandler
import com.example.voicebrainlive.memory.BehaviorMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.example.voicebrainlive.commands.SensitiveActionGuard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhoneHandlerTest {

    @Test
    fun makeCall_rawKoreanNumber_requiresApprovalForNormalizedNumber() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val handler = PhoneHandler(app, prefs)
        val result = handler.execute("make_call", "010-1234-5678", null)

        val started = shadowOf(app).nextStartedActivity
        assertNull(started)
        assertTrue(result.startsWith("NEEDS_APPROVAL"))
        val id = result.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        assertEquals("tel:01012345678", SensitiveActionGuard.store.pending(id)!!.snapshot.targetId)
        SensitiveActionGuard.cancel(app, id)
    }

    @Test
    fun makeCall_quickContactName_requiresApprovalWithExactContact() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        BehaviorMemory.clearAll(app)
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("alice", "01022223333").commit()

        val handler = PhoneHandler(app, prefs)
        val result = handler.execute("make_call", "alice", null)

        val started = shadowOf(app).nextStartedActivity
        assertNull(started)
        assertTrue(result.startsWith("NEEDS_APPROVAL"))
        val id = result.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        assertEquals("quick:alice:01022223333", SensitiveActionGuard.store.pending(id)!!.snapshot.targetId)
        SensitiveActionGuard.cancel(app, id)
    }

    @Test
    fun makeCall_rememberedAlias_resolvesWithoutGrantingApproval() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        BehaviorMemory.clearAll(app)
        val prefs = app.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("alice", "01022223333").commit()
        BehaviorMemory.rememberCallAlias(
            context = app,
            spokenInput = "boss",
            resolvedTarget = "alice",
            confidencePercent = 100,
            confirmedByUser = true
        )

        val handler = PhoneHandler(app, prefs)
        val result = handler.execute("make_call", "boss", null)

        val started = shadowOf(app).nextStartedActivity
        assertNull(started)
        assertTrue(result.startsWith("NEEDS_APPROVAL"))
        val id = result.substringAfter("NEEDS_APPROVAL: ").substringBefore('.')
        assertEquals("quick:alice:01022223333", SensitiveActionGuard.store.pending(id)!!.snapshot.targetId)
        SensitiveActionGuard.cancel(app, id)
    }
}
