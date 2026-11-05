// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeCoordinator
import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeEndpoint
import io.github.ccch1mneyyy.dsh_app.runtime.attachBinder
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Component checks, NOT a genuine Bot-ready handshake or product launcher.
 * No Node script runs here. The READY event below is deliberately synthetic. */
@RunWith(AndroidJUnit4::class)
class RuntimeCoordinatorProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 30_000) fun binderDeathRetiresASyntheticallyReadyRunBeforeItsDrainCompletes() {
        val owner = BotRuntimeCoordinator()
        val cleanup = CompletableFuture<Unit>()
        val run = owner.begin { cleanup }
        try {
            ProbeConnection(context).use { probe ->
                val identity = probe.identity().get(10, TimeUnit.SECONDS)
                assertNotEquals(Process.myUid(), identity.uid)
                assertNull(BotRuntimeEndpoint.descriptor())
                run.attachBinder(context, probe.peerBinder(), identity.uid) { throw IOException("unused_probe_connection") }
                assertNull(BotRuntimeEndpoint.descriptor())
                // Synthetic component readiness; Binder identity alone MUST
                // NOT be used this way by the eventual production launcher.
                assertTrue(run.ready())
                assertTrue(BotRuntimeEndpoint.descriptor()?.get("baseUrl") == "http://127.0.0.1:${run.port}")
                probe.close()
                assertNull(BotRuntimeEndpoint.descriptor())
                assertEquals(BotRuntimeCoordinator.Run.Phase.STOPPING, run.phase)
                assertFalse(run.ready())
                try {
                    owner.begin { CompletableFuture.completedFuture(Unit) }.close()
                    fail("runtime_owner_must_wait_for_drain")
                } catch (error: IOException) { assertEquals("bot_runtime_busy", error.message) }
                cleanup.complete(Unit)
                run.closeAsync().get(2, TimeUnit.SECONDS)
                assertEquals(BotRuntimeCoordinator.Run.Phase.STOPPED, run.phase)
                owner.begin { CompletableFuture.completedFuture(Unit) }.closeAsync().get(2, TimeUnit.SECONDS)
                assertFalse(run.ready())
            }
        } finally { cleanup.complete(Unit); run.close() }
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test(timeout = 30_000) fun theOwnerUidCannotBeAcceptedAsItsOwnIsolatedPeer() {
        val run = BotRuntimeCoordinator().begin { CompletableFuture.completedFuture(Unit) }
        try {
            ProbeConnection(context).use { probe ->
                probe.identity().get(10, TimeUnit.SECONDS)
                try {
                    run.attachBinder(context, probe.peerBinder(), Process.myUid()) { throw IOException("unused") }
                    fail("runtime_peer_owner_uid_expected_refusal")
                } catch (error: IllegalArgumentException) { assertEquals("bot_runtime_peer_invalid", error.message) }
                assertFalse(run.ready())
                assertNull(BotRuntimeEndpoint.descriptor())
            }
        } finally { run.close() }
    }
}
