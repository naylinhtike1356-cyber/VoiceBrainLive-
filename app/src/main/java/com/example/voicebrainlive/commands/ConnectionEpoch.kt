package com.example.voicebrainlive.commands

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Revokes queued and suspended callbacks before a replacement connection can execute tools. */
internal class ConnectionEpoch {
    private var generation = 0L
    private var active: Token? = null
    class Token internal constructor(val generation: Long, val job: Job) {
        internal val revokers = mutableListOf<() -> Unit>()
    }

    private fun revoke(token: Token) {
        val callbacks = token.revokers.toList()
        token.revokers.clear()
        callbacks.forEach { it() }
        token.job.cancel()
    }

    @Synchronized fun open(parent: Job?): Token {
        active?.let { revoke(it) }
        return Token(++generation, SupervisorJob(parent)).also { active = it }
    }
    @Synchronized fun current(token: Token): Boolean = active === token && token.job.isActive
    @Synchronized fun token(): Token? = active?.takeIf { it.job.isActive }
    @Synchronized fun <T> withCurrent(token: Token, effect: () -> T): T? = if (current(token)) effect() else null
    @Synchronized fun bind(token: Token, revokeSession: () -> Unit, install: () -> Unit): Boolean {
        if (!current(token)) { revokeSession(); return false }
        token.revokers += revokeSession
        install()
        return true
    }
    @Synchronized fun suspendCallbacks(token: Token): Boolean {
        if (active !== token) return false
        revoke(token)
        return true
    }
    @Synchronized fun close(): Boolean = active?.let { close(it) } ?: false
    @Synchronized fun close(token: Token): Boolean {
        if (active !== token) return false
        revoke(token)
        active = null
        ++generation
        return true
    }
}
