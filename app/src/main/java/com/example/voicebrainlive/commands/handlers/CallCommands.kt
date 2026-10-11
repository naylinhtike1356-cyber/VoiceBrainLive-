package com.example.voicebrainlive.commands.handlers

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.voicebrainlive.memory.BehaviorMemory
import kotlin.math.max

/**
 * Phase 2: Call and Contact management modularized from PhoneCommandExecutor.
 * Prioritizes quick contacts, learns user pronunciation/corrections, and avoids repeat confirmations.
 */
class CallCommands(private val context: Context) : CommandHandler {

    private val prefs = context.getSharedPreferences("quick_contacts", Context.MODE_PRIVATE)

    companion object {
        private var pendingCallName: String? = null
        private var pendingCallNumber: String? = null
        private var pendingPhoneSearchQuery: String? = null
    }

    // Construction/search must not copy or clear user data. Legacy contacts are read in place.

    override fun getSupportedCommands(): List<String> = listOf(
        "make_call", "call", "dial", "call_contact", "call_quick_contact", "contact_call",
        "add_contact", "save_contact", "add_quick_contact",
        "edit_contact", "edit_quick_contact",
        "list_contacts", "get_contacts", "delete_contact",
        "search_phone_contacts", "call_phone_contact",
        "speaker_on", "speaker_off", "mute_call", "unmute_call", "end_call"
    )

    private val approvalSession = java.util.UUID.randomUUID().toString()
    override suspend fun execute(commandType: String, target: String?, value: String?): String? =
        com.example.voicebrainlive.commands.SensitiveActionGuard.run(context, approvalSession, commandType, target, value) {
            executeUnchecked(commandType, target, value).orEmpty()
        }

    private suspend fun executeUnchecked(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val effectiveTarget = target.takeIf { !it.isNullOrBlank() } ?: value.takeIf { !it.isNullOrBlank() } ?: ""
        when (commandType.lowercase()) {
            "make_call", "call", "dial", "call_contact", "call_quick_contact", "contact_call" ->
                makeCall(effectiveTarget)
            "search_phone_contacts" -> searchPhoneContacts(effectiveTarget)
            "call_phone_contact" -> callFromPhoneContacts(effectiveTarget)
            "add_contact", "save_contact", "add_quick_contact" -> addQuickContact(target.takeIf { !it.isNullOrBlank() } ?: "", value.takeIf { !it.isNullOrBlank() } ?: "")
            "edit_contact", "edit_quick_contact" -> editQuickContact(target.takeIf { !it.isNullOrBlank() } ?: "", value.takeIf { !it.isNullOrBlank() } ?: "")
            "list_contacts", "get_contacts" -> listQuickContacts()
            "delete_contact" -> deleteQuickContact(effectiveTarget)
            "speaker_on" -> setSpeakerphone(true)
            "speaker_off" -> setSpeakerphone(false)
            "mute_call" -> setCallMute(true)
            "unmute_call" -> setCallMute(false)
            "end_call" -> endActiveCall()
            else -> null
        }
    }

    private fun extractNameFromNegation(input: String): String {
        val negationPattern = Regex(
            "(?i)\\b(no|cancel|stop|don't call|do not call|instead)\\b|" +
            "မဟုတ်ဘူး|မဟုတ်|မခေါ်နဲ့တော့|မခေါ်နဲ့|မဆက်နဲ့တော့|မဆက်နဲ့|မခေါ်တော့ဘူး|မဆက်တော့ဘူး|ဖျက်လိုက်ပါ|ဖျက်လိုက်|မလုပ်နဲ့တော့|မလုပ်နဲ့",
            RegexOption.IGNORE_CASE
        )
        var cleared = input.replace(negationPattern, " ")
        
        val actionPattern = Regex(
            "(?i)\\b(call|dial)\\b|" +
            "ဖုန်းခေါ်ပေးပါ|ဖုန်းခေါ်ပေး|ဖုန်းခေါ်ပါ|ဖုန်းခေါ်|ခေါ်ပေးပါ|ခေါ်ပေး|ခေါ်လိုက်ပါ|ခေါ်လိုက်|ခေါ်ပါ|ခေါ်|ဖုန်းဆက်ပေးပါ|ဖုန်းဆက်ပေး|ဖုန်းဆက်ပါ|ဖုန်းဆက်|ဆက်ပေးပါ|ဆက်ပေး|ဆက်လိုက်ပါ|ဆက်လိုက်|ဆက်ပါ|ဆက်",
            RegexOption.IGNORE_CASE
        )
        cleared = cleared.replace(actionPattern, " ")
        cleared = cleared.replace(Regex("[,.?!_\\-+]"), " ")
        cleared = cleared.replace(Regex("\\s+"), " ").trim()
        
        // Clean up trailing politeness/aspect/sentence-ending particles in Burmese recursively
        val trailingParticles = listOf(
            "လိုက်ပါဦး", "လိုက်ဦးမယ်", "လိုက်ဦး", "ပါဦး", "ဦးပါ", "လိုက်ပါ", 
            "ဦးမယ်", "ဦး", "ပါ", "နော်", "စမ်း", "လိုက်စမ်း", "ဟေ့"
        )
        var stable = true
        while (stable) {
            val before = cleared
            for (particle in trailingParticles) {
                if (cleared.endsWith(particle) && cleared.length > particle.length + 1) {
                    cleared = cleared.removeSuffix(particle).trim()
                }
            }
            if (cleared.endsWith("ဆီသို့") && cleared.length > "ဆီသို့".length + 1) {
                cleared = cleared.removeSuffix("ဆီသို့").trim()
            }
            if (cleared.endsWith("ကို") && cleared.length > "ကို".length + 1) {
                cleared = cleared.removeSuffix("ကို").trim()
            }
            if (cleared.endsWith("ဆီ") && cleared.length > "ဆီ".length + 1) {
                cleared = cleared.removeSuffix("ဆီ").trim()
            }
            if (cleared.endsWith("အား") && cleared.length > "အား".length + 1) {
                cleared = cleared.removeSuffix("အား").trim()
            }
            if (cleared == before) {
                stable = false
            }
        }
        return cleared
    }


