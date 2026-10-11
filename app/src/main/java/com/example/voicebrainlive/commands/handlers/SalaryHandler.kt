package com.example.voicebrainlive.commands.handlers

import android.content.Context
import android.util.Log
import com.example.voicebrainlive.SalaryDataReader

/**
 * Handler for salary-related commands (Salary Tracker integration).
 * Migrated logic from PhoneCommandExecutor.
 */
class SalaryHandler(private val context: Context) : CommandHandler {

    override fun getSupportedCommands(): List<String> = listOf(
        "get_salary_data", "query_salary",
        "add_salary_today", "quick_add_salary",
        "copy_salary_yesterday"
    )

    override suspend fun execute(commandType: String, target: String?, value: String?): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        when (commandType.lowercase()) {
            "get_salary_data", "query_salary" -> SalaryDataReader.getSalaryAnswer(context, target ?: value ?: "")
            "add_salary_today", "quick_add_salary" -> SalaryDataReader.insertSalaryAction(context, "quick_add_today")
            "copy_salary_yesterday" -> SalaryDataReader.insertSalaryAction(context, "copy_yesterday")
            else -> null
        }
    }
}
