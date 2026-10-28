package com.example.voicebrainlive.audio

/** Owns delayed playback UI/focus completion only; never a provider/session/approval grant. */
internal class PlaybackDrainLease {
    class Token internal constructor()
    private val lock = Any()
    private var active: Token? = null

    /** Cancellation and new-job registration run atomically with completion admission. */
    fun replace(action: (Token) -> Unit): Token = synchronized(lock) {
        Token().also { active = it; action(it) }
    }

    fun revoke(action: () -> Unit) = synchronized(lock) {
        active = null
        action()
    }

    /** The effect itself is serialized; checking first then dispatching later is insufficient. */
    fun withCurrent(token: Token, action: () -> Unit): Boolean = synchronized(lock) {
        if (active !== token) return@synchronized false
        action()
        true
    }
}
