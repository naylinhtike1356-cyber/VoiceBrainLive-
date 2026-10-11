package com.example.voicebrainlive

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.voicebrainlive.databinding.ActivityGuideBinding

/**
 * အသုံးပြုလမ်းညွန် – Voice Brain အသုံးပြုနည်း၊ အသံကွန်းမက်များ၊ အပ်ဒိတ်အစီအစဉ်
 */
class GuideActivity : ComponentActivity() {

    private lateinit var binding: ActivityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ThemeUtils.getTheme(this) == "light") {
            binding.root.setBackgroundResource(R.drawable.bg_gradient_light)
        }

        binding.btnBack.setOnClickListener { finish() }
    }
}
