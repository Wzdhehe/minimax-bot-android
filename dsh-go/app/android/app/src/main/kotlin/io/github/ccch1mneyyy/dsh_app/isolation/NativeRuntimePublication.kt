// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

/** Main selects and owns the directory. Loading this object or knowing an FD
 * number grants no access to another process's descriptor table. Not an IPC API.
 * Only RuntimeAtomicPublisher invokes it in production, after owner/worker checks.
 */
internal object NativeRuntimePublication : RuntimePublicationKernel {
    init { System.loadLibrary("mbot_runtime_files") }
    override external fun publishNoReplace(directory: Int, source: String, target: String): Int
    override external fun replace(directory: Int, source: String, target: String): Int
    /** Main-only owned file FD or negative errno, never parcelled to Agent. */
    external fun openRead(directory: Int, name: String): Int
}
