package com.example.voicebrainlive.utils

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import kotlin.math.abs

/**
 * A specialized container for WindowManager floating overlays (such as ComposeView) that reliably
 * intercepts and handles touch events for dragging, auto edge-snapping, inactivity peek tab,
 * and bottom dismiss target coordination.
 */
class DraggableOverlayContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var onClickAction: (() -> Unit)? = null

    // Drag coordination callbacks
    var onDragStateChanged: ((isDragging: Boolean, rawX: Float, rawY: Float) -> Unit)? = null
    var onDragRelease: ((rawX: Float, rawY: Float) -> Boolean)? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var clickTime = 0L
    private var isDragging = false

    private var isPeeked = false
    private var isDockedOnLeft = true
    private var snapAnimator: ValueAnimator? = null
    private var peekAnimator: ValueAnimator? = null

    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = context.resources.displayMetrics.heightPixels

    private val density: Float
        get() = context.resources.displayMetrics.density

    fun setup(
        wm: WindowManager,
        params: WindowManager.LayoutParams,
        onClick: () -> Unit
    ) {
        this.windowManager = wm
        this.layoutParams = params
        this.onClickAction = onClick
        post {
            clampToScreen()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                if (isPeeked) {
                    restoreFromPeek()
                }
                initialX = layoutParams?.x ?: 0
                initialY = layoutParams?.y ?: 0
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                clickTime = System.currentTimeMillis()
                isDragging = false
                return false // allow child to receive DOWN in case of a tap
            }
            MotionEvent.ACTION_MOVE -> {
                val diffX = abs(ev.rawX - initialTouchX)
                val diffY = abs(ev.rawY - initialTouchY)
                if (diffX > 15 || diffY > 15) {
                    isDragging = true
                    onDragStateChanged?.invoke(true, ev.rawX, ev.rawY)
                    return true // Intercept! Cancel child touch events and take over dragging
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                if (isPeeked) {
                    restoreFromPeek()
                }
                initialX = layoutParams?.x ?: 0
                initialY = layoutParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                clickTime = System.currentTimeMillis()
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val diffX = abs(event.rawX - initialTouchX)
                val diffY = abs(event.rawY - initialTouchY)
                if (diffX > 15 || diffY > 15) {
                    if (!isDragging) {
                        isDragging = true
                        onDragStateChanged?.invoke(true, event.rawX, event.rawY)
                    }
                }
                if (isDragging) {
                    layoutParams?.let { params ->
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(this, params)
                        } catch (e: Exception) {
                            Log.w("DraggableOverlay", "Failed to update layout", e)
                        }
                    }
                    onDragStateChanged?.invoke(true, event.rawX, event.rawY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - clickTime
                val diffX = abs(event.rawX - initialTouchX)
                val diffY = abs(event.rawY - initialTouchY)

                if (isDragging) {
                    isDragging = false
                    val handled = onDragRelease?.invoke(event.rawX, event.rawY) ?: false
                    onDragStateChanged?.invoke(false, event.rawX, event.rawY)
                    if (!handled) {
                        snapToClosestEdge()
                    }
                } else {
                    onDragStateChanged?.invoke(false, event.rawX, event.rawY)
                    if (duration < 350 && diffX <= 25 && diffY <= 25) {
                        onClickAction?.invoke()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    snapToClosestEdge()
                }
                onDragStateChanged?.invoke(false, event.rawX, event.rawY)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Auto edge snapping: smoothly glides the bubble to either the left or right edge of the screen.
     */
    fun snapToClosestEdge() {
        snapAnimator?.cancel()
        val params = layoutParams ?: return
        val viewWidth = if (width > 0) width else (56 * density).toInt()
        val bubbleCenterX = params.x + viewWidth / 2
        val isLeft = bubbleCenterX < (screenWidth / 2)
        isDockedOnLeft = isLeft

        val targetX = if (isLeft) 0 else (screenWidth - viewWidth)

        // Clamp Y to safe screen boundaries
        val viewHeight = if (height > 0) height else (56 * density).toInt()
        val minY = (48 * density).toInt()
        val maxY = (screenHeight - viewHeight - 64 * density).toInt()
        params.y = params.y.coerceIn(minY, maxY)

        val startX = params.x
        snapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(this@DraggableOverlayContainer, params)
                } catch (e: Exception) {
                    Log.w("DraggableOverlay", "Failed to update layout during snap", e)
                }
            }
            doOnEnd {
                alpha = 1.0f
                isPeeked = false
            }
            start()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        post {
            clampToScreen()
        }
    }

    /**
     * Clamps the bubble position to within current screen boundaries.
     * Ensures the bubble never disappears off-screen during orientation changes or window resize.
     */
    fun clampToScreen() {
        snapAnimator?.cancel()
        peekAnimator?.cancel()
        val params = layoutParams ?: return
        val viewWidth = if (width > 0) width else (56 * density).toInt()
        val viewHeight = if (height > 0) height else (56 * density).toInt()

        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val minY = (48 * density).toInt()
        val maxY = (screenHeight - viewHeight - 64 * density).toInt().coerceAtLeast(minY)

        params.x = if (isDockedOnLeft) 0 else maxX
        params.y = params.y.coerceIn(minY, maxY)
        alpha = 1.0f
        isPeeked = false

        try {
            windowManager?.updateViewLayout(this, params)
        } catch (e: Exception) {
            Log.w("DraggableOverlay", "Failed to clamp layout to screen", e)
        }
    }

    /**
     * Restores the bubble to full visibility and screen-docked position.
     */
    fun restoreFromPeek(onRestored: (() -> Unit)? = null) {
        peekAnimator?.cancel()
        snapAnimator?.cancel()
        isPeeked = false
        alpha = 1.0f
        clampToScreen()
        onRestored?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        snapAnimator?.cancel()
        peekAnimator?.cancel()
    }
}
