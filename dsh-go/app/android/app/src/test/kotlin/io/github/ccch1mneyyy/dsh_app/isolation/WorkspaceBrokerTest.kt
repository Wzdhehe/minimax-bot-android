// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Host-JVM unit tests for the workspace broker (no Android device needed). */
class WorkspaceBrokerTest {

    private fun tempDir(): File = Files.createTempDirectory("mbot-broker").toFile()

    @Test fun growingOrUnendingInputStopsAfterOneByteBeyondBudget() {
        var consumed = 0
        val input = object : InputStream() {
            override fun available() = 0
            override fun read(): Int { consumed++; return 'x'.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                check(consumed <= 33) { "Read past the hard budget" }
                bytes.fill('x'.code.toByte(), offset, offset + length)
                return length
            }
        }
        assertNull(readWorkspaceBytes(input, 32))
        assertEquals(33, consumed)
        assertEquals("abcd", String(readWorkspaceBytes("abcd".byteInputStream(), 4)!!))
        assertNull(readWorkspaceBytes("abcde".byteInputStream(), 4))
    }

    @Test fun largeDirectoriesFailWithoutReturningTruncatedEntries() {
        val root = tempDir()
        try {
            repeat(1001) { File(root, "item-$it").createNewFile() }
            val response = WorkspaceBroker(WorkspacePathPolicy.open(root)).listDir(".")
            assertEquals(BrokerResponse.TOO_LARGE, response.code)
            assertNull(response.entries)
        } finally { root.deleteRecursively() }
    }

    @Test fun directoryNameByteBudgetAppliesBeforeTheEntryCountLimit() {
        val root = tempDir()
        try {
            repeat(600) { File(root, "${it}-" + "x".repeat(240)).createNewFile() }
            val response = WorkspaceBroker(WorkspacePathPolicy.open(root)).listDir(".")
            assertEquals(BrokerResponse.TOO_LARGE, response.code)
            assertNull(response.entries)
        } finally { root.deleteRecursively() }
    }

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
