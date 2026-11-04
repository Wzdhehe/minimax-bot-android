// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataCode
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeAtomicPublisher
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataFailure
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitClient
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitHost
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitWire
import io.github.ccch1mneyyy.dsh_app.isolation.readBoundedRequestPipe
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimePublicationResult
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDeletionResult
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFileCommitHostTest {
    private fun expectFailure(code: RuntimeDataCode, block: () -> Unit) {
        try { block(); fail("file_commit_expected_refusal") }
        catch (error: RuntimeDataFailure) { assertEquals(code, error.code); assertNull(error.cause) }
    }
    @Test fun aRealIsolatedUidGetsOnlyTheMainAssignedRootsAndOperations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "commit-test-${UUID.randomUUID()}"
        val roots = listOf("backup", "state", "closed").map { File(context.noBackupFilesDir, "minimax-bot-runtime/files/$prefix-$it") }
        val hosts = mutableListOf<RuntimeFileCommitHost>()
        try {
            ProbeConnection(context).use { peer ->
                val identity = peer.identity().get(10, TimeUnit.SECONDS)
                assertNotEquals(Process.myUid(), identity.uid)
                hosts.add(RuntimeFileCommitHost.open(context, "$prefix-backup", identity.uid, peer.peerBinder()))
                hosts.add(RuntimeFileCommitHost.open(context, "$prefix-state", identity.uid, peer.peerBinder(), allowReplace = true, readTimeoutMs = 1_000,
                    allowRead = true, allowMove = true, allowRemove = true, allowAppend = true))
                hosts.add(RuntimeFileCommitHost.open(context, "$prefix-closed", identity.uid, peer.peerBinder(), allowMove = true, allowRemove = true, allowAppend = true))
                hosts.add(RuntimeFileCommitHost.open(context, "$prefix-state", identity.uid, peer.peerBinder(), allowMove = true))
                hosts.add(RuntimeFileCommitHost.open(context, "$prefix-state", identity.uid, peer.peerBinder(), allowReplace = true))
                hosts[2].closeAsync().get(5, TimeUnit.SECONDS)
                // A correct session from Main is still unauthorized: the host
                // must use Binder callingUid rather than the payload identity.
                expectFailure(RuntimeDataCode.DENIED) { RuntimeFileCommitClient(hosts[1], hosts[1].session).publishIfAbsent("wrong-uid.bin", byteArrayOf()) }
                val result = peer.run(Bundle().apply {
                    putString("id", UUID.randomUUID().toString())
                    listOf("backup", "state", "closed", "move", "replace").forEachIndexed { index, name ->
                        putBinder(name + "_host", hosts[index]); putString(name + "_session", hosts[index].session)
                    }
                }, what = 4).get(15, TimeUnit.SECONDS)
                assertEquals(identity.uid, result.uid)
                val allowed = setOf("setup", "exclusive", "replacement", "read", "mutation", "nonce", "path", "pipe_shape", "truncated", "overlong", "oversize", "timeout", "closed", "passed")
                val code = result.data.getString("code").takeIf { it in allowed } ?: "unknown"
                assertTrue("runtime_file_probe_$code", code == "passed")
                assertEquals("original", File(roots[0], "memory.bak").readText())
                assertTrue(fileProbeBytes(true).contentEquals(File(roots[1], "payload.bin").readBytes()))
                assertEquals(0L, File(roots[1], "empty.bin").length())
                assertEquals(listOf("memory.bak"), roots[0].list()!!.sorted())
                assertEquals(listOf("empty.bin", "payload.bin"), roots[1].list()!!.sorted())
                assertTrue(roots[2].list().isNullOrEmpty())
                assertEquals("RuntimeFileCommitHost(REDACTED)", hosts[1].toString())
            }
            // Peer death retires the host without another explicit close.
            hosts.forEach { it.whenRetired().get(5, TimeUnit.SECONDS) }
            assertEquals("original", File(roots[0], "memory.bak").readText())
        } finally {
            hosts.forEach { try { it.closeAsync().get(5, TimeUnit.SECONDS) } catch (_: Exception) { } }
            roots.forEach { it.deleteRecursively() }
        }
    }

    @Test fun transportRefusalMalformedRepliesAndLostRepliesCannotClaimNoWrite() {
        for (mode in 0..2) {
            val transacts = AtomicInteger()
            val bodies = AtomicInteger()
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    transacts.incrementAndGet()
                    if (code == RuntimeFileCommitWire.REPLACE || code == RuntimeFileCommitWire.APPEND) {
                        // Lose the reply after consuming an admitted request.
                        // Immediate fake replies leave asynchronous producers
                        // queued and can instead test pre-IPC backpressure.
                        data.enforceInterface(RuntimeFileCommitWire.DESCRIPTOR)
                        assertEquals("a".repeat(64), data.readString())
                        assertEquals("document.json", data.readString())
                        val length = data.readInt()
                        val expected = if (code == RuntimeFileCommitWire.APPEND) byteArrayOf(1) else byteArrayOf()
                        val bytes = ParcelFileDescriptor.CREATOR.createFromParcel(data).use {
                            readBoundedRequestPipe(it, length, RuntimeFileCommitWire.MAX_BYTES, 1_000) { false }
                        }
                        assertArrayEquals(expected, bytes)
                        assertEquals(0, data.dataAvail())
                        bodies.incrementAndGet()
                    }
                    if (mode == 0) return false
                    if (mode == 1) { reply!!.writeString("private-fault-sentinel"); return true }
                    throw IllegalStateException("private-fault-sentinel")
                }
            }
            val client = RuntimeFileCommitClient(binder, "a".repeat(64))
            assertEquals("RuntimeFileCommitClient(REDACTED)", client.toString())
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, client.replace("document.json", byteArrayOf()))
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, client.move("source.tmp", "target.json", true))
            assertEquals(RuntimeDeletionResult.OUTCOME_UNKNOWN, client.remove("document.json"))
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, client.append("document.json", byteArrayOf(1)))
            assertEquals(4, transacts.get())
            assertEquals(2, bodies.get())
        }
    }

    @Test fun closeDrainsAnAdmittedIncompleteRequestWhileThePeerStaysAlive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "drain-test-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, "minimax-bot-runtime/files/$scope")
        try {
            ProbeConnection(context).use { peer ->
                val identity = peer.identity().get(10, TimeUnit.SECONDS)
                RuntimeFileCommitHost.open(context, scope, identity.uid, peer.peerBinder(), readTimeoutMs = 10_000).use { host ->
                    val completion = peer.run(Bundle().apply {
                        putString("id", UUID.randomUUID().toString())
                        putBinder("state_host", host); putString("state_session", host.session)
                    }, what = 5)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (host.inFlightOperations == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                    assertEquals(1, host.inFlightOperations)
                    // Less than the read timeout: retirement must be caused by
                    // close cancellation, not by waiting for the natural deadline.
                    host.closeAsync().get(3, TimeUnit.SECONDS)
                    val reply = completion.get(3, TimeUnit.SECONDS)
                    assertEquals(identity.uid, reply.uid)
                    assertTrue("runtime_file_probe_drain", reply.data.getString("code") == "passed")
                    assertEquals(0, host.inFlightOperations)
                    assertTrue(peer.peerBinder().isBinderAlive)
                    assertTrue(root.list().isNullOrEmpty())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidFactoryAndClientInputsAreRejectedBeforeIoOrIpc() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "invalid-test-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, "minimax-bot-runtime/files/$name")
        expectFailure(RuntimeDataCode.INVALID) { RuntimeFileCommitHost.open(context, name, Process.myUid(), Binder()) }
        for (scope in listOf("../outside", "/absolute", ".", "..")) {
            expectFailure(RuntimeDataCode.INVALID) { RuntimeFileCommitHost.open(context, scope, Process.myUid() + 1, Binder()) }
        }
        assertFalse(root.exists())
        val calls = AtomicInteger()
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean { calls.incrementAndGet(); return false }
        }
        val client = RuntimeFileCommitClient(binder, "a".repeat(64))
        expectFailure(RuntimeDataCode.INVALID) { client.publishIfAbsent("../outside", byteArrayOf()) }
        expectFailure(RuntimeDataCode.INVALID) { client.publishIfAbsent("large.bin", ByteArray(1024 * 1024 + 1)) }
        expectFailure(RuntimeDataCode.INVALID) { client.move("source.tmp", "../outside", false) }
        expectFailure(RuntimeDataCode.INVALID) { client.move("source.tmp", "source.tmp", false) }
        expectFailure(RuntimeDataCode.INVALID) { client.remove(".native-publish-reserved.tmp") }
        val result = AtomicReference<RuntimeDataCode>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { client.publishIfAbsent("document.json", byteArrayOf()) }
            catch (error: RuntimeDataFailure) { result.set(error.code) }
        }
        assertEquals(RuntimeDataCode.WORKER_REQUIRED, result.get())
        assertEquals(0, calls.get())
    }

    @Test fun abandonedSnapshotOutputsReleaseTheirSlotsOnCloseAndDeadline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (cancel in listOf(true, false)) {
            val scope = "output-test-${UUID.randomUUID()}"
            val root = File(context.noBackupFilesDir, "minimax-bot-runtime/files/$scope")
            try {
                RuntimeAtomicPublisher.open(context, listOf("files", scope)).use { publisher ->
                    publisher.publishIfAbsent("large.bin", ByteArray(1024 * 1024) { 7 })
                }
                ProbeConnection(context).use { peer ->
                    val identity = peer.identity().get(10, TimeUnit.SECONDS)
                    RuntimeFileCommitHost.open(context, scope, identity.uid, peer.peerBinder(),
                        readTimeoutMs = if (cancel) 10_000 else 1_000, allowRead = true).use { host ->
                        val response = peer.run(Bundle().apply {
                            putString("id", UUID.randomUUID().toString())
                            putBinder("state_host", host); putString("state_session", host.session)
                        }, what = 6).get(10, TimeUnit.SECONDS)
                        assertEquals(identity.uid, response.uid)
                        assertTrue("runtime_file_probe_output", response.data.getString("code") == "passed")
                        assertTrue(host.inFlightOperations > 0)
                        if (cancel) host.closeAsync().get(3, TimeUnit.SECONDS)
                        else {
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                            while (host.inFlightOperations > 0 && System.nanoTime() < deadline) Thread.sleep(5)
                            assertFalse(host.whenRetired().isDone)
                        }
                        assertEquals(0, host.inFlightOperations)
                        assertTrue(peer.peerBinder().isBinderAlive)
                        assertEquals(1024 * 1024L, File(root, "large.bin").length())
                    }
                }
            } finally { root.deleteRecursively() }
        }
    }
}
