package com.example.voicebrainlive.commands.handlers

import android.content.Context
import com.example.voicebrainlive.VoiceBrainAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AccessibilityHandler - Executed commands related to Voice Access/UI control
 * using AccessibilityService.
 */
class AccessibilityHandler(private val context: Context) : CommandHandler {

    override fun getSupportedCommands(): List<String> = listOf(
        "click_text",
        "long_click",
        "double_click",
        "scroll_screen",
        "input_text",
        "clear_text",
        "perform_system_action",
        "read_screen_contents",
        "click_at",
        "swipe_gesture"
    )

    override suspend fun execute(
        commandType: String,
        target: String?,
        value: String?
    ): String? = withContext(Dispatchers.Main) {
        val service = VoiceBrainAccessibilityService.instance
        if (service == null) {
            return@withContext "အသံထိန်းချုပ်မှု လုပ်ဆောင်ချက်ကို သုံးနိုင်ရန် ဖုန်း Settings > Accessibility တွင် Voice Brain - Voice Access Service ကို ဖွင့်ပေးပါ။"
        }

        when (commandType.lowercase().trim()) {
            "click_text" -> {
                val text = target ?: value ?: return@withContext "နှိပ်မည့်စာသား သို့မဟုတ် နံပါတ် (#1, #2) ပြောပေးပါ။"
                val ok = service.clickText(text)
                if (ok) {
                    "စခရင်ပေါ်ရှိ \"$text\" ကို နှိပ်လိုက်ပါပြီ။"
                } else {
                    val currentScreenDump = service.dumpScreenContents()
                    "စခရင်ပေါ်တွင် \"$text\" ကို တိုက်ရိုက် ရှာမတွေ့ပါ။ စခရင်ပေါ်ရှိ လက်ရှိအရာများ:\n$currentScreenDump"
                }
            }
            "long_click" -> {
                val text = target ?: value ?: return@withContext "ဖိနှိပ်မည့် စာသား သို့မဟုတ် နံပါတ် ပြောပေးပါ။"
                val ok = service.longClickText(text)
                if (ok) "စခရင်ပေါ်ရှိ \"$text\" ကို ဖိနှိပ် (Long Press) လိုက်ပါပြီ။"
                else "စခရင်ပေါ်တွင် \"$text\" ကို ရှာမတွေ့ပါ။"
            }
            "double_click" -> {
                val coords = (target ?: value)?.split(",")
                if (coords != null && coords.size == 2) {
                    val x = coords[0].trim().toFloatOrNull()
                    val y = coords[1].trim().toFloatOrNull()
                    if (x != null && y != null) {
                        val ok = service.doubleClickAt(x, y)
                        if (ok) "Double clicked at $x, $y" else "Failed to double click"
                    } else "Invalid coordinates"
                } else {
                    val text = target ?: value ?: return@withContext "နှစ်ချက်နှိပ်မည့် အရာ ပြောပေးပါ။"
                    service.clickText(text)
                    service.clickText(text)
                    "\"$text\" ကို နှစ်ချက် နှိပ်လိုက်ပါပြီ။"
                }
            }
            "scroll_screen" -> {
                val dir = target ?: value ?: "down"
                val ok = service.scroll(dir)
                val dirMy = when (dir.lowercase().trim()) {
                    "up", "backward" -> "အပေါ်သို့"
                    "down", "forward" -> "အောက်သို့"
                    "left" -> "ဘယ်ဘက်သို့"
                    "right" -> "ညာဘက်သို့"
                    "top" -> "အပေါ်ဆုံးထိ"
                    "bottom" -> "အောက်ဆုံးထိ"
                    else -> dir
                }
                if (ok) "စခရင်ကို $dirMy ရွှေ့လိုက်ပါပြီ။" 
                else "ရွှေ့စရာ စခရင် မတွေ့ပါ။"
            }
            "input_text" -> {
                val text = value.takeIf { !it.isNullOrBlank() } ?: target ?: return@withContext "ရိုက်ထည့်မည့်စာသား ပြောပေးပါ။"
                val targetField = if (!value.isNullOrBlank()) target else null
                val ok = service.inputText(text, targetField)
                if (ok) "\"$text\" ကို ရိုက်ထည့်လိုက်ပါပြီ။" 
                else "စာရိုက်ရန် နေရာ မတွေ့ပါ။"
            }
            "clear_text" -> {
                val ok = service.clearText()
                if (ok) "စာသားများကို ရှင်းလင်းလိုက်ပါပြီ။"
                else "ရှင်းလင်းစရာ နေရာ မတွေ့ပါ။"
            }
            "perform_system_action" -> {
                val action = target ?: value ?: return@withContext "လုပ်ဆောင်ချက် မမှန်ပါ။"
                val ok = service.performSystem(action)
                val actionMy = when (action.lowercase().trim()) {
                    "back", "နောက်ဆုတ်", "အနောက်သို့" -> "အနောက်သို့"
                    "home", "မူလမျက်နှာပြင်" -> "မူလမျက်နှာပြင်သို့"
                    "recents", "recent_apps", "လတ်တလောသုံးအက်ပ်များ", "ရှေ့တိုး" -> "လတ်တလောသုံးအက်ပ်များ"
                    "notifications", "နိုတီ", "အသိပေးချက်များ" -> "အသိပေးချက်များ"
                    "quick_settings", "အမြန်ဆက်တင်များ" -> "အမြန်ဆက်တင်များ"
                    "power_dialog", "ပါဝါ" -> "ပါဝါရွေးချယ်မှု"
                    "split_screen", "စခရင်နှစ်ခြမ်းခွဲ" -> "စခရင်နှစ်ခြမ်းခွဲခြင်း"
                    "lock_screen", "စခရင်ပိတ်" -> "စခရင်ပိတ်ခြင်း"
                    "take_screenshot", "screenshot", "စခရင်ရှော့" -> "စခရင်ရှော့ရိုက်ခြင်း"
                    else -> action
                }
                if (ok) "$actionMy လုပ်ဆောင်လိုက်ပါပြီ။" 
                else "$actionMy လုပ်ဆောင်ရန် မအောင်မြင်ပါ။"
            }
            "read_screen_contents" -> {
                service.dumpScreenContents()
            }
            "click_at" -> {
                val coords = (target ?: value)?.split(",")
                if (coords != null && coords.size == 2) {
                    val x = coords[0].trim().toFloatOrNull()
                    val y = coords[1].trim().toFloatOrNull()
                    if (x != null && y != null) {
                        val ok = service.clickAt(x, y)
                        if (ok) "Screen clicked at $x, $y" else "Failed to click at $x, $y"
                    } else {
                        "Invalid coordinates provided. Use format x,y"
                    }
                } else {
                    "Invalid coordinates provided. Use format x,y"
                }
            }
            "swipe_gesture" -> {
                val coords = (target ?: value)?.split(",")
                if (coords != null && coords.size == 4) {
                    val startX = coords[0].trim().toFloatOrNull()
                    val startY = coords[1].trim().toFloatOrNull()
                    val endX = coords[2].trim().toFloatOrNull()
                    val endY = coords[3].trim().toFloatOrNull()
                    if (startX != null && startY != null && endX != null && endY != null) {
                        val ok = service.swipe(startX, startY, endX, endY)
                        if (ok) "Swiped from $startX,$startY to $endX,$endY" else "Failed to perform swipe"
                    } else {
                        "Invalid coordinates provided. Use format startX,startY,endX,endY"
                    }
                } else {
                    "Invalid coordinates provided. Use format startX,startY,endX,endY"
                }
            }
            else -> null
        }
    }
}
