// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BotRuntimeStartupProgressTest {
    @Test fun onlyTheFiniteVocabularyCanReachTheLog() {
        val events = mutableListOf<String>()
        val progress = BotRuntimeStartupProgress(events::add)
        for (value in listOf("", "unknown", "runtime_start\nuser data", "https://example.invalid/private", "assets_loading ")) {
            assertFalse(progress.report(value))
        }
        assertTrue(events.isEmpty())
        for (phase in BotRuntimeStartupProgress.PHASES) assertTrue(progress.report(phase))
        assertEquals(BotRuntimeStartupProgress.PHASES.map { "bot_start_$it" }, events)
        assertFalse(events.contains("bot_ready"))
    }

    @Test fun duplicatesAndEarlierPhasesCannotSpamOrRegressTheLog() {
        val events = mutableListOf<String>()
        val progress = BotRuntimeStartupProgress(events::add)
        assertTrue(progress.report("assets_loading"))
        assertFalse(progress.report("assets_loading"))
        assertTrue(progress.report("runtime_importing"))
        assertFalse(progress.report("filesystem_loading"))
        assertFalse(progress.report("unknown"))
        assertTrue(progress.report("ingress_started"))
        assertFalse(progress.report("native_catalog_projected"))
        assertEquals(3, events.size)
    }

    @Test fun retirementPermanentlyClosesTheObserver() {
        val events = mutableListOf<String>()
        val progress = BotRuntimeStartupProgress(events::add)
        progress.report("assets_loaded")
        progress.close()
        progress.close()
        assertFalse(progress.report("runtime_factory_started"))
        assertEquals(listOf("bot_start_assets_loaded"), events)
    }

    @Test fun aFailedDiagnosticDoesNotFailStartupOrRetryTheSameEvent() {
        var attempts = 0
        val progress = BotRuntimeStartupProgress { attempts++; throw IllegalStateException("diagnostic fixture") }
        assertTrue(progress.report("assets_loaded"))
        assertFalse(progress.report("assets_loaded"))
        assertTrue(progress.report("runtime_imported"))
        assertEquals(2, attempts)
    }

    @Test fun concurrentDuplicatesProduceOnlyOneFiniteEvent() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val progress = BotRuntimeStartupProgress(events::add)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val threads = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                threads.execute { try { start.await(); progress.report("assets_loading") } finally { done.countDown() } }
            }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("bot_start_assets_loading"), events)
        } finally { start.countDown(); threads.shutdownNow() }
    }
}
