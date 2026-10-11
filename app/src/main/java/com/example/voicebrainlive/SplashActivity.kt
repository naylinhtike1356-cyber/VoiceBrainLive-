package com.example.voicebrainlive

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout

/**
 * Minimal launcher – extends plain Activity (not ComponentActivity) to rule out AndroidX/Compose init crash.
 */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            Log.d("SplashActivity", "onCreate started")
            super.onCreate(savedInstanceState)
            setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK) })
            Log.d("SplashActivity", "setContentView done")

            val next = if (OnboardingActivity.isOnboardingDone(this)) {
                MainActivity::class.java
            } else {
                OnboardingActivity::class.java
            }
            startActivity(Intent(this, next))
            finish()
        } catch (t: Throwable) {
            Log.e("CRASH_LOG", "SplashActivity FATAL", t)
            t.printStackTrace()
            throw t
        }
    }
}
