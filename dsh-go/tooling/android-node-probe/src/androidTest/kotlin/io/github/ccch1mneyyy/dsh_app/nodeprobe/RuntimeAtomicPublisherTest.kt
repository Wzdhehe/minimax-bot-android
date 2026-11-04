// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
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

    @Test fun atomicReplacementCreatesCompleteFilesAndKeepsExistingReadersOnTheOldInode() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { publisher ->
            val before = "old-document".toByteArray()
            val after = "new-document".repeat(8192).toByteArray()
            assertEquals(RuntimePublicationResult.PUBLISHED, publisher.replace("document.json", before))
            ParcelFileDescriptor.open(File(root, "document.json"), ParcelFileDescriptor.MODE_READ_ONLY).use { reader ->
                assertEquals(RuntimePublicationResult.PUBLISHED, publisher.replace("document.json", after))
                assertTrue(after.contentEquals(File(root, "document.json").readBytes()))
                val old = ByteArray(before.size)
                assertEquals(old.size, Os.pread(reader.fileDescriptor, old, 0, old.size, 0L))
                assertTrue(before.contentEquals(old))
            }
            assertEquals(RuntimePublicationResult.ALREADY_EXISTS, publisher.publishIfAbsent("document.json", before))
            assertTrue(after.contentEquals(File(root, "document.json").readBytes()))
            assertEquals(RuntimePublicationResult.PUBLISHED, publisher.replace("document.json", byteArrayOf()))
            assertEquals(0L, File(root, "document.json").length())
            assertEquals(0, Os.stat(File(root, "document.json").path).st_mode and 63)
            assertEquals(listOf("document.json"), root.list()!!.sorted())
        }
    }

    @Test fun replacementLosingItsKernelOrDirectorySyncResultIsUnknown() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { publisher ->
            publisher.replace("lost.json", "original".toByteArray())
            publisher.replace("sync.json", "original".toByteArray())
        }
        val uncertain = object : RuntimePublicationKernel {
            override fun publishNoReplace(directory: Int, source: String, target: String) =
                NativeRuntimePublication.publishNoReplace(directory, source, target)
            override fun replace(directory: Int, source: String, target: String): Int {
                val result = NativeRuntimePublication.replace(directory, source, target)
                if (result == 0) throw IOException("private-fault-sentinel")
                return result
            }
        }
        RuntimeAtomicPublisher.open(context, segments, uncertain).use { publisher ->
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, publisher.replace("lost.json", "complete".toByteArray()))
        }
        RuntimeAtomicPublisher.open(context, segments, syncDirectory = { throw IOException("private-fault-sentinel") }).use { publisher ->
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, publisher.replace("sync.json", "complete".toByteArray()))
        }
        for (name in listOf("lost.json", "sync.json")) assertEquals("complete", File(root, name).readText())
        assertEquals(listOf("lost.json", "sync.json"), root.list()!!.sorted())
    }

    @Test fun aPublicationOnlyKernelCannotSilentlyReplaceAnExistingDocument() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { publisher ->
            assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("document.json", "original".toByteArray()))
        }
        // A trusted injected kernel that only implements exclusive publication
        // explicitly refuses replacement; it must never fall back to overwrite.
        RuntimeAtomicPublisher.open(context, segments, kernel).use { publisher ->
            expectFailure(RuntimeDataCode.UNAVAILABLE) { publisher.replace("document.json", "new".toByteArray()) }
            assertEquals("original", File(root, "document.json").readText())
            assertEquals(listOf("document.json"), root.list()!!.sorted())
        }
    }

    @Test fun concurrentReplacementReadersObserveWholeOldOrNewDocuments() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { first ->
            RuntimeAtomicPublisher.open(context, segments).use { second ->
                val a = "first-document".repeat(8192).toByteArray()
                val b = "second-document".repeat(8192).toByteArray()
                first.replace("document.json", a)
                val workers = Executors.newFixedThreadPool(2)
                val go = CountDownLatch(1)
                try {
                    val jobs = listOf(first to a, second to b).map { (publisher, bytes) ->
                        workers.submit {
                            check(go.await(5, TimeUnit.SECONDS))
                            repeat(8) { assertEquals(RuntimePublicationResult.PUBLISHED, publisher.replace("document.json", bytes)) }
                        }
                    }
                    go.countDown()
                    repeat(64) {
                        val bytes = File(root, "document.json").readBytes()
                        assertTrue(a.contentEquals(bytes) || b.contentEquals(bytes))
                        Thread.yield()
                    }
                    jobs.forEach { it.get(15, TimeUnit.SECONDS) }
                    val bytes = File(root, "document.json").readBytes()
                    assertTrue(a.contentEquals(bytes) || b.contentEquals(bytes))
                    assertEquals(listOf("document.json"), root.list()!!.sorted())
                } finally { workers.shutdownNow(); check(workers.awaitTermination(5, TimeUnit.SECONDS)) }
            }
        }
    }

    @Test fun stageCreationPublicationAndCleanupNeverFollowAReplacedParentPath() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { publisher ->
            val unsupported = RuntimePublicationKernel { _, _, _ -> OsConstants.ENOSYS }
            RuntimeAtomicPublisher.open(context, segments, unsupported).use { refused ->
                publisher.publishIfAbsent("document.json", "original".toByteArray())
                val moved = File(root.parentFile, "retired-directory")
                assertTrue(root.renameTo(moved))
                val outside = File(root.parentFile, "outside-directory").apply { mkdir() }
                File(outside, "document.json").writeText("private-outside-sentinel")
                Os.symlink(outside.path, root.path)
                assertEquals(RuntimePublicationResult.PUBLISHED, publisher.publishIfAbsent("new.json", "new".toByteArray()))
                assertEquals(RuntimePublicationResult.PUBLISHED, publisher.replace("document.json", "replacement".toByteArray()))
                assertEquals(RuntimePublicationResult.ALREADY_EXISTS, publisher.publishIfAbsent("document.json", "ignored".toByteArray()))
                expectFailure(RuntimeDataCode.UNAVAILABLE) { refused.publishIfAbsent("refused.json", "staged".toByteArray()) }
                assertEquals("replacement", File(moved, "document.json").readText())
                assertEquals("new", File(moved, "new.json").readText())
                assertEquals(listOf("document.json", "new.json"), moved.list()!!.sorted())
                assertEquals("private-outside-sentinel", File(outside, "document.json").readText())
                assertEquals(listOf("document.json"), outside.list()!!.sorted())
            }
        }
    }
}
