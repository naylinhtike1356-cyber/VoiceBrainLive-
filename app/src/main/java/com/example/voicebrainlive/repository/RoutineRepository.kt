package com.example.voicebrainlive.repository

import android.content.Context
import com.example.voicebrainlive.routines.RoutineStorage

/**
 * Phase 5.1: Repository for scheduled routines.
 * Abstracts RoutineStorage for Clean Architecture.
 */
interface RoutineRepository {
    fun add(context: Context, type: String, hour: Int, minute: Int, message: String, dayOfWeek: Int, useLiveAgent: Boolean = true): RoutineStorage.RoutineItem
    fun getAll(context: Context): List<RoutineStorage.RoutineItem>
    fun getById(context: Context, id: Int): RoutineStorage.RoutineItem?
    fun delete(context: Context, id: Int)
}

class RoutineRepositoryImpl : RoutineRepository {
    override fun add(context: Context, type: String, hour: Int, minute: Int, message: String, dayOfWeek: Int, useLiveAgent: Boolean) =
        RoutineStorage.add(context, type, hour, minute, message, dayOfWeek, useLiveAgent = useLiveAgent)

    override fun getAll(context: Context) = RoutineStorage.getAll(context)

    override fun getById(context: Context, id: Int) = RoutineStorage.getById(context, id)

    override fun delete(context: Context, id: Int) = RoutineStorage.delete(context, id)
}
