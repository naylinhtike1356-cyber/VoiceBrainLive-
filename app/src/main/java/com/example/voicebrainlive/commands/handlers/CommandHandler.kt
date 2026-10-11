package com.example.voicebrainlive.commands.handlers

/**
 * Interface for specialized command handlers to break down the God Object (PhoneCommandExecutor).
 */
interface CommandHandler {
    /**
     * Executes the given command if it's supported by this handler.
     * @return Result message in Burmese/English, or null if command is NOT supported.
     */
    suspend fun execute(commandType: String, target: String?, value: String?): String?
    
    /**
     * List of commands supported by this handler.
     */
    fun getSupportedCommands(): List<String>
}
