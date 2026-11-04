// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeAtomicPublisher
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataCode
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataFailure
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimePrivateDirectory
import io.github.ccch1mneyyy.dsh_app.isolation.readRuntimeFileSnapshot
import io.github.ccch1mneyyy.dsh_app.isolation.runtimeFileSize
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFileSnapshotTest {
    private val segments = listOf("files", "snapshots")
    private fun fixture(block: (ContextWrapper, File, RuntimePrivateDirectory, RuntimeAtomicPublisher) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "snapshot-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        try {
            RuntimeAtomicPublisher.open(context, segments).use { publisher ->
                RuntimePrivateDirectory.open(context, segments).use { directory ->
                    block(context, File(root, "minimax-bot-runtime/files/snapshots"), directory, publisher)
                }
            }
        } finally { root.deleteRecursively() }
    }
    private fun failure(code: RuntimeDataCode, block: () -> Unit) {
        try { block(); fail("snapshot_expected_refusal") }
        catch (error: RuntimeDataFailure) { assertEquals(code, error.code); assertNull(error.cause) }
    }

    @Test fun snapshotsPreserveBinaryBytesEmptyFilesAndExplicitMissingResults() = fixture { _, _, directory, publisher ->
        val bytes = fileProbeBytes(false)
        publisher.publishIfAbsent("payload.bin", bytes)
        publisher.publishIfAbsent("empty.bin", byteArrayOf())
        assertTrue(bytes.contentEquals(readRuntimeFileSnapshot(directory, "payload.bin", 1024 * 1024)))
        assertEquals(bytes.size.toLong(), runtimeFileSize(directory, "payload.bin"))
        assertEquals(0, readRuntimeFileSnapshot(directory, "empty.bin", 1).size)
        failure(RuntimeDataCode.NOT_FOUND) { readRuntimeFileSnapshot(directory, "missing.bin", 1024) }
        failure(RuntimeDataCode.INVALID) { readRuntimeFileSnapshot(directory, "../outside", 1024) }
        failure(RuntimeDataCode.INVALID) { runtimeFileSize(directory, ".native-publish-stage.tmp") }
    }

    @Test fun symlinksNonPrivateObjectsDirectoriesAndOversizeFilesAreRefused() = fixture { _, root, directory, publisher ->
        val outside = File(root.parentFile, "outside-private.bin").apply { writeText("private-outside-sentinel") }
        Os.chmod(outside.path, 384)
        Os.symlink(outside.path, File(root, "link.bin").path)
        failure(RuntimeDataCode.DENIED) { readRuntimeFileSnapshot(directory, "link.bin", 1024) }
        failure(RuntimeDataCode.DENIED) { runtimeFileSize(directory, "link.bin") }
        assertEquals("private-outside-sentinel", outside.readText())
        publisher.replace("public.bin", "public".toByteArray())
        Os.chmod(File(root, "public.bin").path, 420 /* 0644 */)
        failure(RuntimeDataCode.DENIED) { readRuntimeFileSnapshot(directory, "public.bin", 1024) }
        File(root, "directory").mkdir()
        failure(RuntimeDataCode.DENIED) { runtimeFileSize(directory, "directory") }
        val oversized = File(root, "large.bin").apply { writeBytes(ByteArray(1024 * 1024 + 1)) }
        Os.chmod(oversized.path, 384)
        assertEquals(1024 * 1024 + 1L, runtimeFileSize(directory, "large.bin"))
        failure(RuntimeDataCode.TOO_LARGE) { readRuntimeFileSnapshot(directory, "large.bin", 1024 * 1024) }
    }

    @Test fun aMovedDirectoryCapabilityNeverFollowsItsReplacementSymlink() = fixture { _, root, directory, publisher ->
        publisher.replace("document.bin", "authorized-original".toByteArray())
        val moved = File(root.parentFile, "retired-directory")
        assertTrue(root.renameTo(moved))
        val outside = File(root.parentFile, "outside-directory").apply { mkdir() }
        File(outside, "document.bin").writeText("private-outside-sentinel")
        Os.chmod(File(outside, "document.bin").path, 384)
        Os.symlink(outside.path, root.path)
        // openat uses the original Main-owned directory FD, not root.path.
        assertEquals("authorized-original", readRuntimeFileSnapshot(directory, "document.bin", 1024).toString(Charsets.UTF_8))
        assertEquals("authorized-original".length.toLong(), runtimeFileSize(directory, "document.bin"))
        assertEquals("private-outside-sentinel", File(outside, "document.bin").readText())
    }

    @Test fun snapshotsDuringAtomicReplacementAreAlwaysWholeCommittedVersions() = fixture { _, _, directory, publisher ->
        val a = "original".repeat(8192).toByteArray()
        val b = "replacement".repeat(8192).toByteArray()
        publisher.replace("document.bin", a)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val write = worker.submit { repeat(16) { publisher.replace("document.bin", if (it % 2 == 0) b else a) } }
            repeat(64) {
                val bytes = readRuntimeFileSnapshot(directory, "document.bin", 1024 * 1024)
                assertTrue(a.contentEquals(bytes) || b.contentEquals(bytes))
            }
            write.get(10, TimeUnit.SECONDS)
        } finally { worker.shutdownNow(); check(worker.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
