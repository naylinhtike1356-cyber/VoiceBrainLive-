package com.example.voicebrainlive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.voicebrainlive.ui.components.OverlayRobotCornerScreen
import com.example.voicebrainlive.ui.components.VoiceStatus
import com.example.voicebrainlive.utils.DraggableOverlayContainer
import com.example.voicebrainlive.utils.ServiceLifecycleOwner
import kotlin.math.sqrt

/**
 * v2.2: Floating bubble – shows customizable 3D avatar, supports auto edge-snapping,
 * inactivity peek tab, bottom dismiss target zone, and launches VoiceLiveService assistant.
 */
class FloatingBubbleService : Service() {

    private val composeLifecycleOwner = ServiceLifecycleOwner()
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var dismissTargetView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Theme state
    private var currentTheme by mutableStateOf(BubblePrefs.BubbleTheme.PYIT_TINE_HTAUNG)

    // Dismiss target state
    private val isHoveringDismiss = mutableStateOf(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        composeLifecycleOwner.onCreate()
        Log.w(TAG, "FloatingBubbleService onCreate - Floating Avatar Bubble စတင်မည်")
        startForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        currentTheme = BubblePrefs.getTheme(this)
        if (VoiceLiveService.instance != null) {
            Log.d(TAG, "VoiceLiveService active, starting bubble in hidden state")
        } else {
            showBubble()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HIDE_BUBBLE -> {
                hideBubble()
                return START_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                if (VoiceLiveService.instance == null) {
                    showBubble()
                }
                return START_STICKY
            }
            ACTION_UPDATE_THEME -> {
                val newTheme = BubblePrefs.getTheme(this)
                if (newTheme != currentTheme) {
                    currentTheme = newTheme
                    if (bubbleView != null) {
                        hideBubble()
                        showBubble()
                    }
                }
                return START_STICKY
            }
            else -> {
                if (VoiceLiveService.instance == null && bubbleView == null) {
                    showBubble()
                }
            }
        }
        return START_STICKY
    }

