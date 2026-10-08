package com.shici.app.core

/** Every stop, dismissal, new frame or corrected query invalidates older async callbacks. */
class QueryGate {
    @Volatile private var revision = 0L
    @Synchronized fun next(): Long { revision += 1; return revision }
    fun accepts(token: Long): Boolean = token == revision
}
