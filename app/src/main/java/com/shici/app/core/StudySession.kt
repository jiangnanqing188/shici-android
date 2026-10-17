package com.shici.app.core

/**
 * Cross-thread admission handle for one study surface (one Activity's 背词 screen, plus any screen
 * re-created for the same round). The owning screen invalidates it from the Activity lifecycle on the
 * main thread; the single worker admits each persistent study write [admit] immediately before that
 * write's transaction begins. [admit] and [invalidate] are the only linearization point between the
 * two threads: if the owner is invalidated first, [admit] returns false and the worker performs no
 * write at all; once [admit] has returned true, a later destroy can no longer cancel that
 * already-admitted write, so an admitted transaction is allowed to finish atomically.
 *
 * [isOwnerAlive] is a cheap supplemental liveness read (the Activity's destroyed flag) used at the
 * same admission point. It is defense in depth for an owner that was torn down, not a substitute for
 * [invalidate]: the explicit invalidation carries the cross-thread publication.
 *
 * Only these short checks are synchronized — no lock is ever held across a read, a member load or a
 * transaction — so a destroy is never blocked waiting for a long read, and the early
 * `Activity.isDestroyed` guards remain only as fast rejections, not as the write barrier.
 */
class StudyAdmission(private val isOwnerAlive: () -> Boolean = { true }) {
    @Volatile private var active = true

    /** True while the owning surface has not been invalidated; safe to read from any thread. */
    val isActive: Boolean get() = active && isOwnerAlive()

    /** Atomically admits one write while the owner is still live, or returns false once invalidated first. */
    @Synchronized fun admit(): Boolean = active && isOwnerAlive()

    /** Invalidates the surface (Activity.onDestroy / replaced owner); every later write is refused. */
    @Synchronized fun invalidate() { active = false }
}
