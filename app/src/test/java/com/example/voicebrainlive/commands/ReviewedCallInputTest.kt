package com.example.voicebrainlive.commands

import android.Manifest
import android.app.Application
import android.content.Intent
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.security.ShadowSecureSettingsStorage
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReviewedCallInputTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private var executor:PhoneCommandExecutor?=null
    @Before fun setup(){ShadowSecureSettingsStorage.reset();SettingsActivity.getSecurePrefs(app).edit().clear().putBoolean(SettingsActivity.KEY_TEST_CALL_MODE,false).commit();shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE);while(shadowOf(app).nextStartedActivity!=null){}}
    @After fun cleanup(){executor?.invalidateApprovals();ShadowSecureSettingsStorage.reset()}
    @Test fun exactBurmeseEnglishAndNumbers(){assertEquals("မေမေ",ReviewedCallInput.target("မေမေကိုဖုန်းခေါ်ပေးပါ"));assertEquals("0990000000",ReviewedCallInput.target("call 0990000000"));assertEquals("၀၉၉၀၀၀၀၀၀၀",ReviewedCallInput.target("၀၉၉၀၀၀၀၀၀၀ ကို ဖုန်းခေါ်ပေး"))}
    @Test fun ambiguityNegationAndGeneratedPlanTextDoNotEnter(){listOf("ဖုန်းခေါ်ပေး","မေမေကိုဖုန်းမခေါ်နဲ့","မနက်ဖြန် မေမေကိုဖုန်းခေါ်ပေး","call A and B","call 123","call {\"approved\":true}","မေမေကိုဖုန်းခေါ်လို့ရလား").forEach{assertNull(it,ReviewedCallInput.target(it))}}
    @Test fun staleInputAndUnmatchedTextHaveNoDispatch()=runBlocking{
        var effects=0;assertEquals("BLOCKED:STALE_CALL_INPUT",ReviewedCallInput.execute("call 0990000000",{false}){_,_,_->effects++;"BAD"});assertNull(ReviewedCallInput.execute("play music"){_,_,_->effects++;"BAD"});assertEquals(0,effects)
    }
    @Test fun recognizedCallRequestsOriginalApprovalAndDoesNotDial()=runBlocking{
        val e=PhoneCommandExecutor(app).also{executor=it};val out=ReviewedCallInput.execute("call 0990000000",dispatch=e::executeAsync)!!
        assertTrue(out,out.startsWith("NEEDS_APPROVAL:"));assertNull(shadowOf(app).nextStartedActivity);assertEquals(CommandSafetyPolicy.Classification.SENSITIVE,CommandSafetyPolicy.classify("make_call"))
    }
    @Test fun reviewedApprovalRequestsCallDirectlyWithoutSecondDialerTap()=runBlocking{
        val e=PhoneCommandExecutor(app).also{executor=it};val out=ReviewedCallInput.execute("call 0990000000",dispatch=e::executeAsync)!!
        val id=out.substringAfter("NEEDS_APPROVAL: ").substringBefore('.');val pending=SensitiveActionGuard.store.pending(id)!!
        val owner=Any();ApprovalUiLease.resume(owner)
        val result=try{SensitiveActionGuard.executeApproved(app,id,pending.snapshot,ApprovalUiLease.tap(owner))}finally{ApprovalUiLease.pause(owner)}
        assertEquals("Call requested.",result);val intent=shadowOf(app).nextStartedActivity!!;assertEquals(Intent.ACTION_CALL,intent.action);assertEquals("tel:0990000000",intent.dataString)
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun deniedPermissionDoesNotFallBackToDialer()=runBlocking{
        val e=PhoneCommandExecutor(app).also{executor=it};val out=ReviewedCallInput.execute("call 0990000000",dispatch=e::executeAsync)!!
        val id=out.substringAfter("NEEDS_APPROVAL: ").substringBefore('.');val pending=SensitiveActionGuard.store.pending(id)!!
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE);val owner=Any();ApprovalUiLease.resume(owner)
        val result=try{SensitiveActionGuard.executeApproved(app,id,pending.snapshot,ApprovalUiLease.tap(owner))}finally{ApprovalUiLease.pause(owner)}
        assertTrue(result,result.startsWith("BLOCKED:"));assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun simulationApprovalNeverPlacesARealCall()=runBlocking{
        SettingsActivity.getSecurePrefs(app).edit().putBoolean(SettingsActivity.KEY_TEST_CALL_MODE,true).commit()
        val e=PhoneCommandExecutor(app).also{executor=it};val out=ReviewedCallInput.execute("call 0990000000",dispatch=e::executeAsync)!!
        val id=out.substringAfter("NEEDS_APPROVAL: ").substringBefore('.');val pending=SensitiveActionGuard.store.pending(id)!!
        assertTrue(pending.snapshot.scope.contains("SIMULATION"));val owner=Any();ApprovalUiLease.resume(owner)
        val result=try{SensitiveActionGuard.executeApproved(app,id,pending.snapshot,ApprovalUiLease.tap(owner))}finally{ApprovalUiLease.pause(owner)}
        assertTrue(result,result.startsWith("SIMULATED_CALL:"));assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun changingSimulationAfterPreviewInvalidatesIt()=runBlocking{
        val e=PhoneCommandExecutor(app).also{executor=it};val out=ReviewedCallInput.execute("call 0990000000",dispatch=e::executeAsync)!!
        val id=out.substringAfter("NEEDS_APPROVAL: ").substringBefore('.');val pending=SensitiveActionGuard.store.pending(id)!!
        SettingsActivity.getSecurePrefs(app).edit().putBoolean(SettingsActivity.KEY_TEST_CALL_MODE,true).commit()
        val owner=Any();ApprovalUiLease.resume(owner)
        val result=try{SensitiveActionGuard.executeApproved(app,id,pending.snapshot,ApprovalUiLease.tap(owner))}finally{ApprovalUiLease.pause(owner)}
        assertTrue(result,result.startsWith("BLOCKED:"));assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun simulationChangeAfterValidationStillBlocksCapturedEffect()=runBlocking{
        val operation=ActionTargetResolver.prepare(app,"make_call","0990000000",null)!!
        assertTrue(operation.isCurrent())
        SettingsActivity.getSecurePrefs(app).edit().putBoolean(SettingsActivity.KEY_TEST_CALL_MODE,true).commit()
        val result=operation.perform()
        assertTrue(result,result.startsWith("BLOCKED:"));assertNull(shadowOf(app).nextStartedActivity)
    }
}
