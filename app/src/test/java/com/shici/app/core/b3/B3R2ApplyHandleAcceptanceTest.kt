package com.shici.app.core.b3

import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real production session/ApplyHandle; no Android, database or timing-based acceptance. */
class B3R2ApplyHandleAcceptanceTest {
    private val payload = LearningBackupCodec.decode(LearningBackupCodec.encode(
        LearningBackupData(emptyList(), emptyList()), "synthetic", 11, 0))
    private fun applying(s: LearningRestoreSession): Pair<Int, LearningRestoreSession.ApplyHandle> {
        val id = s.beginImport()!!
        assertTrue(s.beginReading(id, "content://synthetic"))
        assertTrue(s.freezeImport(id, payload))
        val safety = s.confirm()!!
        assertSame(payload, s.beginApply(safety))
        return safety to s.applyHandle!!
    }
    private fun await(l: CountDownLatch) { assertTrue("bounded thread rendezvous", l.await(5, TimeUnit.SECONDS)) }
    private fun joined(t: Thread) { t.join(5000); assertFalse("worker terminated", t.isAlive) }

    @Test fun clearBeforeAdmissionRejectsOverwrite() {
        val s = LearningRestoreSession(); val (_, h) = applying(s)
        s.clear(); assertFalse(h.isActive()); assertFalse(h.admit()); assertFalse(h.isAdmitted)
    }
    @Test fun admissionBeforeClearRecordsCommitBoundary() {
        val s = LearningRestoreSession(); val (_, h) = applying(s)
        assertTrue(h.admit()); s.clear()
        assertTrue(h.isAdmitted); assertFalse(h.isActive()); assertFalse(h.admit())
    }
    @Test fun replacementGetsDifferentIdentityAndCannotReviveOldHandle() {
        val s = LearningRestoreSession(); val (_, old) = applying(s); s.clear()
        val (_, current) = applying(s)
        assertNotSame(old, current); assertFalse(old.isActive()); assertFalse(old.admit())
        assertTrue(current.isActive()); assertTrue(current.admit())
    }
    @Test fun duplicatePickerResultNeverCreatesSecondHandle() {
        val s = LearningRestoreSession(); val (id, h) = applying(s)
        assertNull(s.beginApply(id)); assertSame(h, s.applyHandle); assertNull(s.beginImport())
    }
    @Test fun oldFinishDoesNotClearNewApplyingIdentity() {
        val s = LearningRestoreSession(); val (old, _) = applying(s); s.clear()
        val (current, h) = applying(s)
        assertFalse(s.finish(old)); assertTrue(s.busy); assertSame(h, s.applyHandle)
        assertTrue(h.isActive()); assertTrue(s.finish(current)); assertFalse(h.isActive())
    }
    @Test fun finishInvalidatesExactlyCurrentIdentityOnce() {
        val s = LearningRestoreSession(); val (id, h) = applying(s)
        assertTrue(s.finish(id)); assertFalse(s.finish(id)); assertFalse(h.isActive()); assertFalse(h.admit())
    }
    @Test fun onStopPreparationPolicyCannotCancelAdmittedOrUnadmittedApply() {
        val s = LearningRestoreSession(); val (_, h) = applying(s)
        assertFalse(s.cancelPreparation()); assertTrue(h.isActive()); assertTrue(h.admit())
        assertFalse(s.cancelPreparation()); assertTrue(h.isAdmitted)
    }
    @Test fun wrongPhaseFinishCannotClearPicker() {
        val s = LearningRestoreSession(); val id = s.beginImport()!!
        assertFalse(s.finish(id)); assertFalse(s.finish()); assertEquals(id, s.importRequestCode)
    }
    @Test fun cancellationIsVisibleAcrossActualThreadsBeforeAdmission() {
        val s = LearningRestoreSession(); val (_, h) = applying(s)
        val waiting = CountDownLatch(1); val release = CountDownLatch(1)
        val outcome = AtomicReference<Boolean>(); val error = AtomicReference<Throwable>()
        val worker = Thread { try { waiting.countDown(); await(release); outcome.set(h.admit()) } catch(t:Throwable){error.set(t)} }
        worker.start(); await(waiting); s.clear(); release.countDown(); joined(worker)
        assertNull(error.get()); assertEquals(false, outcome.get()); assertFalse(h.isAdmitted)
    }
    @Test fun volatileActiveObservationDoesNotNeedAdmissionMonitor() {
        val s = LearningRestoreSession(); val (_, h) = applying(s)
        val started = CountDownLatch(1); val stopped = CountDownLatch(1); val forceStop = AtomicBoolean()
        val worker = Thread { started.countDown(); while(h.isActive() && !forceStop.get()) Thread.yield(); stopped.countDown() }
        worker.start(); await(started); s.clear()
        val saw = stopped.await(5,TimeUnit.SECONDS); forceStop.set(true); joined(worker); assertTrue(saw)
    }
    @Test fun admissionWinnerAcrossThreadsSurvivesSubsequentCancellation() {
        val s = LearningRestoreSession(); val (_, h) = applying(s)
        val admitted = CountDownLatch(1); val release = CountDownLatch(1); val committed = AtomicBoolean()
        val error = AtomicReference<Throwable>()
        val worker = Thread { try { assertTrue(h.admit()); admitted.countDown(); await(release); committed.set(true) }catch(t:Throwable){error.set(t)} }
        worker.start(); await(admitted); s.clear(); release.countDown(); joined(worker)
        assertNull(error.get()); assertTrue(committed.get()); assertTrue(h.isAdmitted)
    }
    @Test fun concurrentAdmissionAndCancellationHaveSingleConsistentOutcome() {
        repeat(200) {
            val s = LearningRestoreSession(); val (_, h) = applying(s)
            val go = CountDownLatch(1); val outcome = AtomicReference<Boolean>(); val error = AtomicReference<Throwable>()
            val worker = Thread { try { await(go); outcome.set(h.admit()) }catch(t:Throwable){error.set(t)} }
            worker.start(); go.countDown(); s.clear(); joined(worker)
            assertNull(error.get()); assertNotNull(outcome.get()); assertEquals(outcome.get(), h.isAdmitted)
            assertFalse(h.isActive()); assertFalse(h.admit())
        }
    }
    @Test fun cancelledHandleDoesNotAffectNormalNewCompletion() {
        val s = LearningRestoreSession(); val (_, old) = applying(s); s.clear()
        val (id, next) = applying(s); assertFalse(old.admit()); assertTrue(next.admit())
        assertTrue(s.finish(id)); assertFalse(s.busy); assertNotNull(s.beginImport())
    }
}
