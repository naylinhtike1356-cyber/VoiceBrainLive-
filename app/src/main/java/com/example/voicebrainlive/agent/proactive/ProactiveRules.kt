package com.example.voicebrainlive.agent.proactive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Closed trusted UI surface. No tool/model/background entry point or caller-supplied clock. */
interface ProactiveRules {
    suspend fun history(): ProactiveSnapshot
    suspend fun createDisabled(config: DailySimulationConfig): ProactiveRule
    suspend fun updateDisabled(id: String, revision: Long, config: DailySimulationConfig): ProactiveRule
    suspend fun enableReviewed(id: String, revision: Long): ProactiveRule
    suspend fun pause(id: String, revision: Long): ProactiveRule
    suspend fun delete(id: String, revision: Long): ProactiveRule
    suspend fun evaluateSynthetic(id: String, revision: Long, stateRevision: Long, signal: ClosedSyntheticSignal): EvaluationView
}

class AndroidProactiveRules(context: Context) : ProactiveRules {
    private val app = context.applicationContext
    private val repository by lazy { ProactiveRuleRepository(ProactiveRuleStoreFactory.create(app)) }
    override suspend fun history() = withContext(Dispatchers.IO) { repository.history() }
    override suspend fun createDisabled(config: DailySimulationConfig) = withContext(Dispatchers.IO) { repository.createDisabled(config) }
    override suspend fun updateDisabled(id: String, revision: Long, config: DailySimulationConfig) = withContext(Dispatchers.IO) { repository.updateDisabled(id, revision, config) }
    override suspend fun enableReviewed(id: String, revision: Long) = withContext(Dispatchers.IO) { repository.enableReviewed(id, revision) }
    override suspend fun pause(id: String, revision: Long) = withContext(Dispatchers.IO) { repository.pause(id, revision) }
    override suspend fun delete(id: String, revision: Long) = withContext(Dispatchers.IO) { repository.delete(id, revision) }
    override suspend fun evaluateSynthetic(id: String, revision: Long, stateRevision: Long, signal: ClosedSyntheticSignal) = withContext(Dispatchers.IO) {
        repository.evaluateSynthetic(id, revision, stateRevision, signal)
    }
}
