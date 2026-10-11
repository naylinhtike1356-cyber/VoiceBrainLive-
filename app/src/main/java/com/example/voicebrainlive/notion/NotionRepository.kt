package com.example.voicebrainlive.notion

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository layer for complete Notion Database Control & Conversational RAG Q&A.
 * Provides high-level interfaces for AI and command execution to query, discuss,
 * append, create structured notes, and archive items in Notion without subscription fees.
 */
class NotionRepository(private val context: Context) {
    private val client = NotionClient(context)

    suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        client.isConfigured()
    }

    suspend fun searchAndDiscussNotes(query: String = ""): String = withContext(Dispatchers.IO) {
        if (!client.isConfigured()) {
            return@withContext "Notion Token သို့မဟုတ် Database ID အား သတ်မှတ်ထားခြင်း မရှိသေးပါ။ Settings တွင် ထည့်သွင်းပေးပါ။"
        }
        val isGeneralQuery = query.isBlank() || query.trim().lowercase() in setOf(
            "all", "အကုန်", "အားလုံး", "history", "notes", "မှတ်စုများ", "အရင်က", "မှတ်စု", "recent", "list"
        )
        val searchKw = if (isGeneralQuery) "" else query.trim()
        val notes = client.queryNotes(searchKw, limit = 15)
        if (notes.isEmpty()) {
            if (!isGeneralQuery) {
                return@withContext "Notion Database အတွင်း ‘$query’ နှင့် သက်ဆိုင်သော မှတ်စုများကို ရှာမတွေ့ပါ။"
            } else {
                return@withContext "Notion Database တွင် မှတ်စုများ မရှိသေးပါ။"
            }
        }

        val titleText = if (!isGeneralQuery) {
            "‘$query’ နှင့်ဆိုင်သော Notion မှတ်စုများ စာရင်း-"
        } else {
            "Notion Database အတွင်းရှိ မှတ်စုဟောင်းနှင့် အသစ်များ အကုန်စာရင်း-"
        }

        val summary = StringBuilder("$titleText\n\n")
        notes.forEachIndexed { idx, note ->
            summary.append("${idx + 1}. [ခေါင်းစဉ်: ${note.name}] (ကဏ္ဍ: ${note.category.ifBlank { "N/A" }})\n")
            summary.append("   အတွင်းအကြောင်းအရာ: ${note.content.ifBlank { "(စာသားမရှိ)" }}\n")
            summary.append("   အခြေအနေ: ${note.status.ifBlank { "N/A" }}\n\n")
        }
        summary.toString().trimEnd()
    }

    suspend fun appendToNoteByTitle(targetTitle: String, newContent: String): String = withContext(Dispatchers.IO) {
        if (!client.isConfigured()) {
            return@withContext "Notion အား Settings တွင် ချိတ်ဆက်ထားခြင်းမရှိသေးပါ။"
        }
        if (targetTitle.isBlank()) {
            return@withContext "ဘယ်မှတ်စုအောက်မှာ ထည့်ရမလဲဆိုသော မှတ်စုခေါင်းစဉ် ပြောပြပေးပါ။"
        }
        val matches = client.queryNotes(targetTitle, limit = 5)
        if (matches.isEmpty()) {
            return@withContext "‘$targetTitle’ အမည်နှင့်တူသော မှတ်စုကို Notion တွင် ရှာမတွေ့ပါ။ အသစ်ဖန်တီးပေးရမလားခင်ဗျာ?"
        }
        val bestMatch = matches.first()
        val result = client.appendToPage(bestMatch.id, bestMatch.content, newContent)
        if (result.isSuccess) {
            "Notion ရှိ ‘${bestMatch.name}’ မှတ်စုအောက်သို့ ‘$newContent’ ကို အောင်မြင်စွာ ထပ်ပေါင်း ထည့်သွင်းပြီးပါပြီ!"
        } else {
            "‘${bestMatch.name}’ သို့ စာဖြည့်သွင်းရာတွင် အခက်အခဲဖြစ်ပေါ်ခဲ့ပါသည်။ ${result.exceptionOrNull()?.message ?: ""}"
        }
    }

    suspend fun deleteNoteByTitle(targetTitle: String): String = withContext(Dispatchers.IO) {
        if (!client.isConfigured()) {
            return@withContext "Notion အား Settings တွင် ချိတ်ဆက်ထားခြင်းမရှိသေးပါ။"
        }
        if (targetTitle.isBlank()) {
            return@withContext "ဖျက်လိုသော မှတ်စုခေါင်းစဉ် ပြောပြပေးပါ။"
        }
        val matches = client.queryNotes(targetTitle, limit = 5)
        if (matches.isEmpty()) {
            return@withContext "Notion တွင် ‘$targetTitle’ ဆိုသော မှတ်စုကို ရှာမတွေ့ပါ။"
        }
        val bestMatch = matches.first()
        val result = client.archivePage(bestMatch.id)
        if (result.isSuccess) {
            "Notion ထဲမှ ‘${bestMatch.name}’ မှတ်စုကို အောင်မြင်စွာ ဖျက်သိမ်း (Archive) ပြီးပါပြီ!"
        } else {
            "ဖျက်သိမ်းရာတွင် Error ဖြစ်ပေါ်ခဲ့ပါသည်။ ${result.exceptionOrNull()?.message ?: ""}"
        }
    }

    suspend fun createNote(title: String, content: String, category: String = "Quick Note"): String = withContext(Dispatchers.IO) {
        val res = client.createNote(name = title.ifBlank { "Untitled" }, content = content, category = category)
        if (res.isSuccess) {
            "Notion တွင် ‘$title’ အမည်ဖြင့် အသစ်မှတ်သား သိမ်းဆည်းပြီးပါပြီ!"
        } else {
            "Notion သို့ သိမ်းဆည်းရာတွင် အဆင်မပြေပါ။ ${res.exceptionOrNull()?.message ?: ""}"
        }
    }
}
