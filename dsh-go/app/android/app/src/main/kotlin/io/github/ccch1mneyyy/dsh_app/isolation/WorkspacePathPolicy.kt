// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import java.io.File
import java.io.IOException

/**
 * Workspace path guard, pure Kotlin/JVM (no Android APIs, unit-testable on the host JVM).
 *
 * Semantics mirror the Node guard in `packages/mbot-workspace-guard/src/index.js` so the
 * broker and the host runtime agree on what "inside the workspace" means:
 *  - the user path is workspace-relative: empty/blank, NUL, absolute paths, `..` segments,
 *    backslashes and drive-letter prefixes are refused before any filesystem access;
 *  - the joined path is canonicalized (symlinks resolved) and must still be inside the
 *    canonical workspace root, separator-aware, so `workspace` and `workspace-evil`
 *    are not confused;
 *  - a symlink that points out of the workspace is refused.
 *
 * This is a path fence, not a sandbox: it does not isolate UIDs, does not defend against a
 * TOCTOU replacement between the check and the open (the broker re-checks right before
 * opening), and says nothing about network or credential reach.
 */
class WorkspacePathError(val code: String, message: String) : Exception(message) {
    companion object {
        const val INVALID = "INVALID"
        const val OUTSIDE = "OUTSIDE"
        const val UNRESOLVABLE = "UNRESOLVABLE"
        const val ROOT = "ROOT"
    }
}

/** A workspace root plus the strict Agent-facing resolution entry point. */
class WorkspacePathPolicy private constructor(private val rootReal: File) {

    /** Canonical workspace root. */
    val root: String get() = rootReal.path

    /**
     * Resolve a workspace-relative user path to a canonical absolute path inside the root.
     *
     * @throws WorkspacePathError with code INVALID / OUTSIDE / UNRESOLVABLE.
     */
    fun resolve(userPath: String): String {
        val relative = strictRelative(userPath)
        val joined = File(rootReal, relative)
        val canonical = try {
            joined.canonicalFile
        } catch (e: IOException) {
            throw WorkspacePathError(WorkspacePathError.UNRESOLVABLE, "cannot resolve path")
        }
        if (!isInside(rootReal, canonical)) {
            throw WorkspacePathError(WorkspacePathError.OUTSIDE, "path outside workspace")
        }
        return canonical.path
    }

    /**
     * Internal entry point for re-checks: an already resolved absolute path must still be
     * inside the workspace root. The Agent-facing [resolve] stays strict (relative only), so
     * internal canonical paths are never accepted from an Agent request.
     *
     * @throws WorkspacePathError with code OUTSIDE / UNRESOLVABLE.
     */
    fun assertInside(absolutePath: String): String {
        val canonical = try {
            File(absolutePath).canonicalFile
        } catch (e: IOException) {
            throw WorkspacePathError(WorkspacePathError.UNRESOLVABLE, "cannot resolve path")
        }
        if (!isInside(rootReal, canonical)) {
            throw WorkspacePathError(WorkspacePathError.OUTSIDE, "path outside workspace")
        }
        return canonical.path
    }

    companion object {
        /** The NUL character, spelled without a source-level escape. */
        private val NUL: Char = 0.toChar()

        /**
         * Open a guard on an existing workspace directory.
         *
         * @throws WorkspacePathError with code ROOT when the root is missing or not a directory.
         */
        fun open(workspaceRoot: File): WorkspacePathPolicy {
            if (!workspaceRoot.exists()) {
                throw WorkspacePathError(WorkspacePathError.ROOT, "workspace root does not exist")
            }
            val canonical = try {
                workspaceRoot.canonicalFile
            } catch (e: IOException) {
                throw WorkspacePathError(WorkspacePathError.ROOT, "workspace root is not accessible")
            }
            if (!canonical.isDirectory) {
                throw WorkspacePathError(WorkspacePathError.ROOT, "workspace root is not a directory")
            }
            return WorkspacePathPolicy(canonical)
        }

        /**
         * Strict user-path rules, applied before any filesystem access.
         * Internal canonical paths must not be submitted as user paths.
         */
        fun strictRelative(userPath: String): String {
            if (userPath.isBlank()) {
                throw WorkspacePathError(WorkspacePathError.INVALID, "path must be a non-empty string")
            }
            if (userPath.indexOf(NUL) >= 0) {
                throw WorkspacePathError(WorkspacePathError.INVALID, "path contains NUL")
            }
            if (userPath.startsWith("/")) {
                throw WorkspacePathError(WorkspacePathError.INVALID, "path must be workspace-relative")
            }
            if (userPath.contains('\\')) {
                throw WorkspacePathError(WorkspacePathError.INVALID, "path must not contain backslashes")
            }
            if (userPath.length >= 2 && userPath[1] == ':' && userPath[0].isLetter()) {
                throw WorkspacePathError(WorkspacePathError.INVALID, "path must not contain a drive letter")
            }
            if (userPath.split('/').any { it == ".." }) {
                throw WorkspacePathError(WorkspacePathError.INVALID, "path must not contain '..' segments")
            }
            return userPath
        }

        /**
         * Separator-aware containment: `workspace-evil` is not inside `workspace`.
         */
        fun isInside(root: File, candidate: File): Boolean {
            val rootPath = root.path
            val candidatePath = candidate.path
            if (candidatePath == rootPath) return true
            if (!candidatePath.startsWith(rootPath)) return false
            return candidatePath.length > rootPath.length &&
                candidatePath[rootPath.length] == File.separatorChar
        }
    }
}
