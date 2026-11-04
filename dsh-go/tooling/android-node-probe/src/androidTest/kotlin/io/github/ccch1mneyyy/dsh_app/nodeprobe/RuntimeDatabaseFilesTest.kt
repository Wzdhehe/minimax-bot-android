// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ContextWrapper
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDatabaseFiles
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataCode
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataFailure
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeDatabaseFilesTest {
    private fun fixture(block: (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "database-files-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        try { block(context, root) } finally { root.deleteRecursively() }
    }

    private fun denied(block: () -> Unit) {
        try { block(); fail("runtime_data_should_be_refused") }
        catch (error: RuntimeDataFailure) {
            assertEquals(RuntimeDataCode.UNAVAILABLE, error.code)
            assertEquals("runtime_data_unavailable", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun persistentFilesReopenWithoutTruncationAndOnlyFourRegularFdsAreDuplicated() = fixture { context, _ ->
        val marker = "durable-native-data".toByteArray()
        RuntimeDatabaseFiles.open(context).use { owner ->
            assertEquals("RuntimeDatabaseFiles(REDACTED)", owner.toString())
            val transferred = owner.duplicateForTransfer()
            try {
                assertEquals(4, transferred.size)
                transferred.forEach {
                    val info = Os.fstat(it.fileDescriptor)
                    assertEquals(OsConstants.S_IFREG, info.st_mode and OsConstants.S_IFMT)
                    assertEquals(0, info.st_mode and 63)
                }
                assertEquals(marker.size, Os.pwrite(transferred[0].fileDescriptor, marker, 0, marker.size, 0L))
                Os.fsync(transferred[0].fileDescriptor)
            } finally { transferred.forEach { it.close() } }
        }
        RuntimeDatabaseFiles.open(context).use { reopened ->
            val transferred = reopened.duplicateForTransfer()
            try {
                val bytes = ByteArray(marker.size)
                assertEquals(bytes.size, Os.pread(transferred[0].fileDescriptor, bytes, 0, bytes.size, 0L))
                assertTrue(marker.contentEquals(bytes))
            } finally { transferred.forEach { it.close() } }
        }
    }

    @Test fun closingOwnerDoesNotPretendToRevokeAnAlreadyDuplicatedFd() = fixture { context, _ ->
        val owner = RuntimeDatabaseFiles.open(context)
        val transferred = owner.duplicateForTransfer()
        owner.close()
        try {
            assertEquals(OsConstants.S_IFREG, Os.fstat(transferred[0].fileDescriptor).st_mode and OsConstants.S_IFMT)
            try { owner.duplicateForTransfer(); fail("closed_owner_duplicated_fd") }
            catch (error: RuntimeDataFailure) { assertEquals(RuntimeDataCode.CLOSED, error.code) }
        } finally { transferred.forEach { it.close() }; owner.close() }
    }

    @Test fun databaseSymlinkCannotReadOrTruncateAnOutsidePrivateFile() = fixture { context, root ->
        val outside = File(root, "outside-private").apply { writeText("private-native-marker") }
        RuntimeDatabaseFiles.open(context).close()
        val directory = File(root, "minimax-bot-runtime/v2/sqlite")
        val link = File(directory, "runtime-state.sqlite")
        assertTrue(link.delete())
        Os.symlink(outside.path, link.path)
        try {
            denied { RuntimeDatabaseFiles.open(context).close() }
            assertEquals("private-native-marker", outside.readText())
        } finally { link.delete() }
    }

    @Test fun symlinkedDataDirectoryIsRefusedBeforeItCreatesOutsideChildren() = fixture { context, root ->
        val outside = File(root, "outside-private-directory").apply { mkdirs() }
        val link = File(root, "minimax-bot-runtime")
        Os.symlink(outside.path, link.path)
        try {
            denied { RuntimeDatabaseFiles.open(context).close() }
            assertTrue(outside.list().isNullOrEmpty())
        } finally { link.delete() }
    }

    @Test fun hardLinkCannotBypassThePlatformOrDataOwnerBoundary() = fixture { context, root ->
        val outside = File(root, "outside-hardlink").apply { writeText("private-native-marker") }
        RuntimeDatabaseFiles.open(context).close()
        val directory = File(root, "minimax-bot-runtime/v2/sqlite")
        assertTrue(File(directory, "runtime-state.sqlite").delete())
        Os.chmod(outside.path, 384)
        val target = File(directory, "runtime-state.sqlite")
        try { Os.link(outside.path, target.path) }
        catch (error: ErrnoException) {
            // On platforms that disallow app hardlinks the setup itself is
            // refused. Verify that boundary; do not pretend the owner branch ran.
            assertTrue("hardlink_setup_errno_${error.errno}", error.errno == OsConstants.EPERM || error.errno == OsConstants.EACCES)
            assertFalse(target.exists())
            assertEquals("private-native-marker", outside.readText())
            return@fixture
        }
        assertEquals(2L, Os.stat(outside.path).st_nlink)
        denied { RuntimeDatabaseFiles.open(context).close() }
        assertEquals("private-native-marker", outside.readText())
    }

    @Test fun openingPersistentFilesIsRefusedOnTheUiThreadBeforeFilesystemWork() = fixture { context, root ->
        val code = AtomicReference<RuntimeDataCode>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { RuntimeDatabaseFiles.open(context).close() }
            catch (error: RuntimeDataFailure) { code.set(error.code) }
        }
        assertEquals(RuntimeDataCode.WORKER_REQUIRED, code.get())
        assertFalse(File(root, "minimax-bot-runtime").exists())
    }
}
