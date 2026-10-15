package com.shici.app.core.b2

import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real production helpers on JVM; not SQLite/UI/OCR execution. */
class B2DeletionGateAcceptanceTest {
    @Test fun initialCredentialsAreFreshForAllKeys() {
        val gate = DeletionGate(); val before = gate.capture()
        assertEquals(0L, before)
        listOf("abandon", "abandoned", "leave", "left", "").forEach { assertFalse(gate.isStale(it, before)) }
    }
    @Test fun deletionInvalidatesOnlyExactKey() {
        val gate = DeletionGate(); val old = gate.capture(); gate.recordDeletion("abandon")
        assertTrue(gate.isStale("abandon", old))
        listOf("abandoned", "Abandon", "abandon ", "abandonment", "left").forEach { assertFalse(gate.isStale(it, old)) }
    }
    @Test fun activeQueryForAnotherWordSurvivesDeletion() {
        val gate = DeletionGate(); val query = QueryGate(); val token = query.next(); val credential = gate.capture()
        gate.recordDeletion("abandon")
        assertTrue(query.accepts(token)); assertFalse(gate.isStale("ability", credential))
    }
    @Test fun newlyStartedQueryHasFreshCredential() {
        val gate = DeletionGate(); val old = gate.capture(); gate.recordDeletion("abandon")
        val fresh = gate.capture()
        assertEquals(old + 1, fresh); assertFalse(gate.isStale("abandon", fresh)); assertTrue(gate.isStale("abandon", old))
    }
    @Test fun retryMustKeepOriginalCredential() {
        val gate = DeletionGate(); val lookupStart = gate.capture(); gate.recordDeletion("abandon")
        repeat(10) { assertTrue(gate.isStale("abandon", lookupStart)) }
        assertFalse(gate.isStale("abandon", gate.capture()))
    }
    @Test fun consecutiveDeletesInvalidateEachCollectedGeneration() {
        val gate = DeletionGate(); val c0 = gate.capture(); gate.recordDeletion("left"); val c1 = gate.capture()
        gate.recordDeletion("left"); val c2 = gate.capture()
        assertTrue(gate.isStale("left", c0)); assertTrue(gate.isStale("left", c1)); assertFalse(gate.isStale("left", c2))
        assertEquals(2L, c2)
    }
    @Test fun interveningOtherWordDeletionDoesNotInvalidateNewRecord() {
        val gate = DeletionGate(); gate.recordDeletion("left"); val freshLeft = gate.capture(); gate.recordDeletion("leave")
        assertFalse(gate.isStale("left", freshLeft)); assertTrue(gate.isStale("leave", freshLeft))
    }
    @Test fun credentialsCarryThroughSnapshotWithoutRecapture() {
        val gate = DeletionGate(); val snapshot = LearningSnapshot(emptyList(), LearningStats(0,0,0,0,0), 7, gate.capture())
        gate.recordDeletion("left")
        assertEquals(0L, snapshot.deletionCredential); assertTrue(gate.isStale("left", snapshot.deletionCredential))
        assertFalse(gate.isStale("leave", snapshot.deletionCredential))
    }
    @Test fun noRecordedDeletionLeavesCredentialUsable() {
        val gate = DeletionGate(); val c = gate.capture()
        // The helper changes only on recordDeletion. Repository rollback is tested separately on Android.
        assertEquals(c, gate.capture()); assertFalse(gate.isStale("left", c))
    }
    @Test fun concurrentCommittedDeletionCallsDoNotLoseEpochs() {
        val gate = DeletionGate(); val pool = Executors.newFixedThreadPool(4); val latch = CountDownLatch(80)
        try {
            repeat(80) { i -> pool.submit { try { gate.recordDeletion("key-$i") } finally { latch.countDown() } } }
            assertTrue(latch.await(10, TimeUnit.SECONDS)); assertEquals(80L, gate.capture())
            repeat(80) { assertTrue(gate.isStale("key-$it", 0)) }
            assertFalse(gate.isStale("untouched", 0))
        } finally { pool.shutdownNow() }
    }
    @Test fun queryCancellationAndDeletionAreIndependentChecks() {
        val deletion = DeletionGate(); val query = QueryGate(); val c = deletion.capture(); val token = query.next()
        query.next(); assertFalse(query.accepts(token)); assertFalse(deletion.isStale("left", c))
        val token2 = query.next(); deletion.recordDeletion("left")
        assertTrue(query.accepts(token2)); assertTrue(deletion.isStale("left", c))
    }
    @Test fun deletionNotificationRefreshRejectsOlderSnapshot() {
        val gate = LearningRefreshGate(); gate.start(); val first = gate.request(3)!!; val afterDelete = gate.request(4)!!
        assertFalse(gate.accept(first, 3, 4)); assertFalse(gate.accept(afterDelete, 3, 4))
        assertTrue(gate.accept(afterDelete, 4, 4)); assertNull(gate.request(4))
    }
    @Test fun stoppingScreenRejectsDeletionRefreshCallback() {
        val gate = LearningRefreshGate(); gate.start(); val pending = gate.request(1)!!; gate.stop()
        assertFalse(gate.accept(pending, 1, 1)); gate.start(); val new = gate.request(1)!!
        assertTrue(gate.accept(new, 1, 1)); assertFalse(gate.accept(pending, 1, 1))
    }
    @Test fun observerDeliveryIncludesFreshDeletionCredential() {
        val deletions = DeletionGate(); val changes = LearningChanges(); val seen = mutableListOf<Long>()
        changes.observe { seen += deletions.capture() }
        deletions.recordDeletion("left"); changes.publishCommitted()
        assertEquals(listOf(1L), seen); assertEquals(1L, changes.version)
    }
}
