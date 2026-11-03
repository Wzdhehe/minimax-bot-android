// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Host-JVM unit tests for the workspace broker (no Android device needed). */
class WorkspaceBrokerTest {

    private fun tempDir(): File = Files.createTempDirectory("mbot-broker").toFile()

    @Test fun readsAndWritesInsideWorkspace() {
        val root = tempDir()
        val broker = WorkspaceBroker(WorkspacePathPolicy.open(root))
        assertTrue(broker.writeFile("notes/a.txt", "hello").ok)
        val read = broker.readFile("notes/a.txt")
        assertTrue(read.ok)
        assertEquals("hello", read.text)
        assertEquals(listOf("notes"), broker.listDir(".").entries)
    }

    @Test fun refusesTraversalAndAbsolutePaths() {
        val broker = WorkspaceBroker(WorkspacePathPolicy.open(tempDir()))
        val traversal = broker.readFile("../secret.txt")
        assertFalse(traversal.ok)
        assertEquals(WorkspacePathError.INVALID, traversal.code)
        val absolute = broker.readFile("/etc/passwd")
        assertFalse(absolute.ok)
        assertEquals(WorkspacePathError.INVALID, absolute.code)
    }

    @Test fun refusesSymlinkEscape() {
        val root = tempDir()
        val outside = tempDir()
        File(outside, "secret.txt").writeText("secret")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val broker = WorkspaceBroker(WorkspacePathPolicy.open(root))
        val read = broker.readFile("link/secret.txt")
        assertFalse(read.ok)
        assertEquals(WorkspacePathError.OUTSIDE, read.code)
    }

    @Test fun missingFileIsReportedNotCreated() {
        val broker = WorkspaceBroker(WorkspacePathPolicy.open(tempDir()))
        val read = broker.readFile("nope.txt")
        assertFalse(read.ok)
        assertEquals(BrokerResponse.NOT_FOUND, read.code)
    }

    @Test fun protocolHasNoCredentialOperation() {
        assertNull(BrokerOp.fromWire("read_key"))
        assertNull(BrokerOp.fromWire("get_api_key"))
        assertNull(BrokerOp.fromWire("reveal_api_key"))
        assertEquals(3, BrokerOp.values().size)
    }
}
