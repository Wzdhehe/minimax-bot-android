// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.util.UUID

/** Trusted Main kernel implementation; the directory FD is borrowed, not owned.
 * The production launcher must bind the supported native primitive. No Agent can
 * supply this callback, directory selection or synchronization policy over IPC.
 */
internal fun interface RuntimePublicationKernel {
    fun publishNoReplace(directory: Int, source: String, target: String): Int
    /** Injected publication-only kernels must explicitly opt into replacement. */
    fun replace(directory: Int, source: String, target: String): Int = OsConstants.ENOSYS
}
internal enum class RuntimePublicationResult { PUBLISHED, ALREADY_EXISTS, OUTCOME_UNKNOWN }

/** Bounded complete-file commits in a trusted private directory. Exclusive
 * publication and replacement are distinct operations, never generic POSIX link
 * emulation. No directory capability leaves Main. Do not use replacement for
 * SQLite files with outstanding leases: those physical inodes must stay fixed.
 * Closing this owner does not revoke other descriptors or stop an Agent.
 */
internal class RuntimeAtomicPublisher private constructor(
    private val directory: RuntimePrivateDirectory,
    private val kernel: RuntimePublicationKernel,
    private val maxBytes: Int,
    private val syncDirectory: (FileDescriptor) -> Unit,
) : Closeable {
    @Synchronized fun publishIfAbsent(name: String, bytes: ByteArray): RuntimePublicationResult =
        commit(name, bytes, replace = false)

    /** Atomically create/replace a complete JSON/history file. An unknown result
     * must be reconciled by the caller; never automatically retry a new payload.
     */
    @Synchronized fun replace(name: String, bytes: ByteArray): RuntimePublicationResult =
        commit(name, bytes, replace = true)

    private fun commit(name: String, bytes: ByteArray, replace: Boolean): RuntimePublicationResult {
        directory.requireWorker()
        if (!RuntimePrivateDirectory.validName(name) || name.startsWith(STAGE_PREFIX) || bytes.size > maxBytes) {
            throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        }
        val snapshot = bytes.copyOf()
        val stage = STAGE_PREFIX + UUID.randomUUID() + ".tmp"
        val file = File(directory.file, stage)
        var kernelEntered = false
        var kernelReturned = false
        var published = false
        var stageCreated = false
        try {
            val fd = Os.open(file.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384 /* 0600 */)
            stageCreated = true
            try {
                var offset = 0
                while (offset < snapshot.size) {
                    val count = Os.write(fd, snapshot, offset, minOf(32 * 1024, snapshot.size - offset))
                    if (count <= 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                    offset += count
                }
                Os.fsync(fd)
            } finally { Os.close(fd) }
            val directoryFd = directory.descriptor.fd
            kernelEntered = true
            val result = if (replace) kernel.replace(directoryFd, stage, name)
                else kernel.publishNoReplace(directoryFd, stage, name)
            kernelReturned = true
            if (!replace && result == OsConstants.EEXIST) return RuntimePublicationResult.ALREADY_EXISTS
            if (result != 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            published = true
            syncDirectory(directory.descriptor.fileDescriptor)
            return RuntimePublicationResult.PUBLISHED
        } catch (_: Exception) {
            if (published || (kernelEntered && !kernelReturned)) return RuntimePublicationResult.OUTCOME_UNKNOWN
            throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } catch (_: LinkageError) {
            // A native boundary failure must not claim that no publication ran.
            if (kernelEntered) return RuntimePublicationResult.OUTCOME_UNKNOWN
            throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } finally {
            // The stage is private and was created by this operation. Cleanup
            // failure never changes a committed target or erases unknown outcome.
            if (stageCreated) try { Os.remove(file.path) } catch (_: Exception) { }
        }
    }
    @Synchronized override fun close() { directory.close() }
    override fun toString() = "RuntimeAtomicPublisher(REDACTED)"
    companion object {
        private const val STAGE_PREFIX = ".native-publish-"
        fun open(context: Context, relativeSegments: List<String>, kernel: RuntimePublicationKernel? = null,
                 maxBytes: Int = 1024 * 1024, syncDirectory: (FileDescriptor) -> Unit = { Os.fsync(it) }): RuntimeAtomicPublisher {
            RuntimePrivateDirectory.requireOwnerWorker(context)
            if (maxBytes !in 1..8 * 1024 * 1024) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            // Resolve/load JNI only after Main/worker admission, before creating
            // directories. An unsupported kernel has no permissive fallback.
            val implementation = try { kernel ?: NativeRuntimePublication }
                catch (_: LinkageError) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
                catch (_: Exception) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
            val directory = RuntimePrivateDirectory.open(context, relativeSegments)
            try { return RuntimeAtomicPublisher(directory, implementation, maxBytes, syncDirectory) }
            catch (error: Throwable) { directory.close(); throw error }
        }
    }
}
