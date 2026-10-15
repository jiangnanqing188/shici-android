package com.shici.app.core

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** In-process invalidation only; events and counts remain solely in SQLite. */
class LearningChanges {
    private val revision = AtomicLong()
    private val observers = CopyOnWriteArraySet<(Long) -> Unit>()
    val version: Long get() = revision.get()

    fun observe(observer: (Long) -> Unit) { observers.add(observer) }
    fun remove(observer: (Long) -> Unit) { observers.remove(observer) }

    /** Called by the repository only after endTransaction has returned successfully. */
    fun publishCommitted() {
        val version = revision.incrementAndGet()
        observers.forEach { observer -> runCatching { observer(version) } }
    }
}

/**
 * [deletionCredential] is the deletion generation taken by the worker immediately before these rows
 * were read, so a detail opened from this snapshot stays bound to the data it actually shows. Default
 * is only for compatibility with synthetic snapshots; live UI must use the repository-provided value.
 */
data class LearningSnapshot(
    val words: List<LearnedWord>,
    val stats: LearningStats,
    val version: Long,
    val deletionCredential: Long = 0L
)

/** Main-thread gate: duplicate notifications and stopped/older reads never replace current data. */
class LearningRefreshGate {
    private var active = false
    private var generation = 0L
    private var requestedVersion = -1L

    fun start() { active = true; requestedVersion = -1L; generation += 1 }
    fun stop() { active = false; generation += 1 }
    fun request(version: Long, force: Boolean = false): Long? {
        if (!active || (!force && version <= requestedVersion)) return null
        requestedVersion = version
        return ++generation
    }
    fun isCurrent(request: Long) = active && request == generation
    fun accept(request: Long, snapshotVersion: Long, latestVersion: Long): Boolean {
        if (!isCurrent(request) || snapshotVersion < latestVersion) return false
        requestedVersion = maxOf(requestedVersion, snapshotVersion)
        return true
    }
}