    private fun isPositiveResponse(input: String): Boolean {
        val lower = input.lowercase().trim()
        val userUtterance = com.example.voicebrainlive.learning.UserUtteranceContext.peek()?.lowercase() ?: ""
        return lower == "yes" || lower == "y" || lower == "ok" || lower == "okay" ||
               lower.contains("ဟုတ်") || lower.contains("ခေါ်") || lower.contains("ဆက်") ||
               lower.contains("ပို့") || lower.contains("အေး") || lower.contains("အင်း") ||
               userUtterance == "yes" || userUtterance == "y" || userUtterance == "ok" || userUtterance == "okay" ||
               userUtterance.contains("ဟုတ်") || userUtterance.contains("ခေါ်") || userUtterance.contains("ဆက်") ||
               userUtterance.contains("ပို့") || userUtterance.contains("အေး") || userUtterance.contains("အင်း")
    }

    private fun isNegativeResponse(input: String): Boolean {
        val lower = input.lowercase().trim()
        val userUtterance = com.example.voicebrainlive.learning.UserUtteranceContext.peek()?.lowercase() ?: ""
        return lower == "no" || lower == "n" || lower == "cancel" ||
               lower.contains("မခေါ်") || lower.contains("မဆက်") || lower.contains("မပို့") ||
               lower.contains("မဟုတ်") || lower.contains("မလုပ်") || lower.contains("ဖျက်") ||
               userUtterance == "no" || userUtterance == "n" || userUtterance == "cancel" ||
               userUtterance.contains("မခေါ်") || userUtterance.contains("မဆက်") || userUtterance.contains("မပို့") ||
               userUtterance.contains("မဟုတ်") || userUtterance.contains("မလုပ်") || userUtterance.contains("ဖျက်")
    }

    private fun cleanCallQuery(input: String): String {
        var cleared = removeContactSourceModifier(input)
        val actionPattern = Regex(
            "(?i)\\b(call|dial|phone to|phone)\\b|" +
            "ဖုန်းခေါ်ပေးပါ|ဖုန်းခေါ်ပေး|ဖုန်းခေါ်ပါ|ဖုန်းခေါ်|ခေါ်ပေးပါ|ခေါ်ပေး|ခေါ်လိုက်ပါ|ခေါ်လိုက်|ခေါ်ပါ|ခေါ်|ဖုန်းဆက်ပေးပါ|ဖုန်းဆက်ပေး|ဖုန်းဆက်ပါ|ဖုန်းဆက်|ဆက်ပေးပါ|ဆက်ပေး|ဆက်လိုက်ပါ|ဆက်လိုက်|ဆက်ပါ|ဆက်",
            RegexOption.IGNORE_CASE
        )
        cleared = cleared.replace(actionPattern, " ")
        cleared = cleared.replace(Regex("[,.?!_\\-+]"), " ")
        cleared = cleared.replace(Regex("\\s+"), " ").trim()
        
        // Strip trailing politeness/aspect/sentence-ending particles in Burmese recursively
        val trailingParticles = listOf(
            "လိုက်ပါဦး", "လိုက်ဦးမယ်", "လိုက်ဦး", "ပါဦး", "ဦးပါ", "လိုက်ပါ", 
            "ဦးမယ်", "ဦး", "ပါ", "နော်", "စမ်း", "လိုက်စမ်း", "ဟေ့"
        )
        var stable = true
        while (stable) {
            val before = cleared
            for (particle in trailingParticles) {
                if (cleared.endsWith(particle) && cleared.length > particle.length + 1) {
                    cleared = cleared.removeSuffix(particle).trim()
                }
            }
            if (cleared.endsWith("ဆီသို့") && cleared.length > "ဆီသို့".length + 1) {
                cleared = cleared.removeSuffix("ဆီသို့").trim()
            }
            if (cleared.endsWith("ကို") && cleared.length > "ကို".length + 1) {
                cleared = cleared.removeSuffix("ကို").trim()
            }
            if (cleared.endsWith("ဆီ") && cleared.length > "ဆီ".length + 1) {
                cleared = cleared.removeSuffix("ဆီ").trim()
            }
            if (cleared.endsWith("အား") && cleared.length > "အား".length + 1) {
                cleared = cleared.removeSuffix("အား").trim()
            }
            if (cleared == before) {
                stable = false
            }
        }
        return cleared
    }

    /** Phase 3: "ဒီလူ" / "that person" / "သူ့ကို" = last called/interacted contact */
    private fun isThatPersonReference(s: String): Boolean {
        val t = s.trim().lowercase()
        if (t.length > 20) return false
        return t in listOf(
            "ဒီလူ", "ဒီလူကို", "သူ", "သူ့ကို", "သူမကို", "ပြန်ခေါ်", "ပြန်ခေါ်ပေး", "ပြန်ဆက်ပေး", "ပြန်ဆက်",
            "that person", "that guy", "him", "her", "call again",
            "သူ့", "အဲ့ဒီလူ", "အဲ့ဒီလူကို", "ဒီအစ်ကို", "ဒီအစ်မ", "ဒီဆရာ"
        )
    }

    private fun removeContactSourceModifier(input: String): String {
        return input.replace(Regex("\\b(phone contact|device contact|ဖုန်း contact)\\b", RegexOption.IGNORE_CASE), "").trim()
    }

