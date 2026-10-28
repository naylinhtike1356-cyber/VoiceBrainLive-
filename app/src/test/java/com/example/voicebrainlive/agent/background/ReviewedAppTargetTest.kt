package com.example.voicebrainlive.agent.background

import android.app.Activity
import android.content.Intent
import android.content.pm.*
import com.example.voicebrainlive.commands.ReviewedAppTarget
import com.example.voicebrainlive.commands.handlers.ToolRegistryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ScheduledRequestTestApp::class)
class ReviewedAppTargetTest {
    @Suppress("DEPRECATION") private fun install(a:Activity,pkg:String="com.example.exactplayer"){
        val signing=SigningInfo().apply{Shadows.shadowOf(this).setSignatures(arrayOf(Signature(byteArrayOf(1,2,3))))}
        val info=PackageInfo().apply{packageName=pkg;signatures=arrayOf(Signature(byteArrayOf(1,2,3)));signingInfo=signing;applicationInfo=ApplicationInfo().apply{packageName=pkg;enabled=true}}
        Shadows.shadowOf(a.packageManager).installPackage(info)
        val resolve=ResolveInfo().apply{activityInfo=ActivityInfo().apply{packageName=pkg;name="$pkg.Main";applicationInfo=info.applicationInfo;enabled=true;exported=true}}
        Shadows.shadowOf(a.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),resolve)
    }
    @Test fun exactOpenUsesCommonGuardAndOnlySelectedPackage()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true);val a=c.get();install(a)
        val pkg="com.example.exactplayer";val pin=ReviewedAppTarget.certificate(a,pkg)
        val registry=ToolRegistryImpl(a,a.getSharedPreferences("host_test_only",0))
        val result=registry.executeCommandAsync("open_app",pkg,ReviewedAppTarget.PREFIX+pin)
        assertEquals("SUBMITTED:APP_LAUNCH",result)
        assertEquals(pkg,Shadows.shadowOf(a).nextStartedActivity.component!!.packageName)
        c.pause().stop().destroy()
    }
    @Test fun changedSigningKeyBlocksWithoutGuessingAnotherApp()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true);val a=c.get();install(a)
        val registry=ToolRegistryImpl(a,a.getSharedPreferences("host_test_only",0))
        assertTrue(registry.executeCommandAsync("open_app","com.example.exactplayer",ReviewedAppTarget.PREFIX+"f".repeat(64))!!.startsWith("BLOCKED:"))
        assertNull(Shadows.shadowOf(a).nextStartedActivity);c.pause().stop().destroy()
    }
    @Test fun backgroundContextCannotLaunchOrPlayFromStoredRequest()=runBlocking<Unit>{
        val app=RuntimeEnvironment.getApplication()
        for(cmd in listOf("open_app","resume_music"))assertEquals("BLOCKED:FOREGROUND_REQUIRED",ReviewedAppTarget.dispatch(app,cmd,"com.example.player",ReviewedAppTarget.PREFIX+"a".repeat(64)))
    }
    @Test fun missingExactMediaSessionNeverFallsBackOrOpensAnApp()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true);val a=c.get();install(a)
        val pkg="com.example.exactplayer";val pin=ReviewedAppTarget.certificate(a,pkg)
        val registry=ToolRegistryImpl(a,a.getSharedPreferences("host_test_only",0))
        assertTrue(registry.executeCommandAsync("resume_music",pkg,ReviewedAppTarget.PREFIX+pin)!!.startsWith("BLOCKED:"))
        assertNull(Shadows.shadowOf(a).nextStartedActivity);c.pause().stop().destroy()
    }
    @Test fun unknownTargetAndIntentPayloadNeverDispatch()=runBlocking<Unit>{
        val c=Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true);val a=c.get()
        for(pkg in listOf("intent://arbitrary","com.example.notinstalled"))assertTrue(ReviewedAppTarget.dispatch(a,"open_app",pkg,ReviewedAppTarget.PREFIX+"a".repeat(64)).startsWith("BLOCKED:"))
        assertNull(Shadows.shadowOf(a).nextStartedActivity);c.pause().stop().destroy()
    }
}
