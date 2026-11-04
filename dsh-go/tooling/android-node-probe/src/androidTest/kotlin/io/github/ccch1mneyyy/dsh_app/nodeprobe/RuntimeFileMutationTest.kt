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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeFileMutationTest {
    private val segments = listOf("files", "mutations")
    private fun fixture(block: (ContextWrapper, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "mutation-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        try { block(context, File(root, "minimax-bot-runtime/files/mutations")) }
        finally { root.deleteRecursively() }
    }
    private fun failure(code: RuntimeDataCode, block: () -> Unit) {
        try { block(); fail("file_mutation_expected_refusal") }
        catch (error: RuntimeDataFailure) { assertEquals(code, error.code); assertNull(error.cause) }
    }

    @Test fun exclusiveMovesReplacementAndRemovalPreserveTheSpecifiedFiles() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { owner ->
            owner.publishIfAbsent("source.tmp", "first".toByteArray())
            owner.publishIfAbsent("target.json", "original".toByteArray())
            assertEquals(RuntimePublicationResult.ALREADY_EXISTS, owner.move("source.tmp", "target.json", replace = false))
            assertEquals("first", File(root, "source.tmp").readText())
            assertEquals("original", File(root, "target.json").readText())
            ParcelFileDescriptor.open(File(root, "target.json"), ParcelFileDescriptor.MODE_READ_ONLY).use { old ->
                assertEquals(RuntimePublicationResult.PUBLISHED, owner.move("source.tmp", "target.json", replace = true))
                val bytes = ByteArray(8)
                assertEquals(8, Os.pread(old.fileDescriptor, bytes, 0, 8, 0L))
                assertEquals("original", bytes.toString(Charsets.UTF_8))
            }
            assertFalse(File(root, "source.tmp").exists())
            assertEquals("first", File(root, "target.json").readText())
            assertEquals(RuntimeDeletionResult.REMOVED, owner.remove("target.json"))
            failure(RuntimeDataCode.NOT_FOUND) { owner.remove("target.json") }
            failure(RuntimeDataCode.NOT_FOUND) { owner.move("missing.tmp", "new.json", false) }
            assertTrue(root.list().isNullOrEmpty())
        }
    }

    @Test fun lostKernelResultsAndPostMutationSyncFailuresAreUnknown() = fixture { context, root ->
        val lost = object : RuntimePublicationKernel {
            override fun publishNoReplace(directory: Int, source: String, target: String): Int {
                val result = NativeRuntimePublication.publishNoReplace(directory, source, target)
                if (result == 0) throw IOException("private-fault-sentinel")
                return result
            }
            override fun remove(directory: Int, name: String): Int {
                val result = NativeRuntimePublication.remove(directory, name)
                if (result == 0) throw IOException("private-fault-sentinel")
                return result
            }
        }
        RuntimeAtomicPublisher.open(context, segments).use { owner ->
            for (name in listOf("source.tmp", "remove.json", "sync-source.tmp", "sync-remove.json")) owner.publishIfAbsent(name, "complete".toByteArray())
        }
        RuntimeAtomicPublisher.open(context, segments, lost).use { owner ->
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, owner.move("source.tmp", "target.json", false))
            assertEquals(RuntimeDeletionResult.OUTCOME_UNKNOWN, owner.remove("remove.json"))
        }
        RuntimeAtomicPublisher.open(context, segments, syncDirectory = { throw IOException("private-fault-sentinel") }).use { owner ->
            assertEquals(RuntimePublicationResult.OUTCOME_UNKNOWN, owner.move("sync-source.tmp", "sync-target.json", true))
            assertEquals(RuntimeDeletionResult.OUTCOME_UNKNOWN, owner.remove("sync-remove.json"))
        }
        assertEquals(listOf("sync-target.json", "target.json"), root.list()!!.sorted())
        assertEquals("complete", File(root, "target.json").readText())
        assertEquals("complete", File(root, "sync-target.json").readText())
    }

    @Test fun symlinksDirectoriesPublicFilesAndInvalidNamesCannotBeMovedOrRemoved() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { owner ->
            val outside = File(root.parentFile, "outside.bin").apply { writeText("private-outside-sentinel") }
            Os.chmod(outside.path, 384)
            Os.symlink(outside.path, File(root, "link.bin").path)
            File(root, "directory").mkdir()
            owner.publishIfAbsent("public.bin", "original".toByteArray())
            Os.chmod(File(root, "public.bin").path, 420)
            for (name in listOf("link.bin", "directory", "public.bin")) {
                failure(RuntimeDataCode.DENIED) { owner.move(name, "target.json", true) }
                failure(RuntimeDataCode.DENIED) { owner.remove(name) }
            }
            for (name in listOf("../outside", "/absolute", ".native-publish-reserved.tmp")) {
                failure(RuntimeDataCode.INVALID) { owner.move(name, "target.json", false) }
                failure(RuntimeDataCode.INVALID) { owner.remove(name) }
            }
            failure(RuntimeDataCode.INVALID) { owner.move("public.bin", "public.bin", true) }
            assertFalse(File(root, "target.json").exists())
            assertEquals("private-outside-sentinel", outside.readText())
            assertEquals("original", File(root, "public.bin").readText())
            assertTrue(File(root, "directory").isDirectory)
        }
    }

    @Test fun publicationOnlyInjectionDoesNotAuthorizeReplacementOrRemoval() = fixture { context, root ->
        RuntimeAtomicPublisher.open(context, segments).use { owner ->
            owner.publishIfAbsent("source.tmp", "source".toByteArray())
            owner.publishIfAbsent("target.json", "target".toByteArray())
        }
        val publication = RuntimePublicationKernel { fd, source, target -> NativeRuntimePublication.publishNoReplace(fd, source, target) }
        RuntimeAtomicPublisher.open(context, segments, publication).use { owner ->
            failure(RuntimeDataCode.UNAVAILABLE) { owner.move("source.tmp", "target.json", true) }
            failure(RuntimeDataCode.UNAVAILABLE) { owner.remove("target.json") }
        }
        assertEquals("source", File(root, "source.tmp").readText())
        assertEquals("target", File(root, "target.json").readText())
    }
}
