package com.example.voicebrainlive.repository

import android.content.Context
import com.example.voicebrainlive.memory.BehaviorMemory

/**
 * Phase 5.1: Repository for behavior memory (calls, music, corrections).
 * Abstracts BehaviorMemory for Clean Architecture.
 */
interface BehaviorRepository {
    fun recordCall(context: Context, nameOrPhone: String)
    fun recordMusicPlayed(context: Context, query: String)
    fun recordCallCorrection(context: Context, nameOrPhone: String)
    fun recordMusicCorrection(context: Context, query: String)
    fun getMostFrequentContact(context: Context): String?
    fun getLastCalledContact(context: Context): String?
    fun getLastCorrectedContact(context: Context): String?
    fun getLastMusic(context: Context): String?
    fun getLastCorrectedMusic(context: Context): String?
    fun getContextString(context: Context): String
}

class BehaviorRepositoryImpl : BehaviorRepository {
    override fun recordCall(context: Context, nameOrPhone: String) =
        BehaviorMemory.recordCall(context, nameOrPhone)

    override fun recordMusicPlayed(context: Context, query: String) =
        BehaviorMemory.recordMusicPlayed(context, query)

    override fun recordCallCorrection(context: Context, nameOrPhone: String) =
        BehaviorMemory.recordCallCorrection(context, nameOrPhone)

    override fun recordMusicCorrection(context: Context, query: String) =
        BehaviorMemory.recordMusicCorrection(context, query)

    override fun getMostFrequentContact(context: Context): String? =
        BehaviorMemory.getMostFrequentContact(context)

    override fun getLastCalledContact(context: Context): String? =
        BehaviorMemory.getLastCalledContact(context)

    override fun getLastCorrectedContact(context: Context): String? =
        BehaviorMemory.getLastCorrectedContact(context)

    override fun getLastMusic(context: Context): String? =
        BehaviorMemory.getLastMusic(context)

    override fun getLastCorrectedMusic(context: Context): String? =
        BehaviorMemory.getLastCorrectedMusic(context)

    override fun getContextString(context: Context): String =
        BehaviorMemory.getContextString(context)
}
