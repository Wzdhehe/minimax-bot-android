// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Host-JVM unit tests for the workspace path guard (no Android device needed). */
class WorkspacePathPolicyTest {

    private fun tempDir(): File = Files.createTempDirectory("mbot-guard").toFile()

    @Test fun refusesUnsafeRelativePaths() {
        val policy = WorkspacePathPolicy.open(tempDir())
        val nul = "a" + 0.toChar() + "b"
        val bad = listOf("", "   ", nul, "/etc/passwd", "../escape", "a/../../b", "dir\\file", "C:/win")
        for (input in bad) {
            val error = assertThrows(WorkspacePathError::class.java) { policy.resolve(input) }
            assertEquals(WorkspacePathError.INVALID, error.code)
        }
    }

    @Test fun resolvesInsideWorkspace() {
        val root = tempDir()
        val policy = WorkspacePathPolicy.open(root)
        assertEquals(File(root, "a/b.txt").canonicalFile.path, policy.resolve("a/b.txt"))
        assertTrue(File(policy.resolve("a/b.txt")).path.startsWith(root.canonicalFile.path))
    }

    @Test fun refusesSymlinkEscape() {
        val root = tempDir()
        val outside = tempDir()
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        val policy = WorkspacePathPolicy.open(root)
        val error = assertThrows(WorkspacePathError::class.java) { policy.resolve("link/x.txt") }
        assertEquals(WorkspacePathError.OUTSIDE, error.code)
    }

    @Test fun doesNotConfuseWorkspaceWithWorkspaceEvil() {
        val parent = tempDir()
        val root = File(parent, "workspace").apply { mkdirs() }
        val evil = File(parent, "workspace-evil").apply { mkdirs() }
        val policy = WorkspacePathPolicy.open(root)
        assertFalse(WorkspacePathPolicy.isInside(root.canonicalFile, evil.canonicalFile))
        assertTrue(WorkspacePathPolicy.isInside(root.canonicalFile, File(root, "sub").canonicalFile))
        assertThrows(WorkspacePathError::class.java) { policy.resolve("../workspace-evil/x") }
    }

    @Test fun missingRootIsRejected() {
        val error = assertThrows(WorkspacePathError::class.java) {
            WorkspacePathPolicy.open(File(tempDir(), "missing"))
        }
        assertEquals(WorkspacePathError.ROOT, error.code)
    }
}