    private fun makeCall(phoneOrName: String): String {
        val testStartTime = System.currentTimeMillis()
        if (phoneOrName.isBlank() && pendingPhoneSearchQuery == null && pendingCallNumber == null) {
            return "ဘယ်သူ့ကို ဖုန်းခေါ်ဆိုပေးရမလဲ? ကျေးဇူးပြု၍ ကွန်တက်အမည် သို့မဟုတ် ဖုန်းနံပါတ် ပြောပေးပါ။"
        }

        val spokenInput = phoneOrName.trim()
        val lowerInput = spokenInput.lowercase()

        val isNegative = isNegativeResponse(spokenInput)
        val isPositive = isPositiveResponse(spokenInput)

        // Handle pending phone contact search confirmation first!
        if (pendingPhoneSearchQuery != null) {
            val query = pendingPhoneSearchQuery!!
            if (isPositive) {
                pendingPhoneSearchQuery = null
                val matches = findInPhoneContacts(query)
                if (matches.isEmpty()) {
                    return "Phone contacts ထဲမှာလည်း '$query' ကို ရှာမတွေ့ပါဘူး။"
                }
                val topMatch = matches.first()
                val topConfidence = topMatch.confidence
                
                val closeMatches = matches.filter { it.confidence >= topConfidence - 8 }
                if (closeMatches.size > 1) {
                    val options = closeMatches.take(3).map { match ->
                        val hasDuplicateName = closeMatches.count { it.name == match.name } > 1
                        if (hasDuplicateName) {
                            "${match.name} (${match.number})"
                        } else {
                            match.name
                        }
                    }.joinToString(" သို့မဟုတ် ")
                    return "Phone contacts ထဲမှာ '$query' နဲ့ ပတ်သက်ပြီး ဆင်တူသူများ တွေ့ရပါသည်: $options။ ဘယ်သူ့ကို ခေါ်ဆိုချင်ပါသလဲ?"
                }
                
                pendingCallName = topMatch.name
                pendingCallNumber = topMatch.number
                return "Phone contacts ထဲရှိ '${topMatch.name}' ကို ဖုန်းခေါ်ရမလား? (ဟုတ်တယ် သို့မဟုတ် မဟုတ်ဘူး ဟု ပြောပါ)"
            } else if (isNegative) {
                pendingPhoneSearchQuery = null
                val remainingInput = extractNameFromNegation(spokenInput)
                if (remainingInput.isNotBlank()) {
                    return makeCall(remainingInput)
                }
                return "ဖုန်းခေါ်ဆိုမှုကို ဖျက်သိမ်းလိုက်ပါပြီ။"
            } else {
                // If it is a completely new call name (e.g. they say a name instead of yes/no),
                // clear the pending search and treat as a new makeCall request
                pendingPhoneSearchQuery = null
            }
        }

        // Handle pending call dial confirmation
        if (pendingCallNumber != null) {
            if (isPositive) {
                val name = pendingCallName ?: pendingCallNumber ?: ""
                val num = pendingCallNumber!!
                pendingCallName = null
                pendingCallNumber = null
                return placeCall(num, name, name, 100, true, testStartTime, "Confirmed Device Contact")
            } else if (isNegative) {
                val remainingInput = extractNameFromNegation(spokenInput)
                pendingCallName = null
                pendingCallNumber = null
                if (remainingInput.isNotBlank()) {
                    return makeCall(remainingInput)
                }
                return "ဖုန်းခေါ်ဆိုမှုကို ဖျက်သိမ်းလိုက်ပါပြီ။"
            }
        }

        // Emergency numbers list
        val emergencyNumbers = setOf("911", "119", "112", "199", "191", "192", "999")
        val isEmergencyInput = emergencyNumbers.contains(spokenInput.replace(Regex("[^0-9]"), ""))

        if (isEmergencyInput) {
            return placeCall(spokenInput.replace(Regex("[^0-9]"), ""), "အရေးပေါ်ဖုန်း", spokenInput, 100, true, testStartTime, "Emergency")
        }

        BehaviorMemory.recordCallAttempt(context, spokenInput)

        // Step 1: Clean action words and check remembered alias/correction
        var currentInput = spokenInput
        if (currentInput.isNotBlank() && isThatPersonReference(currentInput)) {
            val lastCalled = BehaviorMemory.getLastCalledContact(context)
            if (lastCalled != null) {
                currentInput = lastCalled
            }
        }
        val cleanedInput = cleanCallQuery(currentInput)
        val rememberedTarget = BehaviorMemory.getRememberedCallAlias(context, cleanedInput)
            ?: BehaviorMemory.getCorrectedCallTarget(context, cleanedInput)
        val effectiveInput = rememberedTarget ?: cleanedInput

        // Safety Guard: If query is blank or single-character noise (not an index or number), refuse to dial!
        if (effectiveInput.isBlank() || (effectiveInput.length < 2 && parseIndexQuery(effectiveInput) == null)) {
            return "ဘယ်သူ့ကို ဖုန်းခေါ်ဆိုပေးရမလဲ? ကျေးဇူးပြု၍ ကွန်တက်အမည် သို့မဟုတ် ဖုန်းနံပါတ် ပြောပေးပါ။"
        }

        // Check if user requested a Quick Contact by 1-based Index (e.g. "1", "2", "နံပါတ် 1", "၁")
        val quickList = prefs.all.entries
            .filter { it.value is String }
            .map { Pair(it.key, it.value as String) }
            .sortedBy { it.first }

        val requestedIndex = parseIndexQuery(effectiveInput)
        if (requestedIndex != null && requestedIndex in 1..quickList.size) {
            val (targetName, targetNum) = quickList[requestedIndex - 1]
            return placeCall(targetNum, targetName, spokenInput, 100, true, testStartTime, "Quick Contact Index #$requestedIndex")
        }

        // Step 2: If numeric or USSD code, call directly
        val numericInput = effectiveInput.replace(Regex("[^0-9+*#]"), "")
        if (numericInput.length >= 3 && emergencyNumbers.contains(numericInput.replace(Regex("[^0-9]"), ""))) {
            return placeCall(numericInput.replace(Regex("[^0-9]"), ""), "အရေးပေါ်ဖုန်း", spokenInput, 100, true, testStartTime, "Emergency")
        }
        if (numericInput.length >= 5 || numericInput.contains("*") || numericInput.contains("#")) {
            return placeCall(normalizePhoneNumber(numericInput), effectiveInput, spokenInput, 100, rememberedTarget != null, testStartTime, "Raw Number")
        }

        // Step 3: Search Quick Contacts and Device Contacts together
        val matches = findContactsScored(effectiveInput)

        if (matches.isNotEmpty()) {
            val topMatch = matches.first()
            val topConfidence = topMatch.confidence
            
            // Check for ambiguity (multiple close matches with confidence within 8 points of topConfidence)
            val closeMatches = matches.filter { it.confidence >= topConfidence - 8 }
            if (closeMatches.size > 1) {
                val options = closeMatches.take(3).mapIndexed { idx, match ->
                    val hasDuplicateName = closeMatches.count { it.name == match.name } > 1
                    val typeLabel = if (match.type == ContactType.QUICK) "အမြန်" else "ဖုန်း"
                    if (hasDuplicateName) {
                        "${idx + 1}. ${match.name} (${match.number} - $typeLabel)"
                    } else {
                        "${idx + 1}. ${match.name} ($typeLabel)"
                    }
                }.joinToString(" သို့မဟုတ် ")
                return "ကွန်တက်ထဲမှာ '$spokenInput' နဲ့ ပတ်သက်ပြီး ဆင်တူသူများ တွေ့ရပါသည်: $options။ ဘယ်သူ့ကို ခေါ်ဆိုချင်ပါသလဲ?"
            }
            
            if (topMatch.type == ContactType.QUICK) {
                // Call Quick Contact directly!
                return placeCall(topMatch.number, topMatch.name, spokenInput, topConfidence, true, testStartTime, "Quick Contact")
            } else {
                // Device Contact
                val isPreviouslyConfirmed = topMatch.learnedAlias || (topMatch.previouslyCalled && topConfidence >= 90)
                if (isPreviouslyConfirmed) {
                    return placeCall(topMatch.number, topMatch.name, spokenInput, topConfidence, true, testStartTime, "Device Contact")
                } else {
                    pendingCallName = topMatch.name
                    pendingCallNumber = topMatch.number
                    return "ဖုန်း Contacts ထဲရှိ '${topMatch.name}' ကို ဖုန်းခေါ်ရမလား? (ဟုတ်တယ် သို့မဟုတ် မဟုတ်ဘူး ဟု ပြောပါ)"
                }
            }
        } else {
            val quickContactsFormatted = quickList.mapIndexed { idx, pair -> "${idx + 1}. ${pair.first}: ${pair.second}" }.joinToString("\n")
            val listInfo = if (quickContactsFormatted.isNotBlank())
                "\nသင့်ရဲ့ အမြန်ကွန်တက်များ:\n$quickContactsFormatted"
            else
                ""
            return "ကွန်တက်ထဲမှာ '$spokenInput' ကို ရှာမတွေ့ပါ။$listInfo"
        }
    }

