package com.shici.app.core.q1

import com.shici.app.core.q1.host.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exact R1 DebugPerformance with explicit log/build-clock adapters; not device latency. */
class Q1R1TimingAcceptanceTest {
 @Before fun reset(){Log.entries.clear();BuildConfig.DEBUG=true;SystemClock.now=10}
 @Test fun timingStartsAtClickAndActualSubmitExcludesDrawingTime(){val p=DebugPerformance();p.click();SystemClock.now=120;p.selectionReady();SystemClock.now=600;p.selectionSubmit();SystemClock.now=800;p.definitionVisible();assertTrue(Log.entries.any{it.contains("click_to_selection_ms=110")});assertTrue(Log.entries.any{it.contains("submit_to_definition_ms=200")});assertTrue(Log.entries.last().contains("elapsed_ms=790"))}
 @Test fun endedOperationCannotEmitLateMetric(){val p=DebugPerformance();p.click();p.end("cancelled","test");val n=Log.entries.size;p.selectionReady();p.selectionSubmit();p.definitionVisible();assertEquals(n,Log.entries.size)}
 @Test fun newOperationCannotInheritOldSubmitClock(){val p=DebugPerformance();p.click();p.selectionSubmit();p.end("closed","test");SystemClock.now=300;p.click();p.definitionVisible();assertFalse(Log.entries.last().contains("definition_visible"));SystemClock.now=400;p.selectionSubmit();SystemClock.now=450;p.definitionVisible();assertTrue(Log.entries.last().contains("submit_to_definition_ms=50"))}
 @Test fun releaseBuildDoesNotEmitOperationLogs(){BuildConfig.DEBUG=false;try{val p=DebugPerformance();p.click();p.selectionReady();p.selectionSubmit();p.stage("frame","ok");p.definitionVisible();p.end("closed","test");assertTrue(Log.entries.isEmpty())}finally{BuildConfig.DEBUG=true}}
}
