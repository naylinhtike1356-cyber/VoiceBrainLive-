package com.example.voicebrainlive.commands

/**
 * Validates user-authored automation text before it reaches an executor.
 * This is intentionally syntax/safety validation, not intent recognition.
 */
object AutomationCommandValidator {
    const val MAX_SEQUENCE_CHARS = 1_000
    const val MAX_STEPS = 12
    const val MAX_COMMAND_CHARS = 64
    const val MAX_ARGUMENT_CHARS = 200

    data class ValidationResult(val valid: Boolean, val message: String = "")

    fun validateSequence(sequence: String, allowApprovalRequired: Boolean = false): ValidationResult {
        val raw = sequence.trim()
        if (raw.isBlank()) return ValidationResult(false, "Command sequence မပါရှိပါ။")
        if (raw.length > MAX_SEQUENCE_CHARS) {
            return ValidationResult(false, "Command sequence သည် ${MAX_SEQUENCE_CHARS} characters ထက် မကျော်ရပါ။")
        }

        val steps = extractSteps(sequence)
        if (steps.isEmpty()) return ValidationResult(false, "လုပ်ဆောင်ချက် မတွေ့ပါ။")
        if (steps.size > MAX_STEPS) {
            return ValidationResult(false, "Macro တစ်ခုတွင် အများဆုံး ${MAX_STEPS} steps သာ ထည့်နိုင်ပါသည်။")
        }

        steps.forEachIndexed { index, step ->
            val parts = step.split(':', limit = 3)
            val command = parts.firstOrNull()?.trim().orEmpty()
            if (command.isBlank() || command.length > MAX_COMMAND_CHARS ||
                !command.matches(Regex("[A-Za-z0-9_]+"))) {
                return ValidationResult(false, "Step ${index + 1} ၏ command အမည် မမှန်ကန်ပါ။")
            }
            val target = parts.getOrNull(1)?.trim().orEmpty()
            val value = parts.getOrNull(2)?.trim().orEmpty()
            if (target.length > MAX_ARGUMENT_CHARS || value.length > MAX_ARGUMENT_CHARS) {
                return ValidationResult(false, "Step ${index + 1} ၏ argument သည် အလွန်ရှည်နေပါသည်။")
            }
            if (!allowApprovalRequired && CommandSafetyPolicy.requiresConfirmation(command)) {
                return ValidationResult(false, "Step ${index + 1} ($command) သည် user confirmation လိုအပ်သောကြောင့် automatic macro တွင် မထည့်နိုင်ပါ။")
            }
        }
        return ValidationResult(true)
    }

    /** A sensitive step consumes the remaining text verbatim: separators may be payload. */
    fun extractSteps(sequence: String): List<String> {
        val steps = mutableListOf<String>()
        var remaining = sequence
        while (remaining.isNotBlank()) {
            val command = remaining.substringBefore(':').substringBefore(';').trim()
            if (CommandSafetyPolicy.requiresConfirmation(command)) {
                steps += remaining
                break
            }
            val end = remaining.indexOf(';')
            if (end < 0) { steps += remaining; break }
            remaining.substring(0, end).takeIf { it.isNotBlank() }?.let { steps += it }
            remaining = remaining.substring(end + 1)
        }
        return steps
    }
}
