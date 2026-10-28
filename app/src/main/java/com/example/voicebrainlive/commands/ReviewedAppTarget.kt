package com.example.voicebrainlive.commands

import android.content.*
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Build
import com.example.voicebrainlive.MediaSessionAccessService
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact target option of existing SAFE app/media tools. Never searches another app or session. */
internal object ReviewedAppTarget {
    const val PREFIX="reviewed-package:"
    @Suppress("DEPRECATION")
    fun certificate(context:Context,pkg:String):String {
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        val info=context.packageManager.getPackageInfo(pkg,if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val signatures=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        require(signatures?.size==1)
        return MessageDigest.getInstance("SHA-256").digest(signatures!![0].toByteArray()).joinToString(""){"%02x".format(it)}
    }
    suspend fun dispatch(context:Context,command:String,pkg:String?,value:String):String=withContext(Dispatchers.Main.immediate){
        try{
            val activity=context as? android.app.Activity?:return@withContext "BLOCKED:FOREGROUND_REQUIRED"
            require(!activity.isFinishing&&!activity.isDestroyed&&activity.hasWindowFocus())
            val exact=requireNotNull(pkg);val cert=value.removePrefix(PREFIX)
            require(value.startsWith(PREFIX)&&cert.matches(Regex("[a-f0-9]{64}"))&&certificate(context,exact)==cert)
            when(command){
                "open_app"->{
                    val intent=context.packageManager.getLaunchIntentForPackage(exact)?:return@withContext "BLOCKED:APP_UNAVAILABLE"
                    require(intent.component?.packageName==exact)
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "SUBMITTED:APP_LAUNCH" // Android accepted a launch; no proof of app's inner workflow
                }
                "resume_music"->{
                    val manager=context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager?:return@withContext "BLOCKED:MEDIA_UNAVAILABLE"
                    val controllers=manager.getActiveSessions(ComponentName(context,MediaSessionAccessService::class.java)).filter{it.packageName==exact}
                    if(controllers.size!=1)return@withContext "BLOCKED:EXACT_MEDIA_SESSION_REQUIRED"
                    controllers.single().transportControls.play()
                    "SUBMITTED:MEDIA_PLAY" // delivery of a command, not evidence of audible playback
                }
                else->"BLOCKED:UNSUPPORTED_ACTION"
            }
        }catch(_:Exception){"BLOCKED:TARGET_OR_PERMISSION_UNAVAILABLE"}
    }
}
