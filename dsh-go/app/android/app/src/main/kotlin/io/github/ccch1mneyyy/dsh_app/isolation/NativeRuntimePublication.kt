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
    /** Main-only nonblocking advisory lease on an independently opened directory FD. */
    external fun tryDirectoryLease(directory: Int): Int
    external fun tryDatabaseLease(descriptor: Int): Int
    /** Main-only bounded cleanup, after acquiring the exclusive Bot directory lease. */
    external fun cleanupBotStages(directory: Int): Int
    /** Main-only owned file FD or negative errno, never parcelled to Agent. */
    external fun openRead(directory: Int, name: String): Int
    /** Main-only private child directory FD or negative errno; never parcelled. */
    external fun openChildDirectory(directory: Int, name: String, create: Boolean): Int
    /** Internal reserved-stage operations; no caller-provided flags or paths. */
    external fun openStage(directory: Int, name: String): Int
    external fun removeStage(directory: Int, name: String): Int
    override external fun remove(directory: Int, name: String): Int
    /** Main-only 32-lowercase-hex token and fixed db/wal/shm/journal slot.
     * Exclusive RW creation + file/directory sync; a failure may leave a file. */
    external fun createSnapshotStage(directory: Int, token: String, slot: Int): Int
    external fun openSnapshotStageRead(directory: Int, token: String, slot: Int): Int
    /** Remove only a private stage entry matching the held FD, then sync.
     * Failure after unlink is unknown. Never names/removes the published file. */
    external fun removeSnapshotStage(directory: Int, token: String, slot: Int, held: Int): Int
    /** Caller must first confirm worker retirement and close all writable
     * snapshot copies. RO FD + empty sidecars + identity checks here cannot
     * prove that lifecycle. 0=published, 1=exists, 2=unknown, 3=refused. */
    external fun publishSnapshot(directory: Int, token: String, target: String, readonly: Int, bytes: Long): Int
    /** Only a current owner's known publication, matched to its retained RO FD.
     * Nonzero after unlink can be unknown; transferred RO FDs are not revoked. */
    external fun removePublishedSnapshot(directory: Int, target: String, readonly: Int): Int
}
