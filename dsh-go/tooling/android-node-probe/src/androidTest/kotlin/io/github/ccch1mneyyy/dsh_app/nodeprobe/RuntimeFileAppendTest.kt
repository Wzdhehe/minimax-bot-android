// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.*
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFileAppendTest {
    private val segments = listOf("files", "append")
    private fun fixture(block: (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "append-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        try { block(context, File(root, "minimax-bot-runtime/files/append")) }
        finally { root.deleteRecursively() }
    }
    private fun failure(code: RuntimeDataCode, block: () -> Unit) {
        try { block(); fail("file_append_expected_refusal") }
        catch (error: RuntimeDataFailure) { assertEquals(code, error.code); assertNull(error.cause) }
    }

    @Test fun binaryEmptyAndBoundedAppendsPreserveCompleteVersions() = fixture { context, root ->
        val owner = RuntimeAtomicPublisher.open(context, segments, maxBytes = 8)
        owner.use {
            val prefix = byteArrayOf(1, 0, -1)
            assertEquals(RuntimePublicationResult.PUBLISHED, owner.append("history.bin", prefix))
            ParcelFileDescriptor.open(File(root, "history.bin"), ParcelFileDescriptor.MODE_READ_ONLY).use { old ->
                assertEquals(RuntimePublicationResult.PUBLISHED, owner.append("history.bin", byteArrayOf(2, 3)))
                val bytes = ByteArray(8)
                assertEquals(3, Os.pread(old.fileDescriptor, bytes, 0, bytes.size, 0L))
                assertTrue(prefix.contentEquals(bytes.copyOf(3)))
            }
            assertEquals(RuntimePublicationResult.PUBLISHED, owner.append("history.bin", byteArrayOf()))
            failure(RuntimeDataCode.TOO_LARGE) { owner.append("history.bin", ByteArray(4)) }
            assertTrue(byteArrayOf(1, 0, -1, 2, 3).contentEquals(File(root, "history.bin").readBytes()))
            assertEquals(RuntimePublicationResult.PUBLISHED, owner.append("empty.bin", byteArrayOf()))
            assertEquals(0L, File(root, "empty.bin").length())
            assertEquals(0, Os.stat(File(root, "history.bin").path).st_mode and 63)
            assertEquals(listOf("empty.bin", "history.bin"), root.list()!!.sorted())
            val code = AtomicReference<RuntimeDataCode>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try { owner.append("ui.bin", byteArrayOf()) }
                catch (error: RuntimeDataFailure) { code.set(error.code) }
            }
            assertEquals(RuntimeDataCode.WORKER_REQUIRED, code.get())
            assertFalse(File(root, "ui.bin").exists())
        }
        failure(RuntimeDataCode.CLOSED) { owner.append("history.bin", byteArrayOf()) }
    }

    @Test fun independentOwnersCannotLoseOrInterleaveEachOthersRecords() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { first ->
            RuntimeAtomicPublisher.open(context, segments).use { second ->
                val go = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val futures = listOf(first, second).mapIndexed { index, owner ->
                        pool.submit {
                            check(go.await(5, TimeUnit.SECONDS))
                            repeat(12) { row ->
                                assertEquals(RuntimePublicationResult.PUBLISHED, owner.append("history.jsonl", "$index:$row\n".toByteArray()))
                            }
                        }
                    }
                    go.countDown()
                    futures.forEach { it.get(20, TimeUnit.SECONDS) }
                    val records = File(root, "history.jsonl").readLines()
                    val expected = (0..1).flatMap { index -> (0..11).map { row -> "$index:$row" } }
                    assertEquals(expected.sorted(), records.sorted())
                    assertEquals(24, records.size)
                } finally { pool.shutdownNow() }
            }
            // Releasing one owner cannot retire a still-live owner's gate.
            assertEquals(RuntimePublicationResult.PUBLISHED, first.append("history.jsonl", "last\n".toByteArray()))
        }
        assertEquals(25, File(root, "history.jsonl").readLines().size)
    }

    @Test fun replacementJoinsTheSameGateWhileUnrelatedDirectoriesCanProgress() = fixture { context, root ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val held = object : RuntimePublicationKernel {
            override fun publishNoReplace(directory: Int, source: String, target: String) =
                NativeRuntimePublication.publishNoReplace(directory, source, target)
            override fun replace(directory: Int, source: String, target: String): Int {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return NativeRuntimePublication.replace(directory, source, target)
            }
        }
        RuntimeAtomicPublisher.open(context, segments).use { it.publishIfAbsent("history.jsonl", "prefix\n".toByteArray()) }
        RuntimeAtomicPublisher.open(context, segments, held).use { first ->
            RuntimeAtomicPublisher.open(context, segments).use { second ->
                RuntimeAtomicPublisher.open(context, listOf("files", "unrelated")).use { unrelated ->
                    val pool = Executors.newFixedThreadPool(3)
                    try {
                        val append = pool.submit<RuntimePublicationResult> { first.append("history.jsonl", "suffix\n".toByteArray()) }
                        assertTrue(entered.await(5, TimeUnit.SECONDS))
                        val replace = pool.submit<RuntimePublicationResult> {
                            secondStarted.countDown()
                            second.replace("history.jsonl", "replacement\n".toByteArray())
                        }
                        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                        val independent = pool.submit<RuntimePublicationResult> { unrelated.publishIfAbsent("separate.json", byteArrayOf(1)) }
                        assertEquals(RuntimePublicationResult.PUBLISHED, independent.get(3, TimeUnit.SECONDS))
                        try { replace.get(150, TimeUnit.MILLISECONDS); fail("file_append_missing_shared_gate") }
                        catch (_: TimeoutException) { }
                        release.countDown()
                        assertEquals(RuntimePublicationResult.PUBLISHED, append.get(5, TimeUnit.SECONDS))
                        assertEquals(RuntimePublicationResult.PUBLISHED, replace.get(5, TimeUnit.SECONDS))
                        assertEquals("replacement\n", File(root, "history.jsonl").readText())
                    } finally { release.countDown(); pool.shutdownNow() }
                }
            }
        }
    }

    @Test fun uncertainAppendIsNotRetriedOrReportedAsAnUnwrittenRecord() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { it.append("history.jsonl", "first\n".toByteArray()) }
        val lost = object : RuntimePublicationKernel {
            override fun publishNoReplace(directory: Int, source: String, target: String) =
                NativeRuntimePublication.publishNoReplace(directory, source, target)
            override fun replace(directory: Int, source: String, target: String): Int {
                val result = NativeRuntimePublication.replace(directory, source, target)
                if (result == 0) throw IOException("private-fault-sentinel")
                return result
            }
        }
        RuntimeAtomicPublisher.open(context, segments, lost).use {
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, it.append("history.jsonl", "second\n".toByteArray()))
        }
        RuntimeAtomicPublisher.open(context, segments, syncDirectory = { throw IOException("private-fault-sentinel") }).use {
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, it.append("history.jsonl", "third\n".toByteArray()))
        }
        assertEquals(listOf("first", "second", "third"), File(root, "history.jsonl").readLines())
        val publication = RuntimePublicationKernel { directory, source, target -> NativeRuntimePublication.publishNoReplace(directory, source, target) }
        RuntimeAtomicPublisher.open(context, segments, publication).use {
            failure(RuntimeDataCode.UNAVAILABLE) { it.append("history.jsonl", "never\n".toByteArray()) }
        }
        assertEquals(listOf("first", "second", "third"), File(root, "history.jsonl").readLines())
        assertEquals(listOf("history.jsonl"), root.list()!!.sorted())
    }

    @Test fun unsafeTargetsAndParentPathReplacementCannotEscapeTheDirectoryCap() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { owner ->
            val outside = File(root.parentFile, "outside").apply { mkdir() }
            val sentinel = File(outside, "sentinel.bin").apply { writeText("outside-sentinel") }
            Os.chmod(sentinel.path, 384)
            Os.symlink(sentinel.path, File(root, "link.bin").path)
            File(root, "directory").mkdir()
            owner.publishIfAbsent("public.bin", byteArrayOf(1))
            Os.chmod(File(root, "public.bin").path, 420)
            for (name in listOf("link.bin", "directory", "public.bin")) {
                failure(RuntimeDataCode.DENIED) { owner.append(name, byteArrayOf(2)) }
            }
            for (name in listOf("../outside", "/absolute", ".native-publish-reserved.tmp")) {
                failure(RuntimeDataCode.INVALID) { owner.append(name, byteArrayOf()) }
            }
            owner.append("history.jsonl", "first\n".toByteArray())
            val retained = File(root.parentFile, "retained")
            assertTrue(root.renameTo(retained))
            Os.symlink(outside.path, root.path)
            assertEquals(RuntimePublicationResult.PUBLISHED, owner.append("history.jsonl", "second\n".toByteArray()))
            assertEquals("first\nsecond\n", File(retained, "history.jsonl").readText())
            assertEquals("outside-sentinel", sentinel.readText())
            assertEquals(listOf("sentinel.bin"), outside.list()!!.sorted())
            assertFalse(retained.list()!!.any { it.startsWith(".native-publish-") })
        }
    }
}
