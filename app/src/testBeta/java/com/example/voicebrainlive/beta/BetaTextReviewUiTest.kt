package com.example.voicebrainlive.beta

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Switch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=BetaHostTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class BetaTextReviewUiTest {
    @Before fun clock(){ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun open():org.robolectric.android.controller.ActivityController<BetaPilotActivity>{
        val c=Robolectric.buildActivity(BetaPilotActivity::class.java).setup();idle()
        c.get().findViewById<View>(android.R.id.content).findViewWithTag<View>("pilot_review_request").performClick();idle()
        return c
    }
    @Test fun requestReviewIsProtectedAndDoesNotCollectKeyOrEnableNetwork(){
        val c=open();val a=c.get();val dialog=ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        assertNull(dialog.findViewById<View>(android.R.id.content).findViewWithTag<View>("pilot_key"))
        val model=dialog.window!!.decorView.findViewWithTag<EditText>("pilot_review_model")
        val goal=dialog.window!!.decorView.findViewWithTag<EditText>("pilot_review_goal")
        assertFalse(model.isSaveEnabled);assertFalse(goal.isSaveEnabled);assertFalse(goal.isSaveFromParentEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,goal.importantForAutofill)
        assertFalse(a.findViewById<View>(android.R.id.content).findViewWithTag<Switch>("pilot_enable").isEnabled)
        assertFalse(a.packageManager.getPackageInfo(a.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        c.pause().stop().destroy()
    }
    @Test fun missingOrInvalidFieldsStayInFormAndNeverClaimConnected(){
        val c=open();val form=ShadowDialog.getLatestDialog() as AlertDialog
        val m=form.window!!.decorView.findViewWithTag<EditText>("pilot_review_model")
        val g=form.window!!.decorView.findViewWithTag<EditText>("pilot_review_goal")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(m.error);assertTrue(form.isShowing)
        m.setText("fixture-model");form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNotNull(g.error)
        m.setText("../bad");g.setText("မြန်မာ 한국어 English");form.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(form.isShowing);assertNotNull(m.error)
        c.pause().stop().destroy()
    }
    @Test fun reviewedTextPreviewDoesNotPersistAndPauseClearsForm(){
        val c=open();val a=c.get();val form=ShadowDialog.getLatestDialog() as AlertDialog
        val m=form.window!!.decorView.findViewWithTag<EditText>("pilot_review_model")
        val g=form.window!!.decorView.findViewWithTag<EditText>("pilot_review_goal")
        m.setText("fixture-model");g.setText("PRIVATE preview not transmitted")
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        val preview=ShadowDialog.getLatestDialog() as AlertDialog
        assertNotSame(form,preview);assertFalse(form.isShowing);assertTrue(preview.isShowing)
        assertEquals("",m.text.toString());assertEquals("",g.text.toString())
        assertTrue(preview.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0)
        assertFalse(a.getSharedPreferences("beta_options",0).all.values.any{it.toString().contains("PRIVATE preview")})
        assertTrue((a.application as BetaHostTestApplication).betaStore().list().isEmpty())
        c.pause();idle();assertFalse(preview.isShowing);c.stop().destroy()
    }
    @Test fun backgroundingUnreviewedFormDropsTextAndRequiresNewReview(){
        val c=open();val form=ShadowDialog.getLatestDialog() as AlertDialog
        val g=form.window!!.decorView.findViewWithTag<EditText>("pilot_review_goal")
        g.setText("temporary private text");c.pause();idle()
        assertFalse(form.isShowing);assertEquals("",g.text.toString())
        c.resume();idle()
        assertFalse(c.get().findViewById<View>(android.R.id.content).findViewWithTag<Switch>("pilot_enable").isChecked)
        c.pause().stop().destroy()
    }
}
