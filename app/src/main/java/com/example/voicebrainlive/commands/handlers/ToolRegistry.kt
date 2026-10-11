package com.example.voicebrainlive.commands.handlers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ToolCommandSpec(
    val commandType: String,
    val requiredPermissions: Set<String> = emptySet(),
    val userFacingDescription: String = ""
)

data class ToolCommandResult(
    val handled: Boolean,
    val message: String,
    val userFacingError: String? = null
)

/**
 * Tool Registry - Interface for executing commands across handlers
 * 
 * This allows handlers to delegate commands to other handlers when needed,
 * avoiding circular dependencies and keeping the architecture clean.
 */
interface ToolRegistry {
    /**
     * Execute a command and return the result
     * @param commandType The type of command (e.g., "play_music", "pause_music")
     * @param target The primary target/argument
     * @param value The secondary argument
     * @return Result string, or null if command not handled
     */
    fun executeCommand(commandType: String, target: String?, value: String?): String?

    suspend fun executeCommandAsync(commandType: String, target: String?, value: String?): String? {
        return withContext(Dispatchers.IO) {
            executeCommand(commandType, target, value)
        }
    }

    /**
     * Typed command execution surface for new code. Existing handlers can keep using
     * executeCommand while call sites migrate gradually.
     */
    fun execute(commandType: String, target: String?, value: String?): ToolCommandResult {
        val message = executeCommand(commandType, target, value)
        return if (message != null) {
            ToolCommandResult(handled = true, message = message)
        } else {
            ToolCommandResult(
                handled = false,
                message = "",
                userFacingError = "ဒီ command ကို မသိသေးပါ။"
            )
        }
    }

    suspend fun executeAsync(commandType: String, target: String?, value: String?): ToolCommandResult {
        val message = executeCommandAsync(commandType, target, value)
        return if (message != null) {
            ToolCommandResult(handled = true, message = message)
        } else {
            ToolCommandResult(
                handled = false,
                message = "",
                userFacingError = "ဒီ command ကို မသိသေးပါ။"
            )
        }
    }

    fun getCommandSpec(commandType: String): ToolCommandSpec? = null
}
