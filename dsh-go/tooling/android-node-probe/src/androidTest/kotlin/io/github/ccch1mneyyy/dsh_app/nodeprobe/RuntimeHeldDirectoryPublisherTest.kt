// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A publisher built on an already held Main capability must follow that
 * capability's inode, never a re-resolved path, and must not share a lifetime
 * with the capability it was derived from. */
@RunWith(AndroidJUnit4::class)
class RuntimeHeldDirectoryPublisherTest {
    private val segments = listOf("files", "held")
    private fun fixture(block: (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "held-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        try { block(context, File(root, "minimax-bot-runtime/files/held")) }
        finally { root.deleteRecursively() }
    }
    private fun failure(code: RuntimeDataCode, block: () -> Unit) {
        try { block(); fail("held_publisher_expected_refusal") }
        catch (error: RuntimeDataFailure) { assertEquals(code, error.code); assertNull(error.cause) }
    }

    @Test fun publisherOnHeldChildWritesOnlyInsideThatChild() = fixture { context, root ->
        RuntimePrivateDirectory.open(context, segments).use { parent ->
            parent.openChild("messages", create = true).use { child ->
                RuntimeAtomicPublisher.openForHeldDirectory(child).use { publisher ->
                    assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("history.jsonl", "held\n".toByteArray()))
                    assertEquals(RuntimePublicationResult.PUBLISHED, publisher.replace("history.jsonl", "replaced\n".toByteArray()))
                }
                assertEquals("replaced\n", readRuntimeFileSnapshot(child, "history.jsonl", 64).toString(Charsets.UTF_8))
                assertTrue(File(root, "messages/history.jsonl").isFile)
                assertFalse(File(root, "history.jsonl").exists())
                assertEquals(listOf("messages"), root.list()!!.sorted())
            }
        }
    }

    @Test fun renamedParentAndSymlinkReplacementCannotRedirectHeldPublisherWrites() = fixture { context, root ->
        val parent = RuntimePrivateDirectory.open(context, segments)
        val retained = File(root.parentFile, "retained")
        val outside = File(root.parentFile, "outside").apply { mkdir() }
        val marker = File(outside, "sentinel").apply { writeText("outside-sentinel") }
        assertTrue(root.renameTo(retained))
        Os.symlink(outside.path, root.path)
        try {
            parent.openChild("messages", create = true).use { child ->
                RuntimeAtomicPublisher.openForHeldDirectory(child).use { publisher ->
                    assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("history.jsonl", "anchored\n".toByteArray()))
                    assertEquals(RuntimePublicationResult.PUBLISHED, publisher.replace("history.jsonl", "replaced\n".toByteArray()))
                }
                assertEquals("replaced\n", File(retained, "messages/history.jsonl").readText())
                assertEquals(listOf("sentinel"), outside.list()!!.sorted())
            }
        } finally { parent.close() }
    }

    @Test fun publisherAndHeldCapabilityCloseIndependently() = fixture { context, root ->
        val parent = RuntimePrivateDirectory.open(context, segments)
        val child = parent.openChild("messages", create = true)
        // Closing the publisher must not close the caller's capability.
        RuntimeAtomicPublisher.openForHeldDirectory(child).use { publisher ->
            assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("history.jsonl", "first\n".toByteArray()))
        }
        child.openChild("archive", create = true).use { grandchild ->
            assertTrue(File(root, "messages/archive").isDirectory)
        }
        // Closing the caller's capability must not break a publisher derived
        // from it: the publisher owns a copy of the descriptor.
        val publisher = RuntimeAtomicPublisher.openForHeldDirectory(child)
        child.close()
        assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("history.jsonl", "after\n".toByteArray()))
        assertEquals("after\n", File(root, "messages/history.jsonl").readText())
        publisher.close()
        parent.close()
    }

    @Test fun heldPublishersShareTheDirectoryMutationGate() = fixture { context, root ->
        RuntimePrivateDirectory.open(context, segments).use { parent ->
            parent.openChild("messages", create = true).use { child ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocking = object : RuntimePublicationKernel {
                    override fun publishNoReplace(directory: Int, source: String, target: String) =
                        NativeRuntimePublication.publishNoReplace(directory, source, target)
                    override fun replace(directory: Int, source: String, target: String): Int {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return NativeRuntimePublication.replace(directory, source, target)
                    }
                }
                RuntimeAtomicPublisher.openForHeldDirectory(child, blocking).use { blocker ->
                    RuntimeAtomicPublisher.openForHeldDirectory(child).use { waiter ->
                        blocker.publishIfAbsent("history.jsonl", "prefix\n".toByteArray())
                        val pool = Executors.newFixedThreadPool(2)
                        try {
                            val append = pool.submit<RuntimePublicationResult> { blocker.append("history.jsonl", "suffix\n".toByteArray()) }
                            assertTrue(entered.await(5, TimeUnit.SECONDS))
                            val replace = pool.submit<RuntimePublicationResult> { waiter.replace("history.jsonl", "replacement\n".toByteArray()) }
                            try { replace.get(150, TimeUnit.MILLISECONDS); fail("held_publisher_missing_shared_gate") }
                            catch (_: TimeoutException) { }
                            release.countDown()
                            assertEquals(RuntimePublicationResult.PUBLISHED, append.get(5, TimeUnit.SECONDS))
                            assertEquals(RuntimePublicationResult.PUBLISHED, replace.get(5, TimeUnit.SECONDS))
                            assertEquals("replacement\n", File(root, "messages/history.jsonl").readText())
                        } finally { release.countDown(); pool.shutdownNow() }
                    }
                }
            }
        }
    }

    @Test fun uiThreadClosedCapabilityAndInvalidBoundsAreRefusedBeforeAnyWrite() = fixture { context, root ->
        val parent = RuntimePrivateDirectory.open(context, segments)
        val child = parent.openChild("messages", create = true)
        val code = AtomicReference<RuntimeDataCode>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { RuntimeAtomicPublisher.openForHeldDirectory(child).close() }
            catch (error: RuntimeDataFailure) { code.set(error.code) }
        }
        assertEquals(RuntimeDataCode.WORKER_REQUIRED, code.get())
        assertFalse(File(root, "messages/history.jsonl").exists())
        child.close()
        failure(RuntimeDataCode.CLOSED) { RuntimeAtomicPublisher.openForHeldDirectory(child).close() }
        failure(RuntimeDataCode.INVALID) { RuntimeAtomicPublisher.openForHeldDirectory(parent, maxBytes = 0) }
        failure(RuntimeDataCode.INVALID) { RuntimeAtomicPublisher.openForHeldDirectory(parent, maxBytes = 9 * 1024 * 1024) }
        assertEquals(listOf("messages"), root.list()!!.sorted())
        parent.close()
    }
}
