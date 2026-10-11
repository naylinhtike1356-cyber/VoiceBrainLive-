package com.example.voicebrainlive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
2.2/3.0 Feature: Accessibility-based UI Automation (Voice Access)
Allows VoiceBrain to click, scroll, type, and navigate on screen.
*/
class VoiceBrainAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VoiceBrainAccess"
        
        @Volatile
        var instance: VoiceBrainAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "VoiceBrainAccessibilityService Connected")
        instance = this
    }

    override fun onDestroy() {
        Log.i(TAG, "VoiceBrainAccessibilityService Destroyed")
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not tracking events actively, just acting as command executor
    }

    override fun onInterrupt() {
        Log.w(TAG, "VoiceBrainAccessibilityService Interrupted")
    }

    data class ScreenElement(
        val index: Int,
        val text: String,
        val contentDesc: String?,
        val resourceId: String?,
        val className: String?,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean
    )

    private val cachedElements = mutableListOf<ScreenElement>()

    private val SYNONYMS = mapOf(
        "next" to listOf("next", "continue", "confirm", "done", "ok", "submit", "agree", "next step", "forward", "နောက်တစ်ဆင့်", "နောက်တဆင့်", "ရှေ့ဆက်", "ရှေ့တိုး", "လုပ်ဆောင်မည်", "သဘောတူသည်", "အိုကေ", "နက်စ်"),
        "back" to listOf("back", "previous", "go back", "return", "နောက်သို့", "နောက်ဆုတ်", "အနောက်သို့", "ပြန်သွား", "ဘတ်", "arrow_back", "ic_back"),
        "ok" to listOf("ok", "okay", "confirm", "done", "yes", "agree", "accept", "အိုကေ", "လုပ်မယ်", "ဟုတ်ကဲ့", "သဘောတူတယ်", "အတည်ပြု"),
        "cancel" to listOf("cancel", "close", "dismiss", "exit", "stop", "ပယ်ဖျက်", "ဖျက်သိမ်း", "ပိတ်ပါ", "မလုပ်တော့ပါ", "ကြက်ခြေခတ်", "x", "close_button"),
        "delete" to listOf("delete", "remove", "clear", "trash", "bin", "ဖျက်ပါ", "ဖျက်", "ပယ်ဖျက်ပါ", "အမှိုက်ပုံး"),
        "search" to listOf("search", "find", "query", "lookup", "explore", "ရှာပါ", "ရှာပေး", "ရှာမည်", "မှန်ဘီလူး", "search_button", "ic_search"),
        "send" to listOf("send", "submit", "post", "share", "ပို့မည်", "ပေးပို့ပါ", "စာပို့", "တင်မည်", "send_button", "ic_send"),
        "save" to listOf("save", "keep", "store", "done", "သိမ်းပါ", "သိမ်းဆည်း", "မှတ်ပါ", "ပြီးပါပြီ"),
        "edit" to listOf("edit", "modify", "change", "pencil", "ပြင်ပါ", "ပြင်ဆင်", "ရေးမည်"),
        "play" to listOf("play", "start", "resume", "ဖွင့်ပါ", "စတင်ပါ", "play_arrow"),
        "pause" to listOf("pause", "stop", "ခေတ္တရပ်", "ရပ်ပါ", "ခေတ္တပိတ်"),
        "home" to listOf("home", "main", "မူလမျက်နှာပြင်", "ပင်မမျက်နှာပြင်", "home_page"),
        "menu" to listOf("menu", "more", "options", "overflow", "နောက်ထပ်", "မီနူး", "more_vert", "drawer"),
        "settings" to listOf("settings", "preferences", "gear", "config", "ပြင်ဆင်ချက်များ", "ဆက်တင်", "အပြင်အဆင်"),
        "camera" to listOf("camera", "photo", "capture", "ကင်မရာ", "ဓာတ်ပုံ"),
        "mic" to listOf("mic", "microphone", "voice", "record", "မိုက်", "အသံဖမ်း"),
        "like" to listOf("like", "thumb", "favorite", "love", "ကြိုက်တယ်", "သဘောကျ"),
        "share" to listOf("share", "forward", "မျှဝေပါ", "ရှယ်မည်"),
        "download" to listOf("download", "save offline", "install", "ဒေါင်းလုဒ်", "ရယူပါ"),
        "cart" to listOf("cart", "basket", "bag", "shop", "ဈေးခြင်း", "လှည်း", "ဝယ်မည်"),
        "call" to listOf("call", "phone", "dial", "ဖုန်းခေါ်", "ဖုန်းဆက်")
    )

    /**
     * Finds and clicks a node by index number (#1, #2), text, contentDescription, or synonym.
     */
    fun clickText(target: String): Boolean {
        val cleanTarget = target.trim()
        
        // 1. Check for index-based click (#1, #2, 1, 2, နံပါတ် ၁, etc.)
        val indexPattern = Regex("""^(?:#|နံပါတ်\s*|element\s*|အမှတ်\s*)?([၀-၉0-9]+)$""", RegexOption.IGNORE_CASE)
        val match = indexPattern.find(cleanTarget)
        if (match != null) {
            val numStr = match.groupValues[1]
                .replace("၀", "0").replace("၁", "1").replace("၂", "2").replace("၃", "3").replace("၄", "4")
                .replace("၅", "5").replace("၆", "6").replace("၇", "7").replace("၈", "8").replace("၉", "9")
            val index = numStr.toIntOrNull()
            if (index != null && index in 1..cachedElements.size) {
                val element = cachedElements[index - 1]
                Log.d(TAG, "Clicking cached element #$index at ${element.bounds.centerX()}, ${element.bounds.centerY()}")
                return clickAt(element.bounds.centerX().toFloat(), element.bounds.centerY().toFloat())
            }
        }

        val root = rootInActiveWindow ?: return false
        
        // 2. Try exact / containment match first
        if (performClickOnText(root, cleanTarget)) {
            root.recycle()
            return true
        }

        // 3. Try Burmese Unicode normalized & fuzzy match
        val normalized = normalizeString(cleanTarget)
        if (performFuzzyClick(root, normalized)) {
            root.recycle()
            return true
        }
        
        // 4. If not found, try synonyms
        val synonymsList = SYNONYMS[normalized] ?: SYNONYMS[cleanTarget.lowercase()]
        if (synonymsList != null) {
            Log.d(TAG, "No match for '$cleanTarget', trying synonyms: $synonymsList")
            for (syn in synonymsList) {
                if (syn != normalized && syn != cleanTarget.lowercase()) {
                    if (performClickOnText(root, syn) || performFuzzyClick(root, normalizeString(syn))) {
                        Log.d(TAG, "Successfully clicked synonym '$syn' for '$cleanTarget'")
                        root.recycle()
                        return true
                    }
                }
            }
        }
        
        root.recycle()
        return false
    }

    private fun normalizeString(input: String): String {
        return input.lowercase()
            .replace(Regex("""[\u200B-\u200D\uFEFF]"""), "") // strip zero-width characters
            .replace(Regex("""[^\w\s\u1000-\u109F]"""), " ") // keep alphanumerics & Burmese characters
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun performClickOnText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) {
            return false
        }

        var clicked = false
        for (node in nodes) {
            if (clicked) {
                node.recycle()
                continue
            }
            clicked = safeClickNode(node)
            node.recycle()
        }
        return clicked
    }

    private fun performFuzzyClick(root: AccessibilityNodeInfo, normalizedTarget: String): Boolean {
        val list = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, list)
        var clicked = false
        
        for (node in list) {
            if (clicked) {
                node.recycle()
                continue
            }
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val resId = node.viewIdResourceName?.toString() ?: ""
            
            val normText = normalizeString(text)
            val normDesc = normalizeString(desc)
            val normResId = normalizeString(resId)

            if ((normText.isNotBlank() && (normText.contains(normalizedTarget) || normalizedTarget.contains(normText))) ||
                (normDesc.isNotBlank() && (normDesc.contains(normalizedTarget) || normalizedTarget.contains(normDesc))) ||
                (normResId.isNotBlank() && normResId.contains(normalizedTarget))) {
                
                clicked = safeClickNode(node)
            }
            node.recycle()
        }
        return clicked
    }

    private fun safeClickNode(node: AccessibilityNodeInfo): Boolean {
        // Try node.performAction(ACTION_CLICK) first
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // Try clicking parent node
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                parent.recycle()
                return true
            }
            val nextParent = parent.parent
            parent.recycle()
            parent = nextParent
        }

        // Coordinate tap fallback: if ACTION_CLICK fails, perform touch gesture at element center
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            Log.d(TAG, "ACTION_CLICK failed for node '${node.text ?: node.contentDescription}', falling back to coordinate tap at (${rect.centerX()}, ${rect.centerY()})")
            return clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        return false
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, list)
            child.recycle()
        }
    }

    /**
     * Long click on a target by text/index or coordinates.
     */
    fun longClickText(target: String): Boolean {
        val cleanTarget = target.trim()
        val indexPattern = Regex("""^(?:#|နံပါတ်\s*|element\s*|အမှတ်\s*)?([၀-၉0-9]+)$""", RegexOption.IGNORE_CASE)
        val match = indexPattern.find(cleanTarget)
        if (match != null) {
            val numStr = match.groupValues[1]
                .replace("၀", "0").replace("၁", "1").replace("၂", "2").replace("၃", "3").replace("၄", "4")
                .replace("၅", "5").replace("၆", "6").replace("၇", "7").replace("၈", "8").replace("၉", "9")
            val index = numStr.toIntOrNull()
            if (index != null && index in 1..cachedElements.size) {
                val element = cachedElements[index - 1]
                return longClickAt(element.bounds.centerX().toFloat(), element.bounds.centerY().toFloat())
            }
        }

        val root = rootInActiveWindow ?: return false
        val list = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, list)
        val norm = normalizeString(cleanTarget)

        for (node in list) {
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (normalizeString(text).contains(norm) || normalizeString(desc).contains(norm)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val ok = longClickAt(rect.centerX().toFloat(), rect.centerY().toFloat())
                list.forEach { it.recycle() }
                root.recycle()
                return ok
            }
        }
        list.forEach { it.recycle() }
        root.recycle()
        return false
    }

    /**
     * Scrolls the screen in the specified direction.
     * @param direction "up", "down", "left", "right", "top", "bottom"
     */
    fun scroll(direction: String): Boolean {
        val dir = direction.lowercase().trim()
        val isVertical = dir in listOf("up", "down", "forward", "backward", "top", "bottom")
        
        if (dir == "top") {
            var anyOk = false
            for (i in 0..2) {
                if (performSwipeGesture("up")) anyOk = true
            }
            return anyOk
        } else if (dir == "bottom") {
            var anyOk = false
            for (i in 0..2) {
                if (performSwipeGesture("down")) anyOk = true
            }
            return anyOk
        }

        val root = rootInActiveWindow
        var success = false
        
        if (root != null) {
            val scrollableNode = findBestScrollableNode(root, isVertical)
            if (scrollableNode != null) {
                val action = when (dir) {
                    "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                success = scrollableNode.performAction(action)
                scrollableNode.recycle()
            }
            root.recycle()
        }
        
        // Fallback to physical swipe gesture if node scroll failed
        if (!success) {
            Log.d(TAG, "Scroll action failed, trying swipe gesture fallback for '$dir'")
            success = performSwipeGesture(dir)
        }
        
        return success
    }

    private fun findBestScrollableNode(root: AccessibilityNodeInfo, isVertical: Boolean): AccessibilityNodeInfo? {
        val list = mutableListOf<AccessibilityNodeInfo>()
        findScrollableNodes(root, list)
        if (list.isEmpty()) return null
        
        val candidates = list.filter { node ->
            val className = node.className?.toString() ?: ""
            if (isVertical) {
                !className.contains("HorizontalScrollView", ignoreCase = true)
            } else {
                className.contains("HorizontalScrollView", ignoreCase = true) || 
                className.contains("ViewPager", ignoreCase = true)
            }
        }
        
        val chosenList = if (candidates.isNotEmpty()) candidates else list
        val best = chosenList.maxByOrNull { getNodeArea(it) }
        
        for (node in list) {
            if (node != best) {
                node.recycle()
            }
        }
        return best
    }

    private fun findScrollableNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isScrollable) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollableNodes(child, list)
            child.recycle()
        }
    }

    private fun getNodeArea(node: AccessibilityNodeInfo): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width() * rect.height()
    }

    private fun performSwipeGesture(direction: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        
        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float
        
        when (direction.lowercase().trim()) {
            "up", "backward" -> {
                // Scroll up = Swipe finger top to bottom
                startX = width * 0.5f
                startY = height * 0.25f
                endX = width * 0.5f
                endY = height * 0.8f
            }
            "down", "forward" -> {
                // Scroll down = Swipe finger bottom to top
                startX = width * 0.5f
                startY = height * 0.8f
                endX = width * 0.5f
                endY = height * 0.25f
            }
            "left" -> {
                // Scroll left = Swipe finger left to right
                startX = width * 0.2f
                startY = height * 0.5f
                endX = width * 0.85f
                endY = height * 0.5f
            }
            "right" -> {
                // Scroll right = Swipe finger right to left
                startX = width * 0.85f
                startY = height * 0.5f
                endX = width * 0.2f
                endY = height * 0.5f
            }
            else -> {
                startX = width * 0.5f
                startY = height * 0.8f
                endX = width * 0.5f
                endY = height * 0.25f
            }
        }
        return swipe(startX, startY, endX, endY, 320)
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(swipePath, 0, durationMs)
        gestureBuilder.addStroke(stroke)
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    /**
     * Click at specific coordinates on the screen.
     */
    fun clickAt(x: Float, y: Float): Boolean {
        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, 80)
        gestureBuilder.addStroke(stroke)
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    /**
     * Long press at specific coordinates.
     */
    fun longClickAt(x: Float, y: Float, durationMs: Long = 650): Boolean {
        val clickPath = Path().apply {
            moveTo(x, y)
        }
        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(clickPath, 0, durationMs)
        gestureBuilder.addStroke(stroke)
        return dispatchGesture(gestureBuilder.build(), null, null)
    }

    /**
     * Double click at specific coordinates.
     */
    fun doubleClickAt(x: Float, y: Float): Boolean {
        clickAt(x, y)
        try { Thread.sleep(120) } catch (_: Exception) {}
        return clickAt(x, y)
    }

    /**
     * Input text into currently focused input field or target field.
     */
    fun inputText(text: String, targetField: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        
        // If targetField specified, find and focus that edit text first
        if (!targetField.isNullOrBlank()) {
            val list = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, list)
            val normTarget = normalizeString(targetField)
            for (node in list) {
                if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                    val nodeText = normalizeString(node.text?.toString() ?: "")
                    val nodeDesc = normalizeString(node.contentDescription?.toString() ?: "")
                    val nodeHint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        normalizeString(node.hintText?.toString() ?: "")
                    } else ""

                    if (nodeText.contains(normTarget) || nodeDesc.contains(normTarget) || nodeHint.contains(normTarget)) {
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                        }
                        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        list.forEach { it.recycle() }
                        root.recycle()
                        return ok
                    }
                }
            }
            list.forEach { it.recycle() }
        }

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            // Find any editable node on screen
            val list = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(root, list)
            val editableNode = list.firstOrNull { it.isEditable || it.className?.toString()?.contains("EditText", ignoreCase = true) == true }
            if (editableNode != null) {
                editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val ok = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                list.forEach { it.recycle() }
                root.recycle()
                return ok
            }
            list.forEach { it.recycle() }
            root.recycle()
            return false
        }

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focused.recycle()
        root.recycle()
        return success
    }

    /**
     * Clear text in focused or editable field.
     */
    fun clearText(): Boolean {
        return inputText("")
    }

    /**
     * Perform system actions like BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT.
     */
    fun performSystem(action: String): Boolean {
        val act = action.lowercase().trim()
        val globalAction = when (act) {
            "back", "နောက်ဆုတ်", "အနောက်သို့" -> GLOBAL_ACTION_BACK
            "home", "မူလမျက်နှာပြင်" -> GLOBAL_ACTION_HOME
            "recents", "recent_apps", "လတ်တလောသုံးအက်ပ်များ", "ရှေ့တိုး" -> GLOBAL_ACTION_RECENTS
            "notifications", "နိုတီ", "အသိပေးချက်များ" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "အမြန်ဆက်တင်များ" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog", "ပါဝါ" -> GLOBAL_ACTION_POWER_DIALOG
            "split_screen", "စခရင်နှစ်ခြမ်းခွဲ" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "lock_screen", "စခရင်ပိတ်" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "take_screenshot", "screenshot", "စခရင်ရှော့" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            "dismiss_notifications" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE else return false
            else -> return false
        }
        return performGlobalAction(globalAction)
    }

    /**
     * Traverses the active window node hierarchy, indexes interactive elements with [#1], [#2],
     * and dumps all visible text nodes and interactive elements into a structured string.
     */
    fun dumpScreenContents(): String {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "dumpScreenContents: rootInActiveWindow is null")
            return "မျက်နှာပြင်တွင် စာသားများ မတွေ့ရပါ သို့မဟုတ် Accessibility Service ခွင့်ပြုချက် ပိတ်ထားသည်။"
        }
        
        cachedElements.clear()
        val sb = java.lang.StringBuilder()
        
        val activePackage = root.packageName?.toString() ?: ""
        if (activePackage.isNotBlank()) {
            sb.append("📱 [Active App: $activePackage]\n")
        }
        
        traverseAndDump(root, sb, 0)
        root.recycle()
        
        val result = sb.toString().trim()
        return if (result.isEmpty()) "မျက်နှာပြင်တွင် စာသားများ မတွေ့ရပါ။" else result
    }

    private fun traverseAndDump(node: AccessibilityNodeInfo?, sb: java.lang.StringBuilder, depth: Int) {
        if (node == null) return
        
        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val hintText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) node.hintText?.toString()?.trim() else null
        val resId = node.viewIdResourceName?.toString()?.substringAfterLast("/")?.trim()
        val className = node.className?.toString()?.substringAfterLast(".")?.trim() ?: "View"
        
        val displayText = when {
            !text.isNullOrBlank() -> text
            !contentDesc.isNullOrBlank() -> contentDesc
            !hintText.isNullOrBlank() -> "Hint: $hintText"
            !resId.isNullOrBlank() -> "[$resId]"
            else -> null
        }
        
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable
        
        if (displayText != null || isInteractive) {
            val flags = mutableListOf<String>()
            if (node.isClickable) flags.add("Clickable")
            if (node.isEditable) flags.add("Editable")
            if (node.isScrollable) flags.add("Scrollable")
            if (node.isCheckable) flags.add(if (node.isChecked) "Checked" else "Unchecked")
            if (node.isSelected) flags.add("Selected")
            if (node.isFocused) flags.add("Focused")
            
            val rect = Rect()
            node.getBoundsInScreen(rect)
            
            val elementIndex = cachedElements.size + 1
            cachedElements.add(
                ScreenElement(
                    index = elementIndex,
                    text = text ?: "",
                    contentDesc = contentDesc,
                    resourceId = resId,
                    className = className,
                    bounds = rect,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable
                )
            )
            
            val label = displayText ?: "[$className]"
            val boundsStr = " [Pos: ${rect.centerX()},${rect.centerY()}]"
            val flagStr = if (flags.isNotEmpty()) " [${flags.joinToString(", ")}]" else ""
            val indent = "  ".repeat(depth.coerceAtMost(3))
            
            sb.append("$indent[#$elementIndex] $label ($className)$flagStr$boundsStr\n")
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseAndDump(child, sb, depth + 1)
            child.recycle()
        }
    }
}

