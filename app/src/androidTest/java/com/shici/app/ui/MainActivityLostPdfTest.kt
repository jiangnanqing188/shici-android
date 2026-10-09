package com.shici.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shici.app.ShiciApplication
import com.shici.app.core.LearningExportSession
import com.shici.app.core.LearningExportSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** No picker, real export URI or learning writes: queue worker IO behind a test latch. */
@RunWith(AndroidJUnit4::class)
class MainActivityLostPdfTest {
    private val missingUri = Uri.parse("content://com.shici.app.tests.missing/document/new.pdf")
    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun button(activity: MainActivity) = field("exportButton").get(activity) as Button
    private fun session(activity: MainActivity) = field("exportSession").get(activity) as LearningExportSession
    private fun showVocabulary(activity: MainActivity) {
        field("currentTab").setInt(activity, 1)
        MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(activity)
    }
    private fun savedState(activity: MainActivity) = Bundle().also {
        MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
            .apply { isAccessible = true }.invoke(activity, it)
    }
    private fun deliverResult(activity: MainActivity, requestCode: Int, resultCode: Int, data: Intent?) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(activity, requestCode, resultCode, data)
    }
    private fun newExport(activity: MainActivity): LearningExportSession.Request {
        val session = session(activity)
        val request = session.begin("new search")!!
        val snapshot = LearningExportSnapshot(emptyList(), request.search, LocalDate.of(2026, 10, 9))
        assertTrue(session.freeze(request, snapshot))
        assertSame(snapshot, session.choose(request))
        MainActivity::class.java.getDeclaredMethod("updateExportButton").apply { isAccessible = true }.invoke(activity)
        return request
    }
    private fun blockWorker(app: ShiciApplication): CountDownLatch {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        app.worker.execute { started.countDown(); release.await(30, TimeUnit.SECONDS) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        return release
    }
    private fun finishWorker(app: ShiciApplication, release: CountDownLatch) {
        release.countDown()
        val drained = CountDownLatch(1)
        app.worker.execute { drained.countDown() }
        assertTrue(drained.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Test fun cancelledPickerEvenWithUriDoesNotStartCleanupAndConsumesLostId() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                showVocabulary(activity)
                field("lostExportRequestCode").setInt(activity, 601)
                deliverResult(activity, 601, Activity.RESULT_CANCELED, Intent().setData(missingUri))
                assertFalse(field("cleaningLostExport").getBoolean(activity))
                assertEquals(-1, savedState(activity).getInt("export_request"))
                assertTrue(button(activity).isEnabled)
                assertFalse(session(activity).busy)
            }
        }
    }

    @Test fun acceptedResultWithoutUriDoesNotStartCleanup() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                showVocabulary(activity)
                field("lostExportRequestCode").setInt(activity, 602)
                deliverResult(activity, 602, Activity.RESULT_OK, Intent())
                assertFalse(field("cleaningLostExport").getBoolean(activity))
                assertEquals(-1, savedState(activity).getInt("export_request"))
                assertTrue(button(activity).isEnabled)
            }
        }
    }

    @Test fun pendingCleanupIsBusyButCompletionNeverCancelsAnotherExport() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var app: ShiciApplication
            scenario.onActivity { app = it.application as ShiciApplication }
            val release = blockWorker(app)
            try {
                lateinit var request: LearningExportSession.Request
                scenario.onActivity { activity ->
                    showVocabulary(activity)
                    field("lostExportRequestCode").setInt(activity, 603)
                    deliverResult(activity, 603, Activity.RESULT_OK, Intent().setData(missingUri))
                    assertTrue(field("cleaningLostExport").getBoolean(activity))
                    assertFalse(button(activity).isEnabled)
                    assertEquals(-1, savedState(activity).getInt("export_request"))
                    MainActivity::class.java.getDeclaredMethod("export").apply { isAccessible = true }.invoke(activity)
                    assertFalse(session(activity).busy) // Retry guard works beyond the disabled button.
                    request = newExport(activity) // Isolate callback safety without writing learning data.
                }
                finishWorker(app, release)
                scenario.onActivity { activity ->
                    assertFalse(field("cleaningLostExport").getBoolean(activity))
                    assertTrue(session(activity).busy)
                    assertEquals(request.id, session(activity).fileRequestCode)
                    assertFalse(button(activity).isEnabled)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun recreationDuringCleanupDoesNotPersistConsumedIdOrUpdateDestroyedActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var old: MainActivity
            lateinit var app: ShiciApplication
            scenario.onActivity { old = it; app = it.application as ShiciApplication }
            val release = blockWorker(app)
            try {
                scenario.onActivity { activity ->
                    showVocabulary(activity)
                    field("lostExportRequestCode").setInt(activity, 604)
                    deliverResult(activity, 604, Activity.RESULT_OK, Intent().setData(missingUri))
                    assertEquals(-1, savedState(activity).getInt("export_request"))
                    assertFalse(button(activity).isEnabled)
                }
                scenario.recreate()
                lateinit var request: LearningExportSession.Request
                scenario.onActivity { activity ->
                    assertNotSame(old, activity)
                    assertEquals(-1, field("lostExportRequestCode").getInt(activity))
                    assertTrue(button(activity).isEnabled)
                    request = newExport(activity)
                }
                finishWorker(app, release)
                scenario.onActivity { activity ->
                    assertTrue(old.isDestroyed)
                    assertTrue(field("cleaningLostExport").getBoolean(old)) // Destroyed callback did not mutate UI state.
                    assertTrue(session(activity).busy)
                    assertEquals(request.id, session(activity).fileRequestCode)
                    assertFalse(button(activity).isEnabled)
                }
            } finally { release.countDown() }
        }
    }
}
