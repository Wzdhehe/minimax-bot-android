// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.github.ccch1mneyyy.dsh_app.runtime.BotConnectionGrants
import io.github.ccch1mneyyy.dsh_app.runtime.BotLoopbackBridge
import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeCoordinator
import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeEndpoint
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BotRuntimeCoordinatorTest {
    private val runs = mutableListOf<BotRuntimeCoordinator.Run>()
    private fun completed() = CompletableFuture.completedFuture(Unit)
    private fun begin(owner: BotRuntimeCoordinator = BotRuntimeCoordinator(), cleanup: () -> CompletableFuture<Unit> = ::completed) =
        owner.begin(cleanup).also { runs.add(it) }

    private class Peer {
        var died: () -> Unit = {}
        val unregistered = AtomicInteger()
        fun watch(action: () -> Unit): Closeable { died = action; return Closeable { unregistered.incrementAndGet() } }
    }

    private fun attach(run: BotRuntimeCoordinator.Run, peer: Peer = Peer(), connect: (BotConnectionGrants) -> BotLoopbackBridge.Connection = { throw IOException("test_no_upstream") }): Peer {
        run.attach(99001, peer::watch, connect)
        return peer
    }

    private fun live(run: BotRuntimeCoordinator.Run) {
        val descriptor = BotRuntimeEndpoint.descriptor()
        assertTrue("runtime_endpoint_shape", descriptor?.keys == setOf("baseUrl", "token"))
        assertTrue("runtime_endpoint_url", descriptor?.get("baseUrl") == "http://127.0.0.1:${run.port}")
        assertTrue("runtime_endpoint_token", descriptor?.get("token") == run.token)
    }

    private fun busy(owner: BotRuntimeCoordinator) {
        try { begin(owner); fail("runtime_busy_expected") }
        catch (error: IOException) { assertEquals("bot_runtime_busy", error.message); assertNull(error.cause) }
    }

    @After fun retire() {
        runs.forEach { it.close() }
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test fun neitherBindingNorPeerIdentityPublishesTheEndpoint() {
        val owner = BotRuntimeCoordinator()
        val run = begin(owner)
        assertEquals(BotRuntimeCoordinator.Run.Phase.STARTING, run.phase)
        assertFalse(run.ready())
        assertNull(BotRuntimeEndpoint.descriptor())
        busy(owner)
        val peer = attach(run)
        assertNull(BotRuntimeEndpoint.descriptor())
        assertTrue(run.ready())
        assertTrue(run.ready())
        live(run)
        assertEquals("BotRuntimeCoordinator.Run(REDACTED)", run.toString())
        run.closeAsync().get(2, TimeUnit.SECONDS)
        assertEquals(1, peer.unregistered.get())
        assertEquals(BotRuntimeCoordinator.Run.Phase.STOPPED, run.phase)
    }

    @Test fun aStoppedRunsLateReadyAndCloseCannotReplaceTheNextEndpoint() {
        val owner = BotRuntimeCoordinator()
        var cleaned = 0
        val old = begin(owner) { cleaned++; completed() }
        attach(old)
        old.closeAsync().get(2, TimeUnit.SECONDS)
        val next = begin(owner)
        attach(next)
        assertTrue(next.ready())
        assertFalse(old.ready())
        old.close()
        live(next)
        assertEquals(1, cleaned)
        assertFalse("new_run_token_must_differ", old.token == next.token)
    }

    @Test fun cancellationOfTheStopWaitDoesNotReleaseResourcesOrTheOwnerEarly() {
        val owner = BotRuntimeCoordinator()
        val cleanup = CompletableFuture<Unit>()
        val run = begin(owner) { cleanup }
        try {
            attach(run)
            assertTrue(run.ready())
            val wait = run.closeAsync()
            wait.cancel(false)
            assertFalse(cleanup.isCancelled)
            assertNull(BotRuntimeEndpoint.descriptor())
            assertEquals(BotRuntimeCoordinator.Run.Phase.STOPPING, run.phase)
            busy(owner)
            assertFalse(run.ready())
            cleanup.complete(Unit)
            run.closeAsync().get(2, TimeUnit.SECONDS)
            begin(owner).closeAsync().get(2, TimeUnit.SECONDS)
        } finally { cleanup.complete(Unit) }
    }

    @Test fun anUnverifiedDrainKeepsTheOwnerBusyWithoutExposingItsError() {
        val owner = BotRuntimeCoordinator()
        val run = begin(owner) {
            CompletableFuture<Unit>().also { it.completeExceptionally(IOException("private-cleanup-detail")) }
        }
        attach(run)
        assertTrue(run.ready())
        try { run.closeAsync().get(2, TimeUnit.SECONDS); fail("runtime_cleanup_failure_expected") }
        catch (error: ExecutionException) {
            assertEquals("bot_runtime_cleanup_unverified", error.cause?.message)
            assertNull(error.cause?.cause)
        }
        assertEquals(BotRuntimeCoordinator.Run.Phase.FAILED, run.phase)
        assertNull(BotRuntimeEndpoint.descriptor())
        busy(owner)
        assertFalse(run.ready())
    }

    @Test fun deathDuringWatchRegistrationNeverPublishesOrLeaksTheLateHandle() {
        val run = begin()
        var unregistered = 0
        try {
            run.attach(99001, { died -> died(); Closeable { unregistered++ } }, { throw IOException("unused") })
            fail("dead_peer_expected")
        } catch (error: IOException) { assertEquals("bot_runtime_peer_unavailable", error.message); assertNull(error.cause) }
        assertEquals(1, unregistered)
        assertFalse(run.ready())
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test fun stoppingDuringAWatchDoesNotWaitAndOldCallbacksCannotAffectTheNextRun() {
        val owner = BotRuntimeCoordinator()
        val old = begin(owner)
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unregistered = AtomicInteger()
        try {
            val registration = worker.submit {
                try {
                    old.attach(99001, {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        Closeable { unregistered.incrementAndGet() }
                    }, { throw IOException("unused") })
                    fail("late_watch_expected_refusal")
                } catch (error: IOException) { assertEquals("bot_runtime_peer_unavailable", error.message) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            old.closeAsync().get(2, TimeUnit.SECONDS)
            val next = begin(owner)
            attach(next)
            assertTrue(next.ready())
            release.countDown()
            registration.get(2, TimeUnit.SECONDS)
            assertEquals(1, unregistered.get())
            assertFalse(old.ready())
            live(next)
        } finally {
            release.countDown(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun peerDeathRetractsTheEndpointClosesTheSocketAndRevokesAnUnconfirmedGrant() {
        val run = begin()
        val sent = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val receipt = CompletableFuture<BotConnectionGrants.Receipt>()
        receipt.whenComplete { _, _ -> revoked.countDown() }
        val peer = attach(run, connect = { gate ->
            gate.awaitAccepted { sent.countDown(); receipt }
            throw IOException("stopped_connection_must_not_be_installed")
        })
        assertTrue(run.ready())
        Socket(InetAddress.getByName("127.0.0.1"), run.port).use { client ->
            client.soTimeout = 5000
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            peer.died()
            assertEquals(-1, client.getInputStream().read())
            assertTrue(revoked.await(2, TimeUnit.SECONDS))
            assertTrue(receipt.isCancelled)
        }
        assertNull(BotRuntimeEndpoint.descriptor())
        assertFalse(run.ready())
        run.closeAsync().get(2, TimeUnit.SECONDS)
    }

    @Test fun aReadyEndpointRelaysBytesThroughItsBoundGrantGate() {
        val run = begin()
        val worker = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        try {
            val upstream = worker.submit {
                server.accept().use { peer ->
                    peer.soTimeout = 5000
                    assertEquals(42, peer.getInputStream().read())
                    peer.getOutputStream().write(43)
                }
            }
            attach(run, connect = { gate ->
                gate.awaitAccepted { CompletableFuture.completedFuture(BotConnectionGrants.Receipt(99001, true)) }
                val socket = Socket(InetAddress.getByName("127.0.0.1"), server.localPort)
                object : BotLoopbackBridge.Connection {
                    override val input = socket.getInputStream()
                    override val output = socket.getOutputStream()
                    override fun shutdownOutput() = socket.shutdownOutput()
                    override fun close() = socket.close()
                }
            })
            assertTrue(run.ready())
            live(run)
            Socket(InetAddress.getByName("127.0.0.1"), run.port).use { client ->
                client.soTimeout = 5000
                client.getOutputStream().write(42)
                assertEquals(43, client.getInputStream().read())
                assertEquals(-1, client.getInputStream().read())
            }
            upstream.get(2, TimeUnit.SECONDS)
        } finally {
            run.close(); server.close(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
