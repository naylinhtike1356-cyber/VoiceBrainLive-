package com.example.voicebrainlive.acceptance

import android.app.Notification
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.voicebrainlive.commands.ApprovalUiLease
import com.example.voicebrainlive.commands.ImageShareConsent
import com.example.voicebrainlive.commands.SensitiveActionGuard
import com.example.voicebrainlive.messenger.UnifiedNotificationListenerService
import com.example.voicebrainlive.routines.AutomationApprovalActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.ViewTreeObserver
import android.view.WindowManager

/** Never run without explicit installation/execution approval. No synthetic biometric success. */
@RunWith(AndroidJUnit4::class)
class M16IsolationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun launch(record: M16Fixture.Record): ActivityScenario<AutomationApprovalActivity> {
        val scenario = ActivityScenario.launch<AutomationApprovalActivity>(
            Intent(context,AutomationApprovalActivity::class.java)
                .putExtra(AutomationApprovalActivity.EXTRA_APPROVAL_ID,record.id))
        val ready = CountDownLatch(1)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        var cleanup: (() -> Unit)? = null
        try {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                fun observe() {
                    val stage = monitor.getLifecycleStageOf(activity)
                    if (stage == Stage.RESUMED && decor.hasWindowFocus() &&
                        decor.isLaidOut && !decor.isLayoutRequested) ready.countDown()
                }
                val focus = ViewTreeObserver.OnWindowFocusChangeListener { observe() }
                val draw = ViewTreeObserver.OnPreDrawListener { observe(); true }
                val lifecycle = ActivityLifecycleCallback { current,_ -> if(current === activity) observe() }
                decor.viewTreeObserver.addOnWindowFocusChangeListener(focus)
                decor.viewTreeObserver.addOnPreDrawListener(draw)
                monitor.addLifecycleCallback(lifecycle)
                cleanup = {
                    monitor.removeLifecycleCallback(lifecycle)
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
                        decor.viewTreeObserver.removeOnPreDrawListener(draw)
                    }
                }
                assertTrue("Approval must keep FLAG_SECURE",
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                observe()
            }
            // ActivityScenario RESUMED alone is not evidence of a focused, laid-out window.
            // Observe real callbacks; never simulate focus, authenticate, or increase Espresso's timeout.
            assertTrue("Approval window must be RESUMED, focused and laid out before interaction",
                ready.await(10,TimeUnit.SECONDS))
            M16Fixture.event(context,"AUTOMATED_WINDOW_READY",record)
            return scenario
        } catch (failure: Throwable) {
            M16Fixture.event(context,"AUTOMATED_WINDOW_SETUP_FAILED",record,failure.javaClass.simpleName)
            scenario.close()
            throw failure
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                cleanup?.invoke()
            }
        }
    }

    @Test fun isolatedPackageCannotAccessProductionEffects() {
        M16Fixture.requireIsolation(context)
        assertEquals(M16Fixture.PACKAGE,context.packageName)
        M16Fixture.event(context,"AUTOMATED_ISOLATION_ASSERTIONS_PASS")
    }

    @Test fun D01_actualPreviewMatchesExactSnapshot_beforeAnyBiometric() {
        val before=M16Fixture.effects.get(); val r=M16Fixture.prepare(context)
        try { launch(r).use { onView(withText(r.snapshot.preview())).check(matches(isDisplayed())) } }
        finally { SensitiveActionGuard.invalidate(r.session) }
        assertEquals(before,M16Fixture.effects.get())
    }

    @Test fun D02_actualCancelRevokesGrantAndHasZeroEffects() {
        val before=M16Fixture.effects.get(); val r=M16Fixture.prepare(context)
        try {
            launch(r).use { onView(withText("မလုပ်ဆောင်ပါ / Cancel")).perform(click()) }
            assertNull(SensitiveActionGuard.store.pending(r.id)); assertEquals(before,M16Fixture.effects.get())
        } finally { SensitiveActionGuard.invalidate(r.session) }
    }

    @Test fun D03_actualBackRevokesGrantAndHasZeroEffects() {
        val before=M16Fixture.effects.get(); val r=M16Fixture.prepare(context)
        try {
            launch(r).use { scenario ->
                lateinit var original: AutomationApprovalActivity
                scenario.onActivity { original=it }
                val destroyed=CountDownLatch(1)
                val monitor=ActivityLifecycleMonitorRegistry.getInstance()
                val observer=ActivityLifecycleCallback { activity,stage ->
                    if(activity === original && stage == Stage.DESTROYED) destroyed.countDown()
                }
                monitor.addLifecycleCallback(observer)
                try {
                    // Closing the sole Activity is the expected denial path, not a navigation error.
                    pressBackUnconditionally()
                    assertTrue("Back must destroy the approval Activity",destroyed.await(10,TimeUnit.SECONDS))
                } finally { monitor.removeLifecycleCallback(observer) }
            }
            assertNull(SensitiveActionGuard.store.pending(r.id)); assertEquals(before,M16Fixture.effects.get())
        } finally { SensitiveActionGuard.invalidate(r.session) }
    }

    @Test fun D11_actualApprovalActivityRecreationRevokesGrant() {
        val before=M16Fixture.effects.get(); val r=M16Fixture.prepare(context)
        try {
            launch(r).use { scenario ->
                lateinit var original: AutomationApprovalActivity
                scenario.onActivity { original=it }
                val replacementDestroyed=CountDownLatch(1)
                val observer=ActivityLifecycleCallback { activity,stage ->
                    if (activity is AutomationApprovalActivity && activity !== original && stage == Stage.DESTROYED)
                        replacementDestroyed.countDown()
                }
                val monitor=ActivityLifecycleMonitorRegistry.getInstance()
                monitor.addLifecycleCallback(observer)
                try {
                    // Fail-closed recreation finishes the replacement; do not wait for it to resume.
                    scenario.onActivity { it.recreate() }
                    assertTrue("Recreated approval Activity must finish without a grant",
                        replacementDestroyed.await(10,TimeUnit.SECONDS))
                } finally { monitor.removeLifecycleCallback(observer) }
            }
            assertNull(SensitiveActionGuard.store.pending(r.id)); assertEquals(before,M16Fixture.effects.get())
        } finally { SensitiveActionGuard.invalidate(r.session) }
    }

    @Test fun D12_componentSessionDisconnectRevokesPending_withoutClaimingVoiceLiveCoverage() {
        val before=M16Fixture.effects.get(); val r=M16Fixture.prepare(context)
        SensitiveActionGuard.invalidate(r.session)
        assertNull(SensitiveActionGuard.store.pending(r.id)); assertEquals(before,M16Fixture.effects.get())
    }

    @Test fun D13_exactLongMultilingualText_isRetained_visualGlyphReviewStillRequired() {
        val r=M16Fixture.prepare(context,M16Fixture.EXACT.repeat(80))
        try { launch(r).use { onView(withText(r.snapshot.preview())).check(matches(isDisplayed())) } }
        finally { SensitiveActionGuard.invalidate(r.session) }
    }

    @Test fun D14_unknownAndUnsupportedSensitiveCommandsNeverDispatchEffects() = runBlocking {
        M16Fixture.requireIsolation(context)
        val before=M16Fixture.effects.get(); val session=java.util.UUID.randomUUID().toString()
        try {
            for(command in listOf("unclassified_fixture_action", "send_whatsapp", "take_photo")) {
                val result=SensitiveActionGuard.run(context,session,command,"Fixture Alice",M16Fixture.EXACT) {
                    error("Unapproved effect dispatch must be unreachable")
                }
                assertTrue(result.startsWith("BLOCKED"))
            }
            assertEquals(before,M16Fixture.effects.get())
        } finally { SensitiveActionGuard.invalidate(session) }
    }

    @Test fun cameraConsentComponentWithoutTap_hasZeroLocalEffects_notCameraXEvidence() {
        val owner=Any(); val before=M16Fixture.imageEffects.get()
        val consent=ImageShareConsent(M16Fixture.EXACT,owner,{ true })
        ApprovalUiLease.resume(owner)
        try { assertNull(consent.consume(owner,null)); assertEquals(before,M16Fixture.imageEffects.get()) }
        finally { ApprovalUiLease.pause(owner); consent.cancel() }
    }

    @Test fun notificationReadingSyntheticFixture_withoutListeningToPrivateNotifications() {
        M16Fixture.requireIsolation(context)
        val notification=Notification.Builder(context,"fixture-unposted")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Fixture Alice")
            .setContentText(M16Fixture.EXACT).build()
        val sbn=StatusBarNotification(context.packageName,context.packageName,99,null,Process.myUid(),Process.myPid(),0,
            notification,UserHandle.getUserHandleForUid(Process.myUid()),System.currentTimeMillis())
        assertEquals("Fixture Alice",UnifiedNotificationListenerService.extractSender(sbn))
        assertEquals(M16Fixture.EXACT.trim(),UnifiedNotificationListenerService.extractMessageText(sbn))
        assertFalse(UnifiedNotificationListenerService.trySendReply("Fixture Alice",M16Fixture.EXACT,setOf(context.packageName)))
    }
}
