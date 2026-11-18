package com.shici.app.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Process-wide backup, restore and PDF I/O lane. Never run learning database work here. */
internal object DocumentIo {
    private val executor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(4),
        { work -> Thread(work, "shici-document-io") },
        ThreadPoolExecutor.AbortPolicy()
    )

    private val activeTargets = ConcurrentHashMap.newKeySet<String>()

    interface Task {
        val isCancelled: Boolean

        /** Retires the task without interrupting a running provider call. Safe to repeat. */
        fun cancel()
    }

    /**
     * Enqueues without waiting or running [work] on the submitting thread. A null return means
     * rejection (queue full or the exact [target] already in use): neither callback will run,
     * and the caller still owns all inputs. A running target stays reserved through stream close
     * and cleanup even if its UI has timed out or been destroyed.
     *
     * On acceptance, either [onSkipped] or [work] takes responsibility for any scratch files
     * handed in. [onSkipped] is only for cancellation before work starts; it may run on the
     * cancelling thread or a document worker. Keep it short, thread-safe and limited to this
     * task's app-private scratch files. Never delete a document picked by the user.
     *
     * Once started, [work] owns cleanup (use finally), even after cancellation. Check the task
     * before further I/O or posting a result. Recheck cancellation and session identity where the
     * result is consumed; posting can race cancellation. The caller owns timeout and UI state.
     */
    fun submit(onSkipped: () -> Unit = {}, target: String? = null, work: (Task) -> Unit): Task? {
        if (target != null && !activeTargets.add(target)) return null
        val release = { if (target != null) activeTargets.remove(target); Unit }
        val job = Job(onSkipped, work, release)
        return try {
            executor.execute(job)
            job
        } catch (_: RejectedExecutionException) {
            release()
            null
        }
    }

    private class Job(onSkipped: () -> Unit, work: (Task) -> Unit, release: () -> Unit) : Runnable, Task {
        @Volatile override var isCancelled = false
            private set

        private var action: (() -> Unit)? = {
            try {
                if (isCancelled) onSkipped() else work(this)
            } finally {
                release()
            }
        }

        override fun cancel() {
            isCancelled = true
            // Successful removal owns the skip even if every worker remains blocked.
            if (executor.remove(this)) run()
        }

        override fun run() {
            // Only the worker or the successful remover can reach this point, not both.
            val action = checkNotNull(action)
            this.action = null
            action()
        }
    }
}
