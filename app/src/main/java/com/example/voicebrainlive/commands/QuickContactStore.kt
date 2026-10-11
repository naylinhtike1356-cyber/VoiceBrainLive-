package com.example.voicebrainlive.commands

import android.content.SharedPreferences

object QuickContactStore {
    val lock = Any()
    private var revision = 0L
    data class Snapshot(val name: String, val keys: Map<String, Any?>, val revision: Long)
    fun snapshot(prefs: SharedPreferences, name: String): Snapshot = synchronized(lock) {
        Snapshot(name, matching(prefs, name), revision)
    }
    fun current(prefs: SharedPreferences, expected: Snapshot): Boolean = synchronized(lock) {
        snapshot(prefs, expected.name) == expected
    }
    fun compareAndWrite(prefs: SharedPreferences, expected: Snapshot, block: SharedPreferences.Editor.() -> Unit): Boolean = synchronized(lock) {
        if (!current(prefs, expected)) return false
        write(prefs, block)
    }
    fun matching(prefs: SharedPreferences, name: String): Map<String, Any?> = synchronized(lock) {
        prefs.all.filterKeys { it.equals(name, ignoreCase = true) }.toMap()
    }
    fun write(prefs: SharedPreferences, block: SharedPreferences.Editor.() -> Unit): Boolean = synchronized(lock) {
        val committed = prefs.edit().apply(block).commit()
        ++revision // Failed/uncertain writes also require a fresh review.
        committed
    }
}