    fun hideBubble() {
        hideDismissTarget()
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "removeView in hideBubble", e)
        }
        bubbleView = null
    }

    fun showBubble() {
        if (bubbleView != null) {
            bubbleView?.visibility = View.VISIBLE
            bubbleView?.alpha = 1.0f
            (bubbleView as? DraggableOverlayContainer)?.clampToScreen()
            return
        }
        try {
            currentTheme = BubblePrefs.getTheme(this)
            val composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(composeLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(composeLifecycleOwner)
                setViewTreeViewModelStoreOwner(composeLifecycleOwner)
                setContent {
                    OverlayRobotCornerScreen(
                        statusText = "Voice Brain",
                        voiceStatus = VoiceStatus.IDLE,
                        theme = currentTheme,
                        onExpand = { launchRobotLive() }
                    )
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 10
                y = (resources.displayMetrics.density * 180).toInt()
            }
            layoutParams = params

            val container = DraggableOverlayContainer(this).apply {
                setViewTreeLifecycleOwner(composeLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(composeLifecycleOwner)
                setViewTreeViewModelStoreOwner(composeLifecycleOwner)
                setup(windowManager!!, params) {
                    launchRobotLive()
                }

                // Dragging coordination for dismiss target
                onDragStateChanged = { isDragging, rawX, rawY ->
                    if (isDragging) {
                        showDismissTarget()
                        checkDismissHover(rawX, rawY)
                    } else {
                        hideDismissTarget()
                    }
                }

                onDragRelease = { rawX, rawY ->
                    val inDismiss = isInsideDismissTarget(rawX, rawY)
                    hideDismissTarget()
                    if (inDismiss) {
                        dismissBubblePermanently()
                        true
                    } else {
                        false
                    }
                }

                addView(composeView)
            }
            bubbleView = container

            windowManager?.addView(bubbleView, params)
        } catch (e: Throwable) {
            Log.e(TAG, "showBubble failed", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(applicationContext, "Bubble error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            bubbleView = null
        }
    }

    private fun showDismissTarget() {
        if (dismissTargetView != null) return
        try {
            val dm = resources.displayMetrics
            val targetParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (dm.density * 50).toInt()
            }

            val targetCompose = ComposeView(this).apply {
                setViewTreeLifecycleOwner(composeLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(composeLifecycleOwner)
                setViewTreeViewModelStoreOwner(composeLifecycleOwner)
                setContent {
                    val hovering by isHoveringDismiss
                    val targetScale by animateFloatAsState(
                        targetValue = if (hovering) 1.25f else 1.0f,
                        animationSpec = spring(),
                        label = "targetScale"
                    )
                    val targetColor by animateColorAsState(
                        targetValue = if (hovering) Color(0xFFEF4444) else Color(0xCC1E293B),
                        label = "targetColor"
                    )
                    val borderColor by animateColorAsState(
                        targetValue = if (hovering) Color(0xFFFCA5A5) else Color(0x66EF4444),
                        label = "borderColor"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .scale(targetScale)
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(targetColor)
                                .border(2.dp, borderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (hovering) "လွှတ်၍ ပိတ်မည်" else "ဆွဲချ ပိတ်ရန်",
                            color = if (hovering) Color(0xFFFCA5A5) else Color(0xCCFFFFFF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            dismissTargetView = targetCompose
            windowManager?.addView(dismissTargetView, targetParams)
        } catch (e: Exception) {
            Log.w(TAG, "showDismissTarget error", e)
        }
    }

    private fun hideDismissTarget() {
        try {
            dismissTargetView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "hideDismissTarget error", e)
        }
        dismissTargetView = null
        isHoveringDismiss.value = false
    }

    private fun isInsideDismissTarget(rawX: Float, rawY: Float): Boolean {
        val dm = resources.displayMetrics
        val targetCenterX = dm.widthPixels / 2f
        val targetCenterY = dm.heightPixels - (50 * dm.density) - (35 * dm.density)
        val dx = rawX - targetCenterX
        val dy = rawY - targetCenterY
        val dist = sqrt(dx * dx + dy * dy)
        return dist < (55 * dm.density)
    }

    private fun checkDismissHover(rawX: Float, rawY: Float) {
        val hovering = isInsideDismissTarget(rawX, rawY)
        if (hovering != isHoveringDismiss.value) {
            isHoveringDismiss.value = hovering
            if (hovering) {
                bubbleView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    private fun dismissBubblePermanently() {
        Log.i(TAG, "Bubble dropped into dismiss target - dismissing active bubble")
        hideBubble()
        // Do not permanently turn off BubblePrefs.setEnabled(this, false) so user preference in Settings remains ON.
        Toast.makeText(applicationContext, "Floating Bubble ကို ခေတ္တ ပိတ်လိုက်ပါပြီ", Toast.LENGTH_SHORT).show()
        stopSelf()
    }

    private fun launchRobotLive() {
        Log.w(TAG, "Robot bubble clicked - launching VoiceLiveService with robot card overlay")
        hideBubble()
        val serviceIntent = Intent(this, VoiceLiveService::class.java).apply {
            action = VoiceLiveService.ACTION_START
            putExtra("EXTRA_START_WITH_CARD", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged: re-aligning bubble within screen bounds")
        (bubbleView as? DraggableOverlayContainer)?.clampToScreen()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (BubblePrefs.isEnabled(this)) {
            Log.d(TAG, "FloatingBubbleService task removed; restarting service")
            try {
                val restartIntent = Intent(this, FloatingBubbleService::class.java).apply {
                    action = ACTION_SHOW_BUBBLE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restart floating bubble on task removed: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        composeLifecycleOwner.onDestroy()
        hideBubble()
        if (instance == this) {
            instance = null
        }
    }

    private fun startForeground() {
        val channelId = "voice_brain_bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Voice Brain Bubble", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Voice Brain")
            .setContentText("Floating bubble active – tap to open assistant")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "FloatingBubble"
        const val ACTION_STOP = "com.example.voicebrainlive.STOP_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.example.voicebrainlive.HIDE_BUBBLE"
        const val ACTION_SHOW_BUBBLE = "com.example.voicebrainlive.SHOW_BUBBLE"
        const val ACTION_UPDATE_THEME = "com.example.voicebrainlive.UPDATE_BUBBLE_THEME"
        private const val NOTIFICATION_ID = 9001

        var instance: FloatingBubbleService? = null
            private set
    }
}
