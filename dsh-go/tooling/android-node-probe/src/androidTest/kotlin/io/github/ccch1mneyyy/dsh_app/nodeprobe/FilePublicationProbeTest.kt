// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.NativeRuntimePublication
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests Main-only syscalls, not an isolated-process directory grant. */
@RunWith(AndroidJUnit4::class)
class FilePublicationProbeTest {
    private fun fixture(block: (File, Int) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "publication-test-${UUID.randomUUID()}").apply { mkdirs() }
        val directory = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            ParcelFileDescriptor.dup(directory).use { block(root, it.fd) }
        } finally { Os.close(directory); root.deleteRecursively() }
    }
    private fun stage(root: File, name: String, bytes: ByteArray) {
        File(root, name).writeBytes(bytes)
        ParcelFileDescriptor.open(File(root, name), ParcelFileDescriptor.MODE_READ_WRITE).use { Os.fsync(it.fileDescriptor) }
    }
    private fun expect(expected: Int, actual: Int) {
        assertTrue("publication_errno_$actual", expected == actual)
    }

    @Test fun publishesCompleteStagedFileAndSyncsItsDirectory() = fixture { root, fd ->
        val bytes = "complete-native-publication".toByteArray()
        stage(root, "stage.tmp", bytes)
        expect(0, NativeRuntimePublication.publishNoReplace(fd, "stage.tmp", "document.json"))
        assertFalse(File(root, "stage.tmp").exists())
        assertTrue(bytes.contentEquals(File(root, "document.json").readBytes()))
        val sync = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(sync) } finally { Os.close(sync) }
    }

    @Test fun anExistingTargetIsNeverReplaced() = fixture { root, fd ->
        stage(root, "stage.tmp", "new".toByteArray())
        stage(root, "document.json", "original".toByteArray())
        expect(OsConstants.EEXIST, NativeRuntimePublication.publishNoReplace(fd, "stage.tmp", "document.json"))
        assertEquals("original", File(root, "document.json").readText())
        assertEquals("new", File(root, "stage.tmp").readText())
    }

    @Test fun concurrentPublishersHaveExactlyOneWinnerWithACompleteTarget() = fixture { root, fd ->
        val first = "first-".repeat(4096).toByteArray()
        val second = "second-".repeat(4096).toByteArray()
        stage(root, "first.tmp", first); stage(root, "second.tmp", second)
        val workers = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        try {
            val results = listOf("first.tmp", "second.tmp").map { name ->
                workers.submit<Int> { check(go.await(5, TimeUnit.SECONDS)); NativeRuntimePublication.publishNoReplace(fd, name, "document.json") }
            }
            go.countDown()
            val codes = results.map { it.get(5, TimeUnit.SECONDS) }.sorted()
            expect(0, codes[0]); expect(OsConstants.EEXIST, codes[1])
            val actual = File(root, "document.json").readBytes()
            assertTrue(first.contentEquals(actual) || second.contentEquals(actual))
            assertEquals(1, listOf("first.tmp", "second.tmp").count { File(root, it).exists() })
        } finally { workers.shutdownNow(); check(workers.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun traversalAndANonDirectoryFdAreRefused() = fixture { root, fd ->
        stage(root, "stage.tmp", "unchanged".toByteArray())
        // Stage-only helpers cannot create/overwrite or delete an ordinary
        // committed filename even if trusted Main accidentally passes one.
        expect(-OsConstants.EINVAL, NativeRuntimePublication.openStage(fd, "stage.tmp"))
        expect(OsConstants.EINVAL, NativeRuntimePublication.removeStage(fd, "stage.tmp"))
        for (name in listOf("../outside", "subdir/file", ".", "..", "")) {
            expect(OsConstants.EINVAL, NativeRuntimePublication.publishNoReplace(fd, "stage.tmp", name))
        }
        ParcelFileDescriptor.open(File(root, "stage.tmp"), ParcelFileDescriptor.MODE_READ_WRITE).use {
            expect(OsConstants.ENOTDIR, NativeRuntimePublication.publishNoReplace(it.fd, "stage.tmp", "document.json"))
        }
        assertEquals("unchanged", File(root, "stage.tmp").readText())
        assertFalse(File(root, "document.json").exists())
    }
}
