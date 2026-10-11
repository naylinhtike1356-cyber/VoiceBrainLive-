package com.example.voicebrainlive

import leakcanary.LeakCanary
import shark.AndroidReferenceMatchers
import shark.LibraryLeakReferenceMatcher
import shark.ReferencePattern

/**
 * Debug Application: configures LeakCanary to ignore known Android framework leaks.
 *
 * MediaSessionAccessService leak: NotificationListenerService$NotificationListenerWrapper
 * holds a strong reference (this$0) to our service after onDestroy. This is an Android
 * framework behavior we cannot fix. Marking it as a library leak so LeakCanary won't
 * report it as an application leak.
 */
class DebugVoiceBrainApplication : VoiceBrainApplication() {

    override fun onCreate() {
        super.onCreate()

        LeakCanary.config = LeakCanary.config.copy(
            referenceMatchers = AndroidReferenceMatchers.appDefaults + listOf(
                LibraryLeakReferenceMatcher(
                    pattern = ReferencePattern.InstanceFieldPattern(
                        className = "android.service.notification.NotificationListenerService\$NotificationListenerWrapper",
                        fieldName = "this$0"
                    ),
                    description = "NotificationListenerService holds service via NotificationListenerWrapper.this$0 - Android framework leak"
                )
            )
        )
    }
}
