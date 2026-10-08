package com.shici.app.core

/** Monotonic milliseconds only; IDs and fixed stage labels, never query content. */
class OperationTiming(val id: String, private val clock: () -> Long) {
    private val clicked = clock()
    private var released: Long? = null
    fun release() { released = clock() }
    fun elapsed(): Long = (clock() - clicked).coerceAtLeast(0)
    fun clickToSelection(): Long = elapsed()
    fun releaseToDefinition(): Long? = released?.let { (clock() - it).coerceAtLeast(0) }
}
