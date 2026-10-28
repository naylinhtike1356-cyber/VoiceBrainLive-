package com.example.voicebrainlive.agent.notion

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.voicebrainlive.VoiceBrainApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

class DirectProfilesUiApplication : VoiceBrainApplication() {
    override val directNotionProfiles get() = fake
    override fun onCreate() { /* Local fake snapshot only. No native keys, workers or providers. */ }
    companion object { lateinit var fake: DirectNotionProfiles }
}
private class ControlledProfiles(private val delegate: DirectNotionProfiles) : DirectNotionProfiles by delegate {
    var gate: CompletableDeferred<Unit>? = null
    var unreadable = false
    var created = 0; var verified = 0; var selected = 0; var revoked = 0
    override suspend fun views(): ProfileVaultView {
        gate?.await()
        if (unreadable) throw ProfileStoreException(ProfileFailure.DATA_UNREADABLE)
        return delegate.views()
    }
    override suspend fun createSyntheticDraft(name: String) = delegate.createSyntheticDraft(name).also { created++ }
    override suspend fun verifySynthetic(id: String, revision: Long) = delegate.verifySynthetic(id, revision).also { verified++ }
    override suspend fun select(id: String, revision: Long) = delegate.select(id, revision).also { selected++ }
    override suspend fun revoke(id: String, revision: Long) = delegate.revoke(id, revision).also { revoked++ }
}

/** Prepared only until adopted and executed against current source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = DirectProfilesUiApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NotionProfilesUiTest {
    private lateinit var fixture: NotionFixture
    private lateinit var api: ControlledProfiles
    @Before fun setup() {
        fixture = NotionFixture()
        api = ControlledProfiles(RepositoryDirectNotionProfiles(fixture.repo))
        DirectProfilesUiApplication.fake = api
    }
    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun open() = Robolectric.buildActivity(NotionProfilesActivity::class.java).setup().visible().also { idle() }
    private fun button(a: NotionProfilesActivity, tag: String) = a.window.decorView.findViewWithTag<Button>(tag)!!
    private fun Button.click() { assertTrue(isEnabled); performClick(); idle() }
    private fun create(a: NotionProfilesActivity, name: String): ConnectionProfileView {
        a.window.decorView.findViewWithTag<EditText>("notion_name").setText(name)
        button(a, "notion_create").click()
        return runBlocking { fixture.repo.views().profiles.last() }
    }
    private fun latest() = (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    private fun text(v: View): String = (if (v is TextView) v.text.toString() else "") +
        if (v is ViewGroup) (0 until v.childCount).joinToString("\n") { text(v.getChildAt(it)) } else ""

    @Test fun multipleNamedDraftsHaveSeparateResourcesAndRecreationDoesNotVerifyOrSelect() {
        val c = open(); val a = c.get()
        val first = create(a, "မြန်မာ 한국어 personal")
        val second = create(a, "Study")
        assertNotEquals(first.profileId, second.profileId)
        assertNotEquals(first.resource, second.resource)
        assertEquals(DirectProfileStatus.DRAFT, first.status)
        assertEquals(2, api.created); assertEquals(0, api.verified); assertEquals(0, api.selected)
        c.recreate(); idle()
        assertTrue(text(c.get().window.decorView).contains("REAL ACCOUNT NOT CONNECTED"))
        assertEquals(2, api.created); assertEquals(0, api.verified)
        c.pause().stop().destroy()
    }
    @Test fun exactFixtureReviewIsRequiredAndConsumedOnceWithSyntheticLabel() {
        val c = open(); val p = create(c.get(), "Study")
        button(c.get(), "notion_review_${p.profileId}").click()
        assertEquals(0, api.verified)
        val positive = latest().getButton(AlertDialog.BUTTON_POSITIVE)
        positive.click(); positive.performClick(); idle()
        assertEquals(1, api.verified)
        val saved = runBlocking { fixture.repo.views().profiles.single() }
        assertEquals(DirectProfileStatus.SYNTHETIC_VERIFIED, saved.status)
        assertTrue(text(c.get().window.decorView).contains("SYNTHETIC_FIXTURE"))
        assertEquals(0, api.selected)
        c.pause().stop().destroy()
    }
    @Test fun hiddenOldDialogCannotVerifyAfterAnotherViewLoads() {
        val old = open(); val p = create(old.get(), "Study")
        button(old.get(), "notion_review_${p.profileId}").click()
        val positive = latest().getButton(AlertDialog.BUTTON_POSITIVE)
        old.pause().stop()
        val fresh = open(); positive.performClick(); idle()
        assertEquals(0, api.verified)
        fresh.pause().stop().destroy(); old.destroy()
    }
    @Test fun unreadableStoreDisablesWritesAndDoesNotPretendToBeEmpty() {
        api.unreadable = true
        val c = open()
        assertFalse(button(c.get(), "notion_create").isEnabled)
        assertTrue(button(c.get(), "notion_refresh").isEnabled)
        assertTrue(text(c.get().window.decorView).contains("not an empty writable vault"))
        assertEquals(0, api.created)
        c.pause().stop().destroy()
    }
    @Test fun refreshInFlightDisablesRowActionsAndDropsHiddenCompletion() {
        val c = open(); val p = create(c.get(), "Study")
        val pending = CompletableDeferred<Unit>(); api.gate = pending
        button(c.get(), "notion_refresh").click()
        assertFalse(button(c.get(), "notion_select_${p.profileId}").isEnabled)
        button(c.get(), "notion_select_${p.profileId}").performClick(); idle()
        assertEquals(0, api.selected)
        c.pause().stop(); pending.complete(Unit); idle(); api.gate = null
        c.start().resume().visible(); idle()
        assertTrue(button(c.get(), "notion_select_${p.profileId}").isEnabled)
        assertEquals(0, api.selected)
        c.pause().stop().destroy()
    }
    @Test fun confirmedRevocationClearsFutureSelectionWithoutProviderDeletion() {
        val c = open(); val p = create(c.get(), "Study")
        button(c.get(), "notion_select_${p.profileId}").click()
        assertEquals(p.profileId, runBlocking { fixture.repo.views().selectedProfileId })
        button(c.get(), "notion_revoke_${p.profileId}").click()
        assertEquals(0, api.revoked)
        latest().getButton(AlertDialog.BUTTON_POSITIVE).click()
        assertEquals(1, api.revoked)
        val state = runBlocking { fixture.repo.views() }
        assertNull(state.selectedProfileId); assertEquals(DirectProfileStatus.REVOKED, state.profiles.single().status)
        assertEquals(1, state.credentials.size)
        c.pause().stop().destroy()
    }
}
