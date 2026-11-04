// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ContextWrapper
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeAtomicPublisher
import io.github.ccch1mneyyy.dsh_app.isolation.NativeRuntimePublication
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimePublicationKernel
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimePublicationResult
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataCode
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataFailure
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeAtomicPublisherTest {
    private val kernel = RuntimePublicationKernel { fd, source, target -> NativeRuntimePublication.publishNoReplace(fd, source, target) }
    private val segments = listOf("publication-test")
    private fun fixture(block: (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "atomic-publisher-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val target = File(root, "minimax-bot-runtime/publication-test")
        try { block(context, target) } finally { root.deleteRecursively() }
    }
    private fun expectFailure(expected: RuntimeDataCode, block: () -> Unit) {
        try { block(); fail("runtime_publication_should_be_refused") }
        catch (error: RuntimeDataFailure) { assertEquals(expected, error.code); assertNull(error.cause) }
    }

    @Test fun publishOncePreservesTheOriginalOnRetryAndCleansPrivateStage() = fixture { context, root ->
        val publisher = RuntimeAtomicPublisher.open(context, segments)
        try {
            assertEquals("RuntimeAtomicPublisher(REDACTED)", publisher.toString())
            assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("document.json", "first".toByteArray()))
            assertEquals(RuntimePublicationResult.ALREADY_EXISTS, publisher.publishIfAbsent("document.json", "second".toByteArray()))
            assertEquals("first", File(root, "document.json").readText())
            assertEquals(0, Os.stat(File(root, "document.json").path).st_mode and 63)
            assertEquals(listOf("document.json"), root.list()!!.sorted())
        } finally { publisher.close() }
        expectFailure(RuntimeDataCode.CLOSED) { publisher.publishIfAbsent("other.json", byteArrayOf()) }
        publisher.close()
    }

    @Test fun aLostKernelResultIsUnknownAndRetryDoesNotOverwritePublishedData() = fixture { context, root ->
        val uncertain = RuntimePublicationKernel { fd, source, target ->
            val result = NativeRuntimePublication.publishNoReplace(fd, source, target)
            if (result == 0) throw IOException("private-fault-sentinel")
            result
        }
        RuntimeAtomicPublisher.open(context, segments, uncertain).use { publisher ->
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, publisher.publishIfAbsent("document.json", "first".toByteArray()))
            assertEquals("first", File(root, "document.json").readText())
            assertEquals(RuntimePublicationResult.ALREADY_EXISTS, publisher.publishIfAbsent("document.json", "second".toByteArray()))
            assertEquals("first", File(root, "document.json").readText())
            assertEquals(listOf("document.json"), root.list()!!.sorted())
        }
    }

    @Test fun directorySyncFailureAfterRenameCannotBeReportedAsRejectedOrCommitted() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments, kernel, syncDirectory = { throw IOException("private-fault-sentinel") }).use { publisher ->
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, publisher.publishIfAbsent("document.json", "complete".toByteArray()))
            assertEquals("complete", File(root, "document.json").readText())
        }
        RuntimeAtomicPublisher.open(context, segments, kernel).use { publisher ->
            assertEquals(RuntimePublicationResult.ALREADY_EXISTS, publisher.publishIfAbsent("document.json", "replacement".toByteArray()))
            assertEquals("complete", File(root, "document.json").readText())
        }
    }

    @Test fun invalidTargetsBudgetsAndUiThreadAreRefusedBeforeKernelEntry() = fixture { context, root ->
        val calls = AtomicInteger()
        val tracked = RuntimePublicationKernel { fd, source, target -> calls.incrementAndGet(); kernel.publishNoReplace(fd, source, target) }
        RuntimeAtomicPublisher.open(context, segments, tracked, maxBytes = 16).use { publisher ->
            for (name in listOf("/absolute", "..", "../outside", "subdir/file", "bad\\name", ".native-publish-reserved.tmp")) {
                expectFailure(RuntimeDataCode.INVALID) { publisher.publishIfAbsent(name, byteArrayOf()) }
            }
            expectFailure(RuntimeDataCode.INVALID) { publisher.publishIfAbsent("document.json", ByteArray(17)) }
            val code = AtomicReference<RuntimeDataCode>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try { publisher.publishIfAbsent("document.json", byteArrayOf()) }
                catch (error: RuntimeDataFailure) { code.set(error.code) }
            }
            assertEquals(RuntimeDataCode.WORKER_REQUIRED, code.get())
            assertEquals(0, calls.get())
            assertTrue(root.list().isNullOrEmpty())
        }
    }

    @Test fun knownKernelRefusalCleansTheStageAndLeavesNoPublishedFile() = fixture { context, root ->
        val unavailable = RuntimePublicationKernel { _, _, _ -> OsConstants.ENOSYS }
        RuntimeAtomicPublisher.open(context, segments, unavailable).use { publisher ->
            expectFailure(RuntimeDataCode.UNAVAILABLE) { publisher.publishIfAbsent("document.json", "private-test-body".toByteArray()) }
            assertTrue(root.list().isNullOrEmpty())
        }
        // Zero-length files are complete publications too; they must not be
        // mistaken for the absence of a target when retried.
        RuntimeAtomicPublisher.open(context, segments, kernel).use { publisher ->
            assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("empty.json", byteArrayOf()))
            assertEquals(RuntimePublicationResult.ALREADY_EXISTS, publisher.publishIfAbsent("empty.json", "replacement".toByteArray()))
            assertTrue(File(root, "empty.json").isFile)
            assertEquals(0L, File(root, "empty.json").length())
            assertEquals(listOf("empty.json"), root.list()!!.sorted())
        }
    }

    @Test fun twoIndependentOwnersHaveOneCompletePublishedWinner() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments, kernel).use { first ->
            RuntimeAtomicPublisher.open(context, segments, kernel).use { second ->
                val go = CountDownLatch(1)
                val workers = Executors.newFixedThreadPool(2)
                try {
                    val a = "first".repeat(4096).toByteArray()
                    val b = "second".repeat(4096).toByteArray()
                    val results = listOf(first to a, second to b).map { (publisher, bytes) ->
                        workers.submit<RuntimePublicationResult> { check(go.await(5, TimeUnit.SECONDS)); publisher.publishIfAbsent("document.json", bytes) }
                    }
                    go.countDown()
                    val actual = results.map { it.get(5, TimeUnit.SECONDS) }
                    assertEquals(1, actual.count { it == RuntimePublicationResult.PUBLISHED })
                    assertEquals(1, actual.count { it == RuntimePublicationResult.ALREADY_EXISTS })
                    val bytes = File(root, "document.json").readBytes()
                    assertTrue(a.contentEquals(bytes) || b.contentEquals(bytes))
                    assertEquals(listOf("document.json"), root.list()!!.sorted())
                } finally { workers.shutdownNow(); check(workers.awaitTermination(5, TimeUnit.SECONDS)) }
            }
        }
    }
}
