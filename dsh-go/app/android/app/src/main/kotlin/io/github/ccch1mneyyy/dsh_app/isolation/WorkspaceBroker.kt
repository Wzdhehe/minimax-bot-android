// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Main-process workspace broker, pure Kotlin/JVM (unit-testable on the host JVM).
 *
 * This is the only way the isolated Agent process reaches `filesDir/workspace`: the isolated
 * UID has no app permissions and cannot read the app's private files itself. Every request is
 * resolved through [WorkspacePathPolicy], so the broker inherits the same boundary semantics
 * as the Node host guard (canonical path, NUL/absolute/`..` refused, symlink escape refused,
 * `workspace` vs `workspace-evil` not confused).
 *
 * The broker has no exec/shell surface and no credential surface: it reads and writes text
 * files inside the workspace and nothing else.
 */
class WorkspaceBroker(
    private val policy: WorkspacePathPolicy,
    private val maxTextBytes: Int = DEFAULT_MAX_TEXT_BYTES,
) {

    /** The canonical workspace root this broker serves. */
    val root: String get() = policy.root

    fun handle(request: BrokerRequest): BrokerResponse = when (request.op) {
        BrokerOp.READ_FILE -> readFile(request.path)
        BrokerOp.WRITE_FILE -> writeFile(request.path, request.text)
        BrokerOp.LIST_DIR -> listDir(request.path)
    }

    fun readFile(path: String): BrokerResponse {
        val file = try {
            File(policy.resolve(path))
        } catch (e: WorkspacePathError) {
            return BrokerResponse.error(e.code)
        }
        if (!file.exists()) return BrokerResponse.error(BrokerResponse.NOT_FOUND)
        if (file.isDirectory) return BrokerResponse.error(BrokerResponse.NOT_A_FILE)
        if (file.length() > maxTextBytes) return BrokerResponse.error(BrokerResponse.TOO_LARGE)
        // Re-check right before opening: the path may have been swapped since the first check.
        val again = try {
            policy.assertInside(file.path)
        } catch (e: WorkspacePathError) {
            return BrokerResponse.error(e.code)
        }
        if (again != file.path) return BrokerResponse.error(WorkspacePathError.OUTSIDE)
        return try {
            BrokerResponse.text(String(File(again).readBytes(), StandardCharsets.UTF_8))
        } catch (e: IOException) {
            BrokerResponse.error(BrokerResponse.IO_ERROR)
        }
    }

    fun writeFile(path: String, text: String?): BrokerResponse {
        if (text == null) return BrokerResponse.error(WorkspacePathError.INVALID)
        if (text.toByteArray(StandardCharsets.UTF_8).size > maxTextBytes) {
            return BrokerResponse.error(BrokerResponse.TOO_LARGE)
        }
        val target = try {
            File(policy.resolve(path))
        } catch (e: WorkspacePathError) {
            return BrokerResponse.error(e.code)
        }
        if (target.isDirectory) return BrokerResponse.error(BrokerResponse.NOT_A_FILE)
        val parent = target.parentFile ?: return BrokerResponse.error(WorkspacePathError.OUTSIDE)
        if (!parent.exists()) {
            // Only create parents that are themselves inside the workspace.
            val parentResolved = try {
                policy.assertInside(parent.path)
            } catch (e: WorkspacePathError) {
                return BrokerResponse.error(e.code)
            }
            if (parentResolved != parent.path) return BrokerResponse.error(WorkspacePathError.OUTSIDE)
            if (!parent.mkdirs()) return BrokerResponse.error(BrokerResponse.IO_ERROR)
        }
        // Re-check after mkdir: the directory could have been replaced meanwhile.
        val again = try {
            policy.assertInside(target.path)
        } catch (e: WorkspacePathError) {
            return BrokerResponse.error(e.code)
        }
        if (again != target.path) return BrokerResponse.error(WorkspacePathError.OUTSIDE)
        return try {
            val out = File(again)
            out.writeText(text, StandardCharsets.UTF_8)
            out.setReadable(false, false)
            out.setWritable(false, false)
            out.setReadable(true, true)
            out.setWritable(true, true)
            BrokerResponse.text("")
        } catch (e: IOException) {
            BrokerResponse.error(BrokerResponse.IO_ERROR)
        }
    }

    fun listDir(path: String): BrokerResponse {
        val dir = try {
            File(policy.resolve(path))
        } catch (e: WorkspacePathError) {
            return BrokerResponse.error(e.code)
        }
        if (!dir.exists()) return BrokerResponse.error(BrokerResponse.NOT_FOUND)
        if (!dir.isDirectory) return BrokerResponse.error(BrokerResponse.NOT_A_DIRECTORY)
        val again = try {
            policy.assertInside(dir.path)
        } catch (e: WorkspacePathError) {
            return BrokerResponse.error(e.code)
        }
        if (again != dir.path) return BrokerResponse.error(WorkspacePathError.OUTSIDE)
        val entries = File(again).list()?.sorted() ?: return BrokerResponse.error(BrokerResponse.IO_ERROR)
        return BrokerResponse.listing(entries)
    }

    companion object {
        const val DEFAULT_MAX_TEXT_BYTES: Int = 1024 * 1024
    }
}
