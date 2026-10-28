package com.example.voicebrainlive.ui

import android.view.View
import androidx.fragment.app.Fragment
import com.example.voicebrainlive.SettingsActivity

/** Optional variant boundary. Original activity/application do not implement these interfaces. */
interface InteractiveBetaHost {
    fun betaHomeView(): View
    fun betaConversation(): Fragment
    fun betaSettings()
    fun betaNavLabels(): List<String> = listOf("Home","Chat","Tasks","Memory","Settings")
    fun betaMemory()
    fun betaTasks()
    fun betaInbox()
    fun betaGuide()
}
interface InteractiveBetaSettings {
    fun renderBetaSettings(activity: SettingsActivity)
    fun betaTaskStore(): com.example.voicebrainlive.agent.brain.EncryptedBrainStore? = null
    fun betaHeader(activity: android.app.Activity, title: String): View? = null
}
