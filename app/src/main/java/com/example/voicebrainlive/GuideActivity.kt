package com.example.voicebrainlive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.voicebrainlive.ui.components.GuideScreen
import com.example.voicebrainlive.ui.theme.VoiceBrainLiveTheme

/**
 * အသုံးပြုလမ်းညွှန် – Voice Brain အသုံးပြုနည်း၊ အသံကွန်းမက်များ၊ Backup နှင့် Update
 */
class GuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContent {
            VoiceBrainLiveTheme(darkTheme = ThemeUtils.getTheme(this) != "light", dynamicColor = false) {
                GuideScreen(onBack = { finish() })
            }
        }
    }
}
