package com.shici.app.core

import org.junit.Assert.*
import org.junit.Test

class LearningChangesTest {
    @Test fun observersCanBeRemovedAndFailuresDoNotTurnCommitIntoFailure() {
        val changes = LearningChanges()
        val versions = mutableListOf<Long>()
        val observer: (Long) -> Unit = { versions += it }
        changes.observe(observer)
        changes.observe(observer)
        changes.observe { error("bad observer") }
        changes.publishCommitted()
        assertEquals(listOf(1L), versions)
        changes.remove(observer)
        changes.publishCommitted()
        assertEquals(listOf(1L), versions)
        assertEquals(2L, changes.version)
    }
    @Test fun refreshRejectsOutdatedReadsDuplicateVersionsAndStoppedActivities() {
        val gate = LearningRefreshGate()
        assertNull(gate.request(0))
        gate.start()
        val first = gate.request(0)!!
        assertNull(gate.request(0))
        val newer = gate.request(1)!!
        assertFalse(gate.accept(first, 0, 1))
        assertFalse(gate.accept(newer, 1, 2))
        val latest = gate.request(2)!!
        assertTrue(gate.accept(latest, 2, 2))
        assertNull(gate.request(2))
        val forced = gate.request(2, force = true)!!
        gate.stop()
        assertFalse(gate.accept(forced, 2, 2))
        assertNull(gate.request(3))
        gate.start()
        val resumed = gate.request(3)!!
        assertFalse(gate.accept(forced, 2, 3))
        assertTrue(gate.accept(resumed, 3, 3))
    }
    @Test fun readThatAlreadyContainsNewVersionConsumesItsDelayedNotification() {
        val gate = LearningRefreshGate()
        gate.start()
        val request = gate.request(1)!!
        assertTrue(gate.accept(request, 2, 2))
        assertNull(gate.request(2))
    }
}
