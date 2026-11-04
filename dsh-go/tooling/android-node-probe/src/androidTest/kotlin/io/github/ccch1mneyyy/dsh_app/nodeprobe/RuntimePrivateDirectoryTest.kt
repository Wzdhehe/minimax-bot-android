// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.isolation.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimePrivateDirectoryTest {
    private val segments = listOf("files", "children")
    private fun fixture(block: (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "child-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        try { block(context, File(root, "minimax-bot-runtime/files/children")) }
        finally { root.deleteRecursively() }
    }
    private fun failure(code: RuntimeDataCode, block: () -> Unit) {
        try { block(); fail("runtime_child_expected_refusal") }
        catch (error: RuntimeDataFailure) { assertEquals(code, error.code); assertNull(error.cause) }
    }
    private fun publish(directory: RuntimePrivateDirectory, name: String, bytes: ByteArray) {
        val stage = ".native-publish-${UUID.randomUUID()}.tmp"
        val raw = directory.withDescriptor { NativeRuntimePublication.openStage(it, stage) }
        assertTrue(raw >= 3)
        ParcelFileDescriptor.adoptFd(raw).use {
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(it.fileDescriptor, bytes, offset, bytes.size - offset)
                check(written > 0); offset += written
            }
            Os.fsync(it.fileDescriptor)
        }
        directory.withDescriptor { assertEquals(0, NativeRuntimePublication.publishNoReplace(it, stage, name)) }
        directory.sync()
    }

    @Test fun privateChildrenAreExplicitlyCreatedAndCanBeReopenedWithoutCreation() = fixture { context, root ->
        RuntimePrivateDirectory.open(context, segments).use { parent ->
            failure(RuntimeDataCode.NOT_FOUND) { parent.openChild("missing").close() }
            assertFalse(File(root, "missing").exists())
            parent.openChild("messages", create = true).use { child ->
                assertEquals("RuntimePrivateDirectory(REDACTED)", child.toString())
                assertEquals(448, Os.stat(File(root, "messages").path).st_mode and 511)
                publish(child, "history.jsonl", "first\n".toByteArray())
                parent.openChild("messages").use { again ->
                    assertEquals(Os.fstat(child.descriptor.fileDescriptor).st_ino, Os.fstat(again.descriptor.fileDescriptor).st_ino)
                    assertEquals("first\n", readRuntimeFileSnapshot(again, "history.jsonl", 64).toString(Charsets.UTF_8))
                }
            }
        }
    }

    @Test fun renamedParentAndClosedAncestorsCannotRedirectOrInvalidateChildCaps() = fixture { context, root ->
        val parent = RuntimePrivateDirectory.open(context, segments)
        val retained = File(root.parentFile, "retained")
        val outside = File(root.parentFile, "outside").apply { mkdir() }
        assertTrue(root.renameTo(retained))
        Os.symlink(outside.path, root.path)
        try {
            parent.openChild("messages", create = true).use { child ->
                publish(child, "history.jsonl", "anchored\n".toByteArray())
                parent.close()
                child.openChild("archive", create = true).use { grandchild ->
                    child.close()
                    publish(grandchild, "backup.json", byteArrayOf(0, -1, 17))
                    assertTrue(byteArrayOf(0, -1, 17).contentEquals(readRuntimeFileSnapshot(grandchild, "backup.json", 64)))
                }
            }
            assertEquals("anchored\n", File(retained, "messages/history.jsonl").readText())
            assertTrue(File(retained, "messages/archive/backup.json").isFile)
            assertTrue(outside.list().isNullOrEmpty())
        } finally { parent.close() }
    }

    @Test fun symlinksFilesPublicDirectoriesAndMalformedNativeInputsAreRejected() = fixture { context, root ->
        RuntimePrivateDirectory.open(context, segments).use { parent ->
            val outside = File(root.parentFile, "outside").apply { mkdir() }
            val marker = File(outside, "sentinel").apply { writeText("outside-sentinel") }
            Os.symlink(outside.path, File(root, "link").path)
            File(root, "file").writeText("regular")
            File(root, "public").mkdir()
            Os.chmod(File(root, "public").path, 493)
            for (name in listOf("link", "file", "public")) {
                for (create in listOf(false, true)) failure(RuntimeDataCode.DENIED) { parent.openChild(name, create).close() }
            }
            for (name in listOf("../outside", "/absolute", ".", "..", "nested/child", ".native-publish-reserved.tmp")) {
                failure(RuntimeDataCode.INVALID) { parent.openChild(name, true).close() }
                parent.withDescriptor { assertEquals(-OsConstants.EINVAL, NativeRuntimePublication.openChildDirectory(it, name, true)) }
            }
            ParcelFileDescriptor.open(File(root, "file"), ParcelFileDescriptor.MODE_READ_ONLY).use {
                assertEquals(-OsConstants.ENOTDIR, NativeRuntimePublication.openChildDirectory(it.fd, "never", true))
            }
            Os.chmod(root.path, 493)
            try { parent.withDescriptor { assertEquals(-OsConstants.EACCES, NativeRuntimePublication.openChildDirectory(it, "never", true)) } }
            finally { Os.chmod(root.path, 448) }
            assertFalse(File(root, "never").exists())
            assertEquals("outside-sentinel", marker.readText())
            assertEquals(listOf("sentinel"), outside.list()!!.sorted())
        }
    }

    @Test fun uiThreadClosedCapsAndNinthLevelAreRejectedBeforeChildCreation() = fixture { context, root ->
        val parent = RuntimePrivateDirectory.open(context, segments)
        val code = AtomicReference<RuntimeDataCode>()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                try { parent.openChild("ui", true).close() }
                catch (error: RuntimeDataFailure) { code.set(error.code) }
            }
            assertEquals(RuntimeDataCode.WORKER_REQUIRED, code.get())
            assertFalse(File(root, "ui").exists())
        } finally { parent.close() }
        failure(RuntimeDataCode.CLOSED) { parent.openChild("closed", true).close() }
        assertFalse(File(root, "closed").exists())
        val fullDepth = List(8) { "d$it" }
        RuntimePrivateDirectory.open(context, fullDepth).use { deepest ->
            failure(RuntimeDataCode.INVALID) { deepest.openChild("ninth", true).close() }
            assertFalse(File(deepest.file, "ninth").exists())
        }
    }
}