    private fun parseIndexQuery(input: String): Int? {
        var s = input.lowercase().trim()
            .replace("၀", "0").replace("၁", "1").replace("၂", "2")
            .replace("၃", "3").replace("၄", "4").replace("၅", "5")
            .replace("၆", "6").replace("၇", "7").replace("၈", "8").replace("၉", "9")
            .replace(Regex("(?i)\\b(number|no\\.?|နံပါတ်|အမှတ်|contact|ကွန်တက်|ဖုန်း|ပါ|ပေးပါ)\\b"), " ")
            .replace(Regex("[^0-9]"), "").trim()
        return s.toIntOrNull()
    }

    private fun placeCall(
        number: String,
        displayName: String,
        spokenInput: String,
        confidencePercent: Int,
        confirmedAlias: Boolean,
        testStartTime: Long = 0L,
        contactType: String = "Raw Number"
    ): String {
        if (com.example.voicebrainlive.SettingsActivity.isTestCallMode(context)) {
            val totalTime = if (testStartTime > 0L) System.currentTimeMillis() - testStartTime else 0L
            val speedMsg = if (totalTime > 0L) "သတ်မှတ်ချိန်: ${totalTime}ms" else "သတ်မှတ်ချိန်: N/A"
            val confidenceMsg = "တိကျမှု: ${confidencePercent}%"
            val typeMsg = "အမျိုးအစား: $contactType"
            
            Log.d("CallCommands", "Test Call Simulated: $displayName ($number), Confidence: $confidencePercent%, Speed: ${totalTime}ms, Type: $contactType")
            
            com.example.voicebrainlive.ActionRecordManager.recordAction(
                context, 
                "TestCall", 
                "Simulated call to $displayName ($number). Confidence: $confidencePercent%, Speed: ${totalTime}ms, Type: $contactType"
            )
            
            return "📞 [စမ်းသပ်ဖုန်းခေါ်ဆိုမှု]\n" +
                   "• အမည်: $displayName ($number)\n" +
                   "• $confidenceMsg (Confidence)\n" +
                   "• $speedMsg (Speed)\n" +
                   "• $typeMsg\n" +
                   "• စမ်းသပ်မုဒ်ဖြစ်၍ ဖုန်းတကယ်ခေါ်မည်မဟုတ်ပါ။"
        }
        val callUri = Uri.parse("tel:${number.replace("#", "%23")}")
        val launched = try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null) {
                    telecomManager.placeCall(callUri, null)
                    true
                } else {
                    val callIntent = Intent(Intent.ACTION_CALL).apply {
                        data = callUri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(callIntent)
                    true
                }
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = callUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                true
            }
        } catch (e: Exception) {
            Log.e("CallCommands", "makeCall error: ${e.message}")
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = callUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                true
            } catch (dialError: Exception) {
                Log.e("CallCommands", "dial fallback error: ${dialError.message}")
                false
            }
        }

        if (!launched) return "ဖုန်းခေါ်မပေးနိုင်သေးဘူး။ Dialer ဖွင့်လို့မရခဲ့ပါ။"

        BehaviorMemory.recordCall(context, displayName)
        val shouldRememberAlias = confirmedAlias ||
            spokenInput.equals(displayName, ignoreCase = true).not() ||
            confidencePercent >= 86
        if (shouldRememberAlias) {
            BehaviorMemory.rememberCallAlias(
                context = context,
                spokenInput = spokenInput,
                resolvedTarget = displayName,
                confidencePercent = confidencePercent,
                confirmedByUser = confirmedAlias || confidencePercent >= 94
            )
        }

        return if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            "$displayName ကို ဖုန်းခေါ်နေပါပြီ။"
        } else {
            "$displayName ကို ခေါ်ဖို့ dialer ဖွင့်ပေးလိုက်ပါပြီ။"
        }
    }

    private fun addQuickContact(nameArg: String, numberArg: String): String {
        var name = nameArg.trim()
        var number = numberArg.trim()

        // If number is in name or vice versa (e.g. "ကိုထေး 0912345678" passed as single argument)
        if (number.isBlank() && name.isNotBlank()) {
            val phoneMatch = Regex("""(\+?[0-9]{5,15})""").find(name)
            if (phoneMatch != null) {
                number = phoneMatch.value
                name = name.replace(phoneMatch.value, "").trim()
            }
        } else if (name.isBlank() && number.isNotBlank()) {
            val phoneMatch = Regex("""(\+?[0-9]{5,15})""").find(number)
            if (phoneMatch != null) {
                val foundNum = phoneMatch.value
                name = number.replace(foundNum, "").trim()
                number = foundNum
            }
        }

        // Clean Burmese helper words from name
        name = cleanContactActionWords(name)
        val cleanNumber = normalizePhoneNumber(number.replace(Regex("[^0-9+]"), ""))

        if (name.isBlank() || cleanNumber.isBlank() || cleanNumber.length < 3) {
            return "အမြန်ကွန်တက် မှတ်ရန် ကွန်တက်အမည်နှင့် ဖုန်းနံပါတ် နှစ်ခုလုံး ပြောပေးပါ (ဥပမာ- 'ကိုထေး 0912345678 ကို မှတ်ပေးပါ')။"
        }

        com.example.voicebrainlive.commands.QuickContactStore.write(prefs) { putString(name, cleanNumber) }
        return "အမြန်ကွန်တက် (Quick Contact) ထဲတွင် '$name' ($cleanNumber) ကို အောင်မြင်စွာ မှတ်ထားလိုက်ပါပြီ။"
    }

    private fun editQuickContact(nameArg: String, numberArg: String): String {
        var name = nameArg.trim()
        var number = numberArg.trim()

        if (number.isBlank() && name.isNotBlank()) {
            val phoneMatch = Regex("""(\+?[0-9]{5,15})""").find(name)
            if (phoneMatch != null) {
                number = phoneMatch.value
                name = name.replace(phoneMatch.value, "").trim()
            }
        }

        name = cleanContactActionWords(name)
        val cleanNumber = normalizePhoneNumber(number.replace(Regex("[^0-9+]"), ""))

        if (name.isBlank()) {
            return "မည်သည့် အမြန်ကွန်တက်ကို ပြင်ဆင်လိုပါသလဲ အမည် ပြောပေးပါ။"
        }

        val matches = findQuickContacts(name)
        if (matches.isEmpty()) {
            val quickList = prefs.all.entries.filter { it.value is String }.map { it.key }.joinToString(", ")
            val listMsg = if (quickList.isNotBlank()) " လက်ရှိကွန်တက်များ: $quickList" else ""
            return "အမြန်ကွန်တက်ထဲတွင် '$name' ကို ရှာမတွေ့ပါ။$listMsg"
        }

        if (cleanNumber.isBlank() || cleanNumber.length < 3) {
            return "'${matches.first().name}' အတွက် ပြင်ဆင်လိုသော ဖုန်းနံပါတ်အသစ်ကို ပြောပေးပါ။"
        }

        val key = matches.first().name
        com.example.voicebrainlive.commands.QuickContactStore.write(prefs) { putString(key, cleanNumber) }
        return "အမြန်ကွန်တက် '$key' ၏ ဖုန်းနံပါတ်ကို '$cleanNumber' သို့ အောင်မြင်စွာ ပြင်ဆင်ပြီးပါပြီ။"
    }

    private fun listQuickContacts(): String {
        val all = synchronized(com.example.voicebrainlive.commands.QuickContactStore.lock) {
            (context.getSharedPreferences("voice_contacts", Context.MODE_PRIVATE).all + prefs.all).entries.toList()
        }
            .filter { it.value is String }
            .map { Pair(it.key, it.value as String) }
            .sortedBy { it.first }

        if (all.isEmpty()) {
            return "အမြန်ကွန်တက် (Quick Contacts) မှတ်ထားသည်များ မရှိသေးပါ။ 'ကိုထေး 0912345678 ကို မှတ်ပေးပါ' ဟု ပြောပြီး မှတ်နိုင်ပါသည်။"
        }

        val quickList = all.mapIndexed { idx, pair -> "${idx + 1}. ${pair.first}: ${pair.second}" }.joinToString("\n")
        return "📱 သင့်ရဲ့ အမြန်ကွန်တက်များ (Quick Contacts):\n$quickList"
    }

    private fun deleteQuickContact(nameArg: String): String {
        var name = cleanContactActionWords(nameArg.trim())
        if (name.isBlank()) {
            return "မည်သည့် အမြန်ကွန်တက်ကို ဖျက်လိုပါသလဲ အမည် ပြောပေးပါ။"
        }

        val matches = findQuickContacts(name)
        if (matches.isEmpty()) {
            val quickList = prefs.all.entries.filter { it.value is String }.map { it.key }.joinToString(", ")
            val listMsg = if (quickList.isNotBlank()) " လက်ရှိကွန်တက်များ: $quickList" else ""
            return "အမြန်ကွန်တက်ထဲတွင် '$name' ကို ရှာမတွေ့ပါ။$listMsg"
        }

        val key = matches.first().name
        val number = matches.first().number
        com.example.voicebrainlive.commands.QuickContactStore.write(prefs) { remove(key) }
        return "အမြန်ကွန်တက် '$key' ($number) ကို အောင်မြင်စွာ ဖျက်လိုက်ပါပြီ။"
    }

    private fun cleanContactActionWords(input: String): String {
        return input
            .replace(Regex("(?i)\\b(quick contact|contact|add|edit|delete|save|update|number|phone)\\b"), " ")
            .replace(Regex("အမြန်ကွန်တက်ထဲက|အမြန်ကွန်တက်ထဲမှာ|အမြန်ကွန်တက်ထဲ|အမြန်ကွန်တက်|ကွန်တက်ထဲက|ကွန်တက်ထဲမှာ|ကွန်တက်|ဖုန်းနံပါတ်|နံပါတ်|ဖျက်ပေးပါ|ဖျက်လိုက်ပါ|ဖျက်ပါ|ဖျက်|မှတ်ပေးပါ|မှတ်ထားပါ|မှတ်ပါ|မှတ်မယ်|ထည့်ပေးပါ|ထည့်ပါ|ပြင်ပေးပါ|ပြင်လိုက်ပါ|ပြင်ပါ|ကို|ဆီ|သို့"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private enum class ContactType { QUICK, DEVICE }

    private data class ContactMatch(
        val name: String,
        val number: String,
        val type: ContactType,
        val confidence: Int,
        val learnedAlias: Boolean = false,
        val previouslyCalled: Boolean = false
    ) {
        val shouldAutoDial: Boolean
            get() = learnedAlias || previouslyCalled
    }

    // Search Quick Contacts, and if no exact/high confidence match is found, search Device Contacts too
    private fun findQuickContacts(query: String): List<ContactMatch> {
        val search = query.trim()
        if (search.isBlank()) return emptyList()

        val matches = linkedMapOf<String, ContactMatch>()
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val match = scoreCandidate(search, key, value, ContactType.QUICK)
            mergeMatch(matches, match)
        }

        return matches.values.sortedWith(
            compareByDescending<ContactMatch> { it.confidence }
                .thenBy { it.name.length }
        )
    }

    private fun findContactsScored(query: String): List<ContactMatch> {
        val search = query.trim()
        if (search.isBlank()) return emptyList()

        val matches = linkedMapOf<String, ContactMatch>()
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val match = scoreCandidate(search, key, value, ContactType.QUICK)
            mergeMatch(matches, match)
        }

        val quickMatches = matches.values.sortedWith(
            compareByDescending<ContactMatch> { it.confidence }
                .thenBy { it.name.length }
        )

        // If we have a very strong quick contact match (confidence >= 90), return it directly
        if (quickMatches.isNotEmpty() && quickMatches.first().confidence >= 90) {
            return quickMatches
        }

        // Otherwise, search Device Contacts too and merge the results
        val deviceMatches = findInPhoneContacts(search)
        deviceMatches.forEach { match ->
            val key = "${normalizeKey(match.name)}|${match.number}"
            val existing = matches[key]
            if (existing == null || match.confidence > existing.confidence) {
                matches[key] = match
            }
        }

        return matches.values.sortedWith(
            compareByDescending<ContactMatch> { it.confidence }
                .thenBy { it.name.length }
        )
    }

    // Phone Contacts search - only called when user explicitly asks
    private fun findInPhoneContacts(query: String): List<ContactMatch> {
        val search = query.trim()
        if (search.isBlank()) return emptyList()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val matches = linkedMapOf<String, ContactMatch>()
        try {
            val displayNameCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            } else {
                ContactsContract.Contacts.DISPLAY_NAME
            }
            val projection = arrayOf(displayNameCol, ContactsContract.CommonDataKinds.Phone.NUMBER)
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection, null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(displayNameCol)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "") ?: continue
                    if (number.length < 5) continue
                    val match = scoreCandidate(search, name, number, ContactType.DEVICE)
                    mergeMatch(matches, match)
                }
            }
        } catch (e: Exception) {
            Log.e("CallCommands", "Phone contacts search error: ${e.message}")
        }
        return matches.values.sortedByDescending { it.confidence }
    }

    // Called when user explicitly says "Phone contacts ထဲမှာ ရှာပီးခေါ်ပေး"
    private fun searchPhoneContacts(query: String): String {
        if (query.isBlank()) return "Specify a contact to search."
        val matches = findInPhoneContacts(query)
        return if (matches.isEmpty()) "No matching phone contacts."
        else matches.take(10).joinToString("\n") { "${it.name}: ${it.number}" } +
            "\nSearch only. Use make_call with an exact recipient and review the approval."
    }

    private fun callFromPhoneContacts(phoneOrName: String): String {
        val testStartTime = System.currentTimeMillis()
        if (phoneOrName.isBlank()) return "ဘယ်သူကို Phone contacts ထဲမှာ ရှာရမလဲ ပြောပေးပါ။"
        val spokenInput = phoneOrName.trim()
        BehaviorMemory.recordCallAttempt(context, spokenInput)

        val matches = findInPhoneContacts(spokenInput)
        if (matches.isEmpty()) {
            return "'$spokenInput' ကို Phone contacts ထဲမှာလည်း မတွေ့ဘူး။ နာမည် စစ်ဆေးကြည့်ပေးပါ။"
        }
        val topMatch = matches.first()
        val topConfidence = topMatch.confidence

        val emergencyNumbers = setOf("911", "119", "112", "199", "191", "192", "999")
        val isEmergencyMatch = emergencyNumbers.contains(topMatch.number.replace(Regex("[^0-9]"), ""))
        if (isEmergencyMatch) {
            return placeCall(topMatch.number, topMatch.name, spokenInput, topConfidence, true, testStartTime, "Emergency")
        }

        val closeMatches = matches.filter { it.confidence >= topConfidence - 8 }
        if (closeMatches.size > 1) {
            val options = closeMatches.take(3).map { match ->
                val hasDuplicateName = closeMatches.count { it.name == match.name } > 1
                if (hasDuplicateName) {
                    "${match.name} (${match.number})"
                } else {
                    match.name
                }
            }.joinToString(" သို့မဟုတ် ")
            return "Phone contacts ထဲမှာ '$spokenInput' နဲ့ ပတ်သက်ပြီး ဆင်တူသူများ တွေ့ရပါသည်: $options။ ဘယ်သူ့ကို ခေါ်ဆိုချင်ပါသလဲ?"
        }

        // Check if previously confirmed
        val isPreviouslyConfirmed = topMatch.learnedAlias || (topMatch.previouslyCalled && topConfidence >= 90)
        if (isPreviouslyConfirmed) {
            return placeCall(topMatch.number, topMatch.name, spokenInput, topConfidence, true, testStartTime, "Device Contact")
        }

        pendingCallName = topMatch.name
        pendingCallNumber = topMatch.number
        return "Phone contacts ထဲရှိ '${topMatch.name}' ကို ဖုန်းခေါ်ရမလား? (ဟုတ်တယ် သို့မဟုတ် မဟုတ်ဘူး ဟု ပြောပါ)"
    }

    private fun mergeMatch(target: MutableMap<String, ContactMatch>, match: ContactMatch?) {
        if (match == null) return
        val key = "${normalizeKey(match.name)}|${match.number}"
        val previous = target[key]
        if (previous == null || match.confidence > previous.confidence) target[key] = match
    }

    private fun stripHonorifics(input: String): String {
        val cleaned = input.lowercase().trim()
            .replace(Regex("\\b(ko|ma|u|oo|daw|sayama|sayadaw|bro|sis)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("(^|\\s)(ကို|မ|ဦး|ဦး|ဒေါ်|ဆရာမ|ဆရာ)(\\s|$)"), " ")
            .trim()
        return if (cleaned.isBlank()) input.trim() else cleaned
    }

    private fun scoreCandidate(
        query: String,
        candidateName: String,
        number: String,
        type: ContactType
    ): ContactMatch? {
        val rawQuery = query.trim().lowercase()
        val rawCandidate = candidateName.trim().lowercase()
        if (rawQuery.isBlank() || rawCandidate.isBlank() || rawQuery.length < 2) return null

        val rawQueryNoSpace = rawQuery.replace(" ", "")
        val rawCandidateNoSpace = rawCandidate.replace(" ", "")

        // Check BehaviorMemory for learned aliases and corrections
        val aliasTarget = BehaviorMemory.getRememberedCallAlias(context, query)
        val correctedTarget = BehaviorMemory.getCorrectedCallTarget(context, query)
        val lastCalled = BehaviorMemory.getLastCalledContact(context)
        val sameAsRemembered = aliasTarget.equals(candidateName, ignoreCase = true) ||
            correctedTarget.equals(candidateName, ignoreCase = true)
        val previouslyCalled = lastCalled.equals(candidateName, ignoreCase = true)
        val isFrequentContact = BehaviorMemory.getMostFrequentContact(context)
            ?.equals(candidateName, ignoreCase = true) == true

        var score = 0
        if (rawCandidate == rawQuery || rawCandidateNoSpace == rawQueryNoSpace) {
            score = 100
        } else if (rawCandidateNoSpace.startsWith(rawQueryNoSpace) && rawQueryNoSpace.length >= 2) {
            score = 95
        } else if (rawCandidateNoSpace.contains(rawQueryNoSpace) && rawQueryNoSpace.length >= 2) {
            score = 88
        } else if (rawQueryNoSpace.startsWith(rawCandidateNoSpace) && rawCandidateNoSpace.length >= 2) {
            score = 85
        } else if (rawQueryNoSpace.contains(rawCandidateNoSpace) && rawCandidateNoSpace.length >= 2) {
            score = 80
        }

        if (score == 0) {
            val cleanQuery = stripHonorifics(query)
            val cleanCandidate = stripHonorifics(candidateName)
            val normalizedQuery = normalizeKey(cleanQuery)
            val normalizedName = normalizeKey(cleanCandidate)

            if (normalizedQuery.isNotBlank() && normalizedName.isNotBlank()) {
                val phoneticQuery = phoneticNormalize(normalizedQuery)
                val phoneticName = phoneticNormalize(normalizedName)
                score = when {
                    normalizedName == normalizedQuery -> 100
                    normalizedName.replace(" ", "") == normalizedQuery.replace(" ", "") -> 97
                    normalizedName.startsWith(normalizedQuery) -> 91
                    normalizedQuery.startsWith(normalizedName) -> 88
                    normalizedQuery.length >= 2 && normalizedName.contains(normalizedQuery) -> 84
                    normalizedName.length >= 2 && normalizedQuery.contains(normalizedName) -> 80
                    phoneticName == phoneticQuery -> 88
                    phoneticQuery.length >= 2 && phoneticName.contains(phoneticQuery) -> 79
                    phoneticName.length >= 2 && phoneticQuery.contains(phoneticName) -> 75
                    else -> {
                        val lev = similarityPercent(normalizedQuery, normalizedName)
                        val levPhonetic = similarityPercent(phoneticQuery, phoneticName)
                        maxOf(lev, levPhonetic)
                    }
                }
            }
        }

        // Boost scores based on memory and type
        if (type == ContactType.QUICK) score += 10       // Quick contacts prioritized
        if (sameAsRemembered) score += 25               // Learned alias: strong boost → auto-dial
        if (previouslyCalled) score += 10               // Previously called: boost
        if (isFrequentContact) score += 5               // Frequently called: slight boost
        score = score.coerceIn(0, 100)

        // Lowered threshold: 62 (was 68) to catch more phonetic/partial matches
        if (score < 62) return null

        return ContactMatch(
            name = candidateName,
            number = normalizePhoneNumber(number),
            type = type,
            confidence = score,
            learnedAlias = sameAsRemembered,
            previouslyCalled = previouslyCalled && score >= 72  // Lowered from 88
        )
    }

    private fun similarityPercent(a: String, b: String): Int {
        val distance = levenshtein(a, b)
        val longest = max(a.length, b.length).coerceAtLeast(1)
        return (((longest - distance).toDouble() / longest.toDouble()) * 100.0).toInt()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val costs = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var lastValue = i
            costs[0] = i + 1
            for (j in b.indices) {
                val newValue = if (a[i] == b[j]) {
                    lastValue
                } else {
                    minOf(minOf(costs[j], costs[j + 1]), lastValue) + 1
                }
                lastValue = costs[j + 1]
                costs[j + 1] = newValue
            }
        }
        return costs[b.length]
    }

    /**
     * Phonetic normalization for Myanmar name pronunciation variations.
     * Maps common romanization variants to canonical forms for fuzzy matching.
     * Examples: "aung" == "ong", "aye" ~= "ei", "khin" ~= "kin"
     */
    private fun phoneticNormalize(input: String): String {
        var s = input.lowercase().trim()
        // Myanmar romanization pronunciation variants
        s = s.replace("aung", "on")
            .replace("aun", "on")
            .replace("aye", "ei")
            .replace("thit", "teit")
            .replace("thu", "du")
            .replace("khin", "kin")
            .replace("kyaw", "jo")
            .replace("kyar", "jar")
            .replace("phyu", "pyu")
            .replace("zin", "sin")
            .replace("hla", "la")
            .replace("oo", "u")
            .replace("ou", "u")
            .replace("ph", "p")
            .replace("th", "t")
            .replace("kh", "k")
            .replace("gh", "g")
            .replace("ww", "w")
        // Collapse repeated characters
        s = s.replace(Regex("(.)\\1+"), "$1")
        return s
    }

    private fun normalizeKey(input: String): String {
        var s = input.trim().lowercase().replace(" ", "")
        
        // Multi-char vowel/mark combinations for better Burmese romanization mapping
        val multiCharMappings = listOf(
            "\u103B\u1031\u102C\u103A" to "yaw", // ျော်
            "\u1031\u103B\u102C\u103A" to "yaw", // ေျာ်
            "\u103C\u1031\u102C\u103A" to "raw", // ြော်
            "\u1031\u103C\u102C\u103A" to "raw", // ေြာ်
            "\u1031\u102C\u103A" to "aw",        // ော်
            "\u1031\u102C\u1004\u103A\u1038" to "aung", // ောင်း
            "\u1031\u102C\u1004\u103A\u1037" to "aung", // ောင့်
            "\u1031\u102C\u1004\u103A" to "aung",   // ောင်
            "\u102D\u102C\u1004\u103A\u1038" to "aing", // ိုင်း
            "\u102D\u102C\u1004\u103A" to "aing",   // ိုင်
            "\u102F\u1004\u103A\u1038" to "aing",   // ုင်း
            "\u102F\u1004\u103A" to "aing",       // ုင်
            "\u103D\u1004\u103A\u1038" to "win",    // ွင်း
            "\u103D\u1004\u103A\u1037" to "win",    // ွင့်
            "\u103D\u1004\u103A" to "win",       // ွင်
            "\u103D\u1014\u103A\u1038" to "un",     // ွန်း
            "\u103D\u1014\u103A\u1037" to "un",     // ွန့်
            "\u103D\u1014\u103A" to "un",        // ွန်
            "\u100A\u103A" to "e",               // ည်
            "\u1009\u103A" to "in",              // ဉ်
            "\u102D\u1014\u103A\u1038" to "ein",    // ိန်း
            "\u102D\u1014\u103A" to "ein",       // ိန်
            "\u102D\u1010\u103A" to "eik",       // ိတ်
            "\u102D\u1015\u103A" to "eip"        // ိပ်
        )
        
        multiCharMappings.forEach { (burmese, roman) ->
            s = s.replace(burmese, roman)
        }

        // Comprehensive Burmese consonant phonetic mapping
        val burmeseMappings = mapOf(
            "ချ" to "ch", "ဈ" to "j", "ဋ" to "t", "ဌ" to "t", "ဍ" to "d", "ဎ" to "d",
            "ဏ" to "n", "ဧ" to "e", "ဝ" to "w", "ဖ" to "p", "က" to "k", "ခ" to "k",
            "မ" to "m", "င" to "ng", "စ" to "s", "ဆ" to "s", "ဇ" to "z", "ည" to "ny",
            "တ" to "t", "ထ" to "t", "ဒ" to "d", "န" to "n", "ပ" to "p", "ဘ" to "b",
            "ယ" to "y", "ရ" to "r", "လ" to "l", "သ" to "th", "ဟ" to "h", "အ" to "a",
            "ဂ" to "g", "ဃ" to "g", "ဗ" to "b", "ဓ" to "d", "ဠ" to "l"
        )
        burmeseMappings.forEach { (burmese, roman) -> 
            if (s.contains(burmese)) s = s.replace(burmese, roman) 
        }
        
        return s
            .replace("\u1031", "")  // e vowel
            .replace("\u103A", "")  // asat
            .replace("\u103B", "y") // ya-pin -> y
            .replace("\u103C", "r") // ya-yit -> r
            .replace("\u103D", "w") // wa-sw -> w
            .replace("\u103E", "h") // ha-hto -> h
            .replace("\u102C", "a") // aa vowel -> a
            .replace("\u102D", "i")
            .replace("\u102E", "i")
            .replace("\u102F", "u")
            .replace("\u1030", "u")
            .replace("၀", "0").replace("၁", "1").replace("၂", "2")
            .replace("၃", "3").replace("၄", "4").replace("၅", "5")
            .replace("၆", "6").replace("၇", "7").replace("၈", "8").replace("၉", "9")
            .replace(Regex("[^a-z0-9]"), "") // v2.6: Clear remaining Burmese chars/marks
    }

    private fun normalizePhoneNumber(raw: String): String {
        val s = raw.replace(Regex("[^0-9+*#]"), "")
        if (s.startsWith("+") || s.startsWith("0")) return s
        if (s.startsWith("82") && s.length in 10..12) return "+$s"
        if (s.startsWith("959") && s.length >= 11) return "+$s"
        return s
    }

    private fun setSpeakerphone(on: Boolean): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Audio manager settings class not found."
        return try {
            audioManager.isSpeakerphoneOn = on
            if (on) "စပီကာ ဖွင့်လိုက်ပါပြီ။" else "စပီကာ ပိတ်လိုက်ပါပြီ။"
        } catch (e: Exception) {
            Log.e("CallCommands", "setSpeakerphone error: ${e.message}")
            "စပီကာ ပြောင်းလဲ၍မရပါ။"
        }
    }

    private fun setCallMute(mute: Boolean): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Audio manager settings class not found."
        return try {
            audioManager.isMicrophoneMute = mute
            if (mute) "မိုက်ခရိုဖုန်း ပိတ်လိုက်ပါပြီ။" else "မိုက်ခရိုဖုန်း ပြန်ဖွင့်လိုက်ပါပြီ။"
        } catch (e: Exception) {
            Log.e("CallCommands", "setCallMute error: ${e.message}")
            "မိုက်ခရိုဖုန်း ပြောင်းလဲ၍မရပါ။"
        }
    }

    private fun endActiveCall(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                ?: return "Telecom manager settings class not found."
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                return try {
                    val success = telecomManager.endCall()
                    if (success) "ဖုန်းချလိုက်ပါပြီ။" else "လက်ရှိဖုန်းခေါ်ဆိုမှုကို ချ၍မရပါ။"
                } catch (e: Exception) {
                    Log.e("CallCommands", "endActiveCall error: ${e.message}")
                    "ဖုန်းချရာတွင် အမှားအယွင်းရှိခဲ့သည်။"
                }
            } else {
                return "ဖုန်းချရန် ဖုန်းခေါ်ဆိုမှုခွင့်ပြုချက် လိုအပ်သည်။"
            }
        } else {
            return "ဤဖုန်းဗားရှင်းတွင် ဖုန်းချရန် function ကို မပံ့ပိုးပါ။"
        }
    }
}
