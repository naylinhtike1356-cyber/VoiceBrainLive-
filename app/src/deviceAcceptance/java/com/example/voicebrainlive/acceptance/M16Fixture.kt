package com.example.voicebrainlive.acceptance

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.voicebrainlive.commands.ActionSnapshot
import com.example.voicebrainlive.commands.SensitiveActionGuard
import com.example.voicebrainlive.routines.AutomationApprovalActivity
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Exists only in .m16fixture. No Android effects, credentials, production preferences or network. */
object M16Fixture {
    const val PACKAGE = "com.example.voicebrainlive.m16fixture"
    const val EXACT = "  မြန်မာ။\n한국어!\r\nEnglish: MiXeD, punctuation; 🙂  "
    val effects = AtomicInteger()
    val imageEffects = AtomicInteger()
    data class Record(val id: String, val session: String, val snapshot: ActionSnapshot,
        val elapsed: Long, val uptime: Long, @Volatile var target: String, @Volatile var payload: String)
    var last: Record? = null
        private set
    var expiry: Record? = null
        private set

    fun requireIsolation(context: Context) {
        check(context.packageName == PACKAGE) { "Fixture must never run in production package" }
        val info = context.packageManager.getPackageInfo(PACKAGE, android.content.pm.PackageManager.GET_PERMISSIONS)
        val forbidden = setOf("android.permission.INTERNET", "android.permission.CALL_PHONE", "android.permission.SEND_SMS", "android.permission.READ_CONTACTS", "android.permission.CAMERA")
        check(info.requestedPermissions.orEmpty().none { it in forbidden }) { "Unsafe fixture manifest" }
    }

    fun prepare(context: Context, text: String = EXACT): Record {
        requireIsolation(context)
        val session = UUID.randomUUID().toString()
        val snapshot = ActionSnapshot("make_call", "Fixture Alice — no real recipient", "LOCAL COUNTER — no phone call",
            "fixture:alice:${UUID.randomUUID()}", text, "Increment one harmless process-local counter only")
        val record: Record
        // Fixture-only injection keeps the production guard, preview Activity and biometric callback intact.
        // Exact resolver/real Contacts/CameraX/VoiceLive integration needs separate evidence.
        synchronized(SensitiveActionGuard) {
            val pending = SensitiveActionGuard.store.request(session, snapshot)
            record = Record(pending.id,session,snapshot,SystemClock.elapsedRealtime(),SystemClock.uptimeMillis(),snapshot.targetId,text)
            val operation = SensitiveActionGuard.Operation(snapshot,
                { record.target == snapshot.targetId && record.payload == snapshot.payload }, {
                    requireIsolation(context)
                    effects.incrementAndGet()
                    event(context,"LOCAL_EFFECT_ONCE",record)
                    "LOCAL fixture counter incremented; no call/message/network effect."
                })
            val field = SensitiveActionGuard::class.java.getDeclaredField("operations").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val operations = field.get(SensitiveActionGuard) as MutableMap<String,SensitiveActionGuard.Operation>
            operations[record.id] = operation
        }
        last = record
        event(context,"PREPARED",record)
        return record
    }

    fun preview(context: Context, record: Record) {
        requireIsolation(context)
        context.startActivity(Intent(context,AutomationApprovalActivity::class.java)
            .putExtra(AutomationApprovalActivity.EXTRA_APPROVAL_ID,record.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun prepareExpiry(context: Context) {
        expiry = prepare(context)
        event(context,"EXPIRY_BEGIN_NO_CLOCK_SIMULATION",expiry)
    }

    fun expiryEvidence(context: Context): String {
        val r = expiry ?: return "BLOCKED: No expiry record in this process; prepare a new one."
        val elapsed = SystemClock.elapsedRealtime()-r.elapsed
        val awake = SystemClock.uptimeMillis()-r.uptime
        val result = "elapsedMs=$elapsed; uptimeMs=$awake; sleepDeltaMs=${elapsed-awake}; pending=${SensitiveActionGuard.store.pending(r.id)!=null}"
        event(context,"EXPIRY_OBSERVATION",r,result)
        // No automatic physical PASS: user must document real screen-off/deep-sleep and process continuity.
        return result
    }

    @Synchronized fun event(context: Context, name: String, record: Record? = null, detail: String = "") {
        requireIsolation(context)
        val json = JSONObject().put("event",name).put("elapsedRealtime",SystemClock.elapsedRealtime())
            .put("uptime",SystemClock.uptimeMillis()).put("effects",effects.get()).put("imageEffects",imageEffects.get())
            .put("approvalId",record?.id.orEmpty()).put("detail",detail)
        java.io.File(context.filesDir,"m16-events.jsonl").appendText(json.toString()+"\n",Charsets.UTF_8)
    }
}
