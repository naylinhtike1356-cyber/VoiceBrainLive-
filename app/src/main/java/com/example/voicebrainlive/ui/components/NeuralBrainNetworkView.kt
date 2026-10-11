package com.example.voicebrainlive.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.example.voicebrainlive.ui.MemoryCategory
import com.example.voicebrainlive.ui.UnifiedMemoryItem
import kotlin.math.*

/**
 * Interactive 2D/pseudo-3D Neural Brain Network Visualizer
 * Visualizes memory items as glowing neural synapses connected to a central AI Brain Core.
 */
class NeuralBrainNetworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BrainNode(
        val item: UnifiedMemoryItem,
        var baseAngle: Float,
        var distance: Float,
        var currentX: Float = 0f,
        var currentY: Float = 0f,
        var radius: Float = 24f,
        var color: Int = Color.CYAN,
        var glowColor: Int = Color.CYAN,
        var isSelected: Boolean = false
    )

    data class SynapticPulse(
        var fromNodeIndex: Int,
        var toCore: Boolean, // true: node -> core, false: core -> node
        var progress: Float = 0f,
        var speed: Float = 0.015f,
        var color: Int = Color.WHITE
    )

    private val nodes = mutableListOf<BrainNode>()
    private val pulses = mutableListOf<SynapticPulse>()
    private var selectedNode: BrainNode? = null

    var onNodeClickListener: ((UnifiedMemoryItem) -> Unit)? = null

    // Rotation & Pan Offsets
    private var rotationAngle = 0f
    private var panX = 0f
    private var panY = 0f
    private var scaleFactor = 1.0f

    // Core Animation values
    private var pulsePhase = 0f
    private val coreRadius = 42f

    // Paints
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#448AFF")
    }
    private val coreGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val coreRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#80D8FF")
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#33448AFF")
    }
    private val interLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.parseColor("#2280D8FF")
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val categoryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FFFF00")
    }

    private var animator: ValueAnimator? = null
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            rotationAngle += distanceX * 0.002f
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        startAnimationLoop()
    }

    private fun startAnimationLoop() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulsePhase = (pulsePhase + 0.03f) % (2 * Math.PI.toFloat())
                rotationAngle = (rotationAngle + 0.003f) % (2 * Math.PI.toFloat())
                updatePulses()
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun updatePulses() {
        if (nodes.isEmpty()) return
        
        // Spawn random synaptic pulses
        if (pulses.size < 8 && Math.random() < 0.08) {
            val randomNodeIdx = (0 until nodes.size).random()
            pulses.add(
                SynapticPulse(
                    fromNodeIndex = randomNodeIdx,
                    toCore = Math.random() < 0.5,
                    progress = 0f,
                    speed = (0.012f + (Math.random() * 0.015f)).toFloat(),
                    color = nodes[randomNodeIdx].color
                )
            )
        }

        val iterator = pulses.iterator()
        while (iterator.hasNext()) {
            val pulse = iterator.next()
            pulse.progress += pulse.speed
            if (pulse.progress >= 1.0f) {
                iterator.remove()
            }
        }
    }

    fun setMemories(items: List<UnifiedMemoryItem>) {
        nodes.clear()
        pulses.clear()
        selectedNode = null

        val count = items.size
        if (count == 0) {
            invalidate()
            return
        }

        val angleStep = (2 * Math.PI / count).toFloat()
        items.forEachIndexed { i, item ->
            val angle = i * angleStep
            // Layered distance for organic neural cluster look
            val distLayer = 180f + (i % 3) * 110f + (if (i % 2 == 0) 30f else -20f)
            
            val (color, glowColor) = when (item.category) {
                MemoryCategory.FACT -> Pair(Color.parseColor("#00E5FF"), Color.parseColor("#3300E5FF"))
                MemoryCategory.HABIT -> Pair(Color.parseColor("#E040FB"), Color.parseColor("#33E040FB"))
                MemoryCategory.CONVERSATION -> Pair(Color.parseColor("#2979FF"), Color.parseColor("#332979FF"))
                MemoryCategory.NOTE -> Pair(Color.parseColor("#FFAB00"), Color.parseColor("#33FFAB00"))
            }

            nodes.add(
                BrainNode(
                    item = item,
                    baseAngle = angle,
                    distance = distLayer,
                    radius = if (item.category == MemoryCategory.FACT || item.category == MemoryCategory.HABIT) 28f else 22f,
                    color = color,
                    glowColor = glowColor
                )
            )
        }
        invalidate()
    }

    fun selectMemory(item: UnifiedMemoryItem?) {
        nodes.forEach { it.isSelected = (it.item.id == item?.id) }
        selectedNode = nodes.find { it.isSelected }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val centerX = width / 2f + panX
        val centerY = height / 2f + panY

        var clickedNode: BrainNode? = null
        var minDistance = Float.MAX_VALUE

        for (node in nodes) {
            val dx = x - node.currentX
            val dy = y - node.currentY
            val dist = sqrt(dx * dx + dy * dy)
            // Tap target threshold with generous padding
            if (dist < (node.radius + 35f) && dist < minDistance) {
                minDistance = dist
                clickedNode = node
            }
        }

        nodes.forEach { it.isSelected = false }
        if (clickedNode != null) {
            clickedNode.isSelected = true
            selectedNode = clickedNode
            onNodeClickListener?.invoke(clickedNode.item)
        } else {
            selectedNode = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f + panX
        val centerY = height / 2f + panY

        if (width == 0 || height == 0) return

        // 1. Calculate positions for all nodes
        nodes.forEachIndexed { i, node ->
            val angle = node.baseAngle + rotationAngle
            // Add subtle floating breath motion
            val breath = sin(pulsePhase + i * 0.8f) * 12f
            val currentDist = node.distance + breath
            node.currentX = centerX + cos(angle) * currentDist
            node.currentY = centerY + sin(angle) * currentDist
        }

        // 2. Draw Inter-node Synaptic Webs (connecting nearby memories)
        for (i in nodes.indices) {
            val nodeA = nodes[i]
            for (j in i + 1 until nodes.size) {
                val nodeB = nodes[j]
                val dx = nodeA.currentX - nodeB.currentX
                val dy = nodeA.currentY - nodeB.currentY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 260f) {
                    val alpha = ((1f - dist / 260f) * 70).toInt().coerceIn(10, 70)
                    interLinePaint.color = (alpha shl 24) or (nodeA.color and 0x00FFFFFF)
                    canvas.drawLine(nodeA.currentX, nodeA.currentY, nodeB.currentX, nodeB.currentY, interLinePaint)
                }
            }
        }

        // 3. Draw Synapse Lines from Central Core to each Node
        nodes.forEach { node ->
            linePaint.color = (0x44 shl 24) or (node.color and 0x00FFFFFF)
            canvas.drawLine(centerX, centerY, node.currentX, node.currentY, linePaint)
        }

        // 4. Draw Synaptic Energy Pulses (glowing action potentials moving along lines)
        pulses.forEach { pulse ->
            if (pulse.fromNodeIndex in nodes.indices) {
                val node = nodes[pulse.fromNodeIndex]
                val startX = if (pulse.toCore) node.currentX else centerX
                val startY = if (pulse.toCore) node.currentY else centerY
                val endX = if (pulse.toCore) centerX else node.currentX
                val endY = if (pulse.toCore) centerY else node.currentY

                val px = startX + (endX - startX) * pulse.progress
                val py = startY + (endY - startY) * pulse.progress

                pulsePaint.color = pulse.color
                pulsePaint.alpha = 240
                canvas.drawCircle(px, py, 5.5f, pulsePaint)
                
                // Pulse halo
                pulsePaint.alpha = 80
                canvas.drawCircle(px, py, 11f, pulsePaint)
            }
        }

        // 5. Draw Central AI Brain Core
        val coreGlowSize = coreRadius + 18f + sin(pulsePhase) * 8f
        coreGlowPaint.shader = RadialGradient(
            centerX, centerY, coreGlowSize,
            intArrayOf(Color.parseColor("#80448AFF"), Color.parseColor("#20448AFF"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, coreGlowSize, coreGlowPaint)
        canvas.drawCircle(centerX, centerY, coreRadius, corePaint)
        
        // Rotating Core Rings
        coreRingPaint.alpha = (140 + sin(pulsePhase) * 80).toInt().coerceIn(60, 220)
        canvas.drawCircle(centerX, centerY, coreRadius + 6f, coreRingPaint)
        canvas.drawCircle(centerX, centerY, coreRadius + 14f, coreRingPaint)

        // Core text icon
        textPaint.textSize = 30f
        canvas.drawText("🧠", centerX, centerY + 10f, textPaint)

        // 6. Draw Memory Synaptic Nodes
        nodes.forEach { node ->
            // Outer glow halo
            nodeGlowPaint.shader = RadialGradient(
                node.currentX, node.currentY, node.radius * 2.2f,
                intArrayOf(node.color, node.glowColor, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(node.currentX, node.currentY, node.radius * 2.2f, nodeGlowPaint)

            // Inner solid node
            nodePaint.color = node.color
            canvas.drawCircle(node.currentX, node.currentY, node.radius, nodePaint)

            // If selected, draw highlight animated ring
            if (node.isSelected) {
                selectionRingPaint.color = Color.WHITE
                canvas.drawCircle(node.currentX, node.currentY, node.radius + 8f + sin(pulsePhase * 2f) * 3f, selectionRingPaint)
            }

            // Node Title / Icon Label
            val label = when (node.item.category) {
                MemoryCategory.FACT -> "📌 " + node.item.title.take(10)
                MemoryCategory.HABIT -> "🧠 " + node.item.title.take(10)
                MemoryCategory.CONVERSATION -> "💬 " + node.item.title.take(8)
                MemoryCategory.NOTE -> "📝 " + node.item.title.take(10)
            }
            textPaint.textSize = 24f
            canvas.drawText(label, node.currentX, node.currentY + node.radius + 28f, textPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
