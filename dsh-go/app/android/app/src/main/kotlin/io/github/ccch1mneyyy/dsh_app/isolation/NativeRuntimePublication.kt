// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

/** Main selects and owns the directory. Loading this object or knowing an FD
 * number grants no access to another process's descriptor table. Not an IPC API.
 * Trusted Main directory/file components invoke it after owner/worker checks.
 */
internal object NativeRuntimePublication : RuntimePublicationKernel {
    init { System.loadLibrary("mbot_runtime_files") }
    override external fun publishNoReplace(directory: Int, source: String, target: String): Int
    override external fun replace(directory: Int, source: String, target: String): Int
    /** Main-only owned file FD or negative errno, never parcelled to Agent. */
    external fun openRead(directory: Int, name: String): Int
    /** Main-only private child directory FD or negative errno; never parcelled. */
    external fun openChildDirectory(directory: Int, name: String, create: Boolean): Int
    /** Internal reserved-stage operations; no caller-provided flags or paths. */
    external fun openStage(directory: Int, name: String): Int
    external fun removeStage(directory: Int, name: String): Int
    override external fun remove(directory: Int, name: String): Int
}
