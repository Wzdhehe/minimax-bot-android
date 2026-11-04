// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.os.Process
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The descriptor is the only channel that carries a live session into the
 * guest. It must be owner-only, must replace rather than follow a planted
 * symlink, and must refuse a malformed session before anything is written. */
@RunWith(AndroidJUnit4::class)
class RuntimeDescriptorTest {
    private val commit = "97818b2890b4acae1c6bd1200a226ff506680628"

    private fun session(
        entryPath: String = "/data/app/runtime/entry.mjs",
        pinCommit: String = commit,
        port: Int = 8788,
        token: String = "b".repeat(64),
        brokerBaseUrl: String = "http://127.0.0.1:43117",
        brokerToken: String = "a".repeat(64),
    ) = RuntimeDescriptorSession(entryPath, pinCommit, port, token, brokerBaseUrl, brokerToken)

    private fun fixture(block: (File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.noBackupFilesDir, "descriptor-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun theDescriptorIsAnOwnerOnlyRegularFileTheGuestCanRead() = fixture { root ->
        val path = RuntimeDescriptor.write(root, session())
        assertEquals(RuntimeDescriptor.FILE_NAME, path.name)
        val info = Os.lstat(path.path)
        assertTrue(info.st_mode and android.system.OsConstants.S_IFMT == android.system.OsConstants.S_IFREG)
        assertEquals(Process.myUid(), info.st_uid)
        assertEquals(0, info.st_mode and 63)
        val parsed = JSONObject(path.readText())
        assertEquals("/data/app/runtime/entry.mjs", parsed.getString("entryPath"))
        assertEquals(commit, parsed.getString("pinCommit"))
        assertEquals(8788, parsed.getInt("port"))
        assertEquals("b".repeat(64), parsed.getString("token"))
        assertEquals("http://127.0.0.1:43117", parsed.getJSONObject("broker").getString("baseUrl"))
        assertEquals("a".repeat(64), parsed.getJSONObject("broker").getString("token"))
        assertTrue(RuntimeDescriptor.descriptorIsOwnerOnly(path))
        // The staging file never survives a successful write.
        assertEquals(listOf(RuntimeDescriptor.FILE_NAME), root.list()!!.sorted())
    }

    @Test fun aPlantedSymlinkIsReplacedInsteadOfBeingFollowed() = fixture { root ->
        val outside = File(root, "outside.json").apply { writeText("{\"entryPath\":\"attacker\"}") }
        Os.symlink(outside.path, File(root, RuntimeDescriptor.FILE_NAME).path)
        val path = RuntimeDescriptor.write(root, session(port = 9001))
        val parsed = JSONObject(path.readText())
        assertEquals(9001, parsed.getInt("port"))
        assertEquals("{\"entryPath\":\"attacker\"}", outside.readText())
        assertTrue(RuntimeDescriptor.descriptorIsOwnerOnly(path))
    }

    @Test fun theTransferredFdIsReadOnlyAndKeepsItsOwnPublishedGeneration() = fixture { root ->
        RuntimeDescriptor.writeForTransfer(root, session(port = 8788)).use { transferred ->
            val info = Os.fstat(transferred.fileDescriptor)
            assertEquals(Process.myUid(), info.st_uid)
            assertEquals(0, info.st_mode and 63)
            try {
                Os.write(transferred.fileDescriptor, byteArrayOf(1), 0, 1)
                fail("descriptor_must_be_read_only")
            } catch (error: ErrnoException) { assertEquals(OsConstants.EBADF, error.errno) }
            // Atomic replacement must not change the already-granted FD.
            RuntimeDescriptor.write(root, session(port = 9001))
            val text = ParcelFileDescriptor.AutoCloseInputStream(transferred.dup()).bufferedReader().use { it.readText() }
            assertEquals(8788, JSONObject(text).getInt("port"))
            assertEquals(9001, JSONObject(File(root, RuntimeDescriptor.FILE_NAME).readText()).getInt("port"))
        }
    }

    @Test fun anOversizedCatalogIsRefusedBeforeWritingAFile() = fixture { root ->
        val largeCatalog = "{\"providers\":[{\"name\":\"" + "a".repeat(1024 * 1024) + "\"}]}"
        val normal = session()
        val oversized = RuntimeDescriptorSession(normal.entryPath, normal.pinCommit, normal.port, normal.token,
            normal.brokerBaseUrl, normal.brokerToken, largeCatalog)
        try { RuntimeDescriptor.writeForTransfer(root, oversized); fail("descriptor_expected_refusal") }
        catch (_: IllegalArgumentException) { }
        assertFalse(File(root, RuntimeDescriptor.FILE_NAME).exists())
    }

    @Test fun aMalformedSessionIsRefusedBeforeAnythingIsWritten() = fixture { root ->
        val cases = listOf(
            session(entryPath = "relative/entry.mjs"),
            session(pinCommit = "97818b2"),
            session(port = 0),
            session(token = "short"),
            session(brokerBaseUrl = "http://10.0.0.1:8080"),
            session(brokerToken = "short"),
            RuntimeDescriptorSession("/data/app/runtime/entry.mjs", commit, 8788, "b".repeat(64),
                "http://127.0.0.1:43117", null),
            RuntimeDescriptorSession("/data/app/runtime/entry.mjs", commit, 8788, "b".repeat(64),
                null, "a".repeat(64)),
        )
        for (invalid in cases) {
            try { RuntimeDescriptor.write(root, invalid); fail("descriptor_expected_refusal") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
        assertFalse(File(root, RuntimeDescriptor.FILE_NAME).exists())
        assertTrue(root.list()!!.isEmpty())
    }
}
