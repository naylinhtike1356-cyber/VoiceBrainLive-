package com.example.voicebrainlive.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Proactive "မိုးရွာမယ်" – မနက် ၇ နာရီ alarm ဖြစ်ရင် ProactiveRainWorker စတင်
 */
class ProactiveRainReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PROACTIVE_RAIN) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ProactiveRainWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ProactiveRainWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        const val ACTION_PROACTIVE_RAIN = "com.example.voicebrainlive.PROACTIVE_RAIN"
    }
}
