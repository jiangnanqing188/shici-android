package com.shici.app.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shici.app.ShiciApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Validates the packaged asset on Android, without saving queries or touching the user's database. */
@RunWith(AndroidJUnit4::class)
class RawKajwebAssetTest {
    @Test fun sharedWorkerLoadsOneValidatedOriginalDictionaryWithoutDemoFallback() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ShiciApplication
        val first = app.worker.submit<RawKajwebDictionary> { app.dictionary.getOrThrow() }.get(30, TimeUnit.SECONDS)
        val second = app.worker.submit<RawKajwebDictionary> { app.dictionary.getOrThrow() }.get(30, TimeUnit.SECONDS)
        assertSame(first, second)
        assertEquals(12652, first.entryCount)
        assertEquals(0, first.aliasCount)
        val quantum = first.lookup("quantum") as LookupResult.Match
        assertEquals(RawKajwebDictionary.SOURCE, quantum.entries.single().source)
        assertEquals(RawKajwebDictionary.VERSION, quantum.entries.single().version)
        assertTrue(first.lookup("zzzzzxyzfixture") is LookupResult.Missing)
    }
}
