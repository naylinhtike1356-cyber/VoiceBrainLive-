package com.example.voicebrainlive.commands

import android.content.Context
import android.app.KeyguardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.voicebrainlive.routines.AutomationApprovalActivity
import com.example.voicebrainlive.routines.AutomationApprovalNotification
import com.example.voicebrainlive.routines.AutomationApprovalQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

/** Common effect boundary. The AI-facing API has no approval/token/approved=true parameter. */
object SensitiveActionGuard {
    // Source compatibility only. Presentation/execution use lifecycle leases, never this flag.
    @Volatile var uiVisible = false
    val store = ActionApprovalStore()
    data class Operation(
        val snapshot: ActionSnapshot,
        val isCurrent: suspend () -> Boolean,
        val perform: suspend () -> String
    )
    private val operations = mutableMapOf<String, Operation>()
    private data class Flight(val request: ActionApprovalStore.Pending, val operation: Operation, val job: Job?, @Volatile var revoked: Boolean = false)
    private val inFlight = mutableMapOf<String, Flight>()

    suspend fun run(
        context: Context, session: String, command: String, target: String?, value: String?,
        safeDispatch: suspend () -> String
    ): String {
        coroutineContext.ensureActive()
        when (CommandSafetyPolicy.classify(command)) {
            CommandSafetyPolicy.Classification.SAFE -> return safeDispatch()
            CommandSafetyPolicy.Classification.UNKNOWN -> return "BLOCKED: Unknown/unclassified action."
            CommandSafetyPolicy.Classification.SENSITIVE -> Unit
        }
        val epoch = store.epoch(session) ?: return "BLOCKED: Session closed; request in a fresh session."
        val operation = try {
            ActionTargetResolver.prepare(context.applicationContext, command, target, value)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
            ?: return "BLOCKED: Exact target, destination or scope မရှင်းလင်းပါ။ Explicit recipient/target ID လိုအပ်သည်။"
        coroutineContext.ensureActive()
        val pending = synchronized(this) {
            val record = store.requestIfCurrent(session, operation.snapshot, epoch) ?: return@synchronized null
            operations.entries.removeAll { store.pending(it.key) == null }
            operations[record.id] = operation
            record
        }
        if (pending == null) return "BLOCKED: Session invalidated during target preparation."
        val item = AutomationApprovalQueue.enqueue(
            context, command, target, value, "Explicit tap + biometric required",
            pending.id, operation.snapshot.preview()
        )
        val launchingOwner = ApprovalUiLease.resumedLease()
        // Foreground UI only. Background/locked work remains pending via notification.
        Handler(Looper.getMainLooper()).post {
            val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true
            if (!locked && ApprovalUiLease.current(launchingOwner) && store.pending(pending.id) != null) {
                try {
                    context.startActivity(Intent(context, AutomationApprovalActivity::class.java)
                        .putExtra(AutomationApprovalActivity.EXTRA_APPROVAL_ID, item.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { /* pending notification remains the entry point */ }
            }
        }
        return "NEEDS_APPROVAL: ${pending.id}. Preview ကိုကြည့်ပြီး explicit tap ဖြင့် အတည်ပြုပါ။"
    }

    internal suspend fun executeApproved(context: Context, id: String, shown: ActionSnapshot, lease: ApprovalUiLease.Lease? = null): String {
        coroutineContext.ensureActive()
        if (!ApprovalUiLease.currentTap(lease)) {
            cancel(context, id)
            return "BLOCKED: Approval owner paused or tap generation changed."
        }
        if ((context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true) {
            cancel(context, id)
            return "BLOCKED: Unlock the device and review the action again."
        }
        val job = coroutineContext[Job]
        val claimed = synchronized(this) {
            val pending = store.pending(id) ?: return@synchronized null
            if (!store.approve(id, shown) || !store.consume(id, pending.session, shown)) return@synchronized null
            operations.remove(id)?.let { Flight(pending, it, job).also { flight -> inFlight[id] = flight } }
        } ?: return "BLOCKED: Approval cancelled, expired, changed or already used."
        return try {
            AutomationApprovalQueue.remove(context, id)
            AutomationApprovalNotification.cancel(context, id)
            coroutineContext.ensureActive()
            if (!claimed.operation.isCurrent()) return "BLOCKED: Permission/authentication denied or target/destination/scope changed. New approval required."
            coroutineContext.ensureActive()
            if (store.isExpired(claimed.request)) return "BLOCKED: Approval expired during validation."
            if ((context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true)
                return "BLOCKED: Device locked during validation. New approval required."
            if (claimed.revoked || !store.isCurrent(claimed.request) || !ApprovalUiLease.currentTap(lease))
                return "BLOCKED: Session or approval lifecycle changed during validation."
            claimed.operation.perform()
        }
        catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { "BLOCKED: Android permission/authentication denied." }
        catch (_: Exception) { "FAILED/OUTCOME_UNKNOWN: No automatic retry; new approval required." }
        finally { synchronized(this) { inFlight.remove(id) } }
    }

    @Synchronized
    fun cancel(context: Context, id: String) {
        store.cancel(id)
        operations.remove(id)
        inFlight[id]?.let { it.revoked = true; it.job?.cancel(CancellationException("Action cancelled")) }
        AutomationApprovalQueue.remove(context, id)
        AutomationApprovalNotification.cancel(context, id)
    }

    @Synchronized
    fun invalidate(session: String) {
        val ids = operations.keys.filter { store.pending(it)?.session == session }
        store.invalidate(session)
        ids.forEach { operations.remove(it) }
        inFlight.values.filter { it.request.session == session }.forEach {
            it.revoked = true
            it.job?.cancel(CancellationException("Session invalidated"))
        }
    }
}
