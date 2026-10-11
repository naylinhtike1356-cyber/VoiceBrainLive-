package com.example.voicebrainlive.acceptance

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.example.voicebrainlive.commands.ApprovalUiLease
import com.example.voicebrainlive.commands.ImageShareConsent
import com.example.voicebrainlive.commands.SensitiveActionGuard

/** Manual actor controls. Biometric decisions are exclusively the production Android prompt. */
class M16FixtureActivity : FragmentActivity() {
    private lateinit var status: TextView
    private var resumed = false
    private var imageDialog: AlertDialog? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        M16Fixture.requireIsolation(this)
        val body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        status = TextView(this).apply { text="M1.6 isolated local fixture — physical results NOT RUN" }
        body.addView(status)
        fun button(label: String, block: () -> Unit) {
            body.addView(Button(this).apply { text=label; filterTouchesWhenObscured=true; setOnClickListener { block() } })
        }
        button("D01–D06: New exact preview / real biometric") { M16Fixture.preview(this,M16Fixture.prepare(this)) }
        button("D07: Mutate target before approval") {
            val r=M16Fixture.prepare(this); r.target="fixture:changed"; M16Fixture.event(this,"TARGET_MUTATED",r); M16Fixture.preview(this,r)
        }
        button("D08: Mutate payload before approval") {
            val r=M16Fixture.prepare(this); r.payload="Changed text"; M16Fixture.event(this,"PAYLOAD_MUTATED",r); M16Fixture.preview(this,r)
        }
        button("D06: Attempt previous approval replay") {
            M16Fixture.last?.let { M16Fixture.event(this,"REPLAY_ATTEMPT",it); M16Fixture.preview(this,it) }
        }
        button("D12: Invalidate fixture session then preview") {
            val r=M16Fixture.prepare(this); SensitiveActionGuard.invalidate(r.session); M16Fixture.event(this,"SESSION_DISCONNECTED",r); M16Fixture.preview(this,r)
        }
        button("D13: Long exact Burmese/Korean/English preview") { M16Fixture.preview(this,M16Fixture.prepare(this,M16Fixture.EXACT.repeat(80))) }
        button("D15: Prepare real 15-minute expiry without preview") { M16Fixture.prepareExpiry(this); refresh() }
        button("D15: Read elapsed / uptime / expiry evidence") { status.text=M16Fixture.expiryEvidence(this) }
        button("D15: Attempt expired preview after real 15-minute sleep") {
            M16Fixture.expiry?.let { M16Fixture.event(this,"EXPIRY_REPLAY_ATTEMPT",it); M16Fixture.preview(this,it) }
        }
        button("Camera consent component: local frozen-frame counter") { imageConsent() }
        body.addView(TextView(this).apply {
            text="D02 Cancel; D03 Back; D04 reject fingerprint; D05 touch enrolled sensor; D09 Home; D10 manually lock/unlock; D11 recreate approval Activity. No simulated biometric success.\nVoice Live, actual Contacts UI and CameraX integration are not represented by this fixture."
        })
        setContentView(ScrollView(this).apply { addView(body) })
    }
    private fun imageConsent() {
        val frame=M16Fixture.EXACT // Local immutable content fixture; not a camera capture or network frame.
        val consent=ImageShareConsent(frame,this,{ resumed })
        imageDialog=AlertDialog.Builder(this).setTitle("LOCAL frozen-frame consent component")
            .setMessage("Exact fixture content:\n$frame\nDestination: local counter only; external transmission disabled.")
            .setPositiveButton("Approve local counter") { _,_ ->
                if (consent.consume(this,ApprovalUiLease.tap(this))==frame) {
                    M16Fixture.imageEffects.incrementAndGet(); M16Fixture.event(this,"LOCAL_IMAGE_CONSENT_COMPONENT")
                }
                refresh()
            }.setNegativeButton("Cancel") { _,_ -> consent.cancel() }
            .setOnCancelListener { consent.cancel() }.setOnDismissListener { consent.cancel() }.create()
        imageDialog!!.show()
    }
    private fun refresh() { status.text="LOCAL effects=${M16Fixture.effects.get()}; image consent effects=${M16Fixture.imageEffects.get()}. Hardware verdict requires documented observation." }
    override fun onResume() {
        super.onResume(); resumed=true; ApprovalUiLease.resume(this); refresh()
        M16Fixture.event(this,"FIXTURE_RESUMED")
    }
    override fun onPause() {
        resumed=false; ApprovalUiLease.pause(this); imageDialog?.dismiss()
        M16Fixture.event(this,"FIXTURE_PAUSED")
        super.onPause()
    }
}
