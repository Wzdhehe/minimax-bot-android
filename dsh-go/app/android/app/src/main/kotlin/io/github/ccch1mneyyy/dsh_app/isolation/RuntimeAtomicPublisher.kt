// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
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
    fun remove(directory: Int, name: String): Int = OsConstants.ENOSYS
}
internal enum class RuntimePublicationResult { PUBLISHED, ALREADY_EXISTS, OUTCOME_UNKNOWN }
internal enum class RuntimeDeletionResult { REMOVED, OUTCOME_UNKNOWN }

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
    private val mutationLock: RuntimeDirectoryMutationLock.Lease,
) : Closeable {
    @Synchronized fun publishIfAbsent(name: String, bytes: ByteArray): RuntimePublicationResult =
        mutationLock.withLock { commit(name, bytes, replace = false) }

    /** Atomically create/replace a complete JSON/history file. An unknown result
     * must be reconciled by the caller; never automatically retry a new payload.
     */
    @Synchronized fun replace(name: String, bytes: ByteArray): RuntimePublicationResult =
        mutationLock.withLock { commit(name, bytes, replace = true) }

    /** Same-directory regular-file move, not POSIX link or directory rename.
     * Main must separately grant source removal and optional target replacement.
     */
    @Synchronized fun move(source: String, target: String, replace: Boolean): RuntimePublicationResult =
        mutationLock.withLock { moveUncoordinated(source, target, replace) }

    private fun moveUncoordinated(source: String, target: String, replace: Boolean): RuntimePublicationResult {
        directory.requireWorker()
        if (!validTarget(source) || !validTarget(target) || source == target) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        runtimeFileSize(directory, source) // Openat/private regular-file admission, no root paths.
        var entered = false
        var returned = false
        var moved = false
        try {
            val fd = directory.descriptor.fd
            entered = true
            val result = if (replace) kernel.replace(fd, source, target) else kernel.publishNoReplace(fd, source, target)
            returned = true
            if (!replace && result == OsConstants.EEXIST) return RuntimePublicationResult.ALREADY_EXISTS
            if (result == OsConstants.ENOENT) throw RuntimeDataFailure(RuntimeDataCode.NOT_FOUND)
            if (result != 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            moved = true
            syncDirectory(directory.descriptor.fileDescriptor)
            return RuntimePublicationResult.PUBLISHED
        } catch (error: Exception) {
            if (moved || (entered && !returned)) return RuntimePublicationResult.OUTCOME_UNKNOWN
            throw if (error is RuntimeDataFailure) error else RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } catch (_: LinkageError) {
            if (entered) return RuntimePublicationResult.OUTCOME_UNKNOWN
            throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        }
    }

    /** Removes only a Main-owned private regular file. No directory recursion. */
    @Synchronized fun remove(name: String): RuntimeDeletionResult =
        mutationLock.withLock { removeUncoordinated(name) }

    private fun removeUncoordinated(name: String): RuntimeDeletionResult {
        directory.requireWorker()
        if (!validTarget(name)) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        var entered = false
        var returned = false
        var removed = false
        try {
            val fd = directory.descriptor.fd
            entered = true
            val result = kernel.remove(fd, name)
            returned = true
            if (result != 0) throw RuntimeDataFailure(when (result) {
                OsConstants.ENOENT -> RuntimeDataCode.NOT_FOUND
                OsConstants.EACCES, OsConstants.EPERM -> RuntimeDataCode.DENIED
                else -> RuntimeDataCode.UNAVAILABLE
            })
            removed = true
            syncDirectory(directory.descriptor.fileDescriptor)
            return RuntimeDeletionResult.REMOVED
        } catch (error: Exception) {
            if (removed || (entered && !returned)) return RuntimeDeletionResult.OUTCOME_UNKNOWN
            throw if (error is RuntimeDataFailure) error else RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } catch (_: LinkageError) {
            if (entered) return RuntimeDeletionResult.OUTCOME_UNKNOWN
            throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        }
    }

    /** Bounded whole-file append via a fresh inode, not an O_APPEND/FileHandle
     * API. Shared directory coordination prevents lost updates among publishers.
     * An unknown outcome must never be automatically retried (duplicate records).
     */
    @Synchronized fun append(name: String, bytes: ByteArray): RuntimePublicationResult = mutationLock.withLock {
        directory.requireWorker()
        if (!validTarget(name) || bytes.size > maxBytes) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        val suffix = bytes.copyOf()
        val before = try { readRuntimeFileSnapshot(directory, name, maxBytes) }
            catch (error: RuntimeDataFailure) {
                if (error.code != RuntimeDataCode.NOT_FOUND) throw error
                byteArrayOf()
            }
        if (suffix.size > maxBytes - before.size) throw RuntimeDataFailure(RuntimeDataCode.TOO_LARGE)
        commit(name, before + suffix, replace = true)
    }

    private fun commit(name: String, bytes: ByteArray, replace: Boolean): RuntimePublicationResult {
        directory.requireWorker()
        if (!validTarget(name) || bytes.size > maxBytes) {
            throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        }
        val snapshot = bytes.copyOf()
        val stage = STAGE_PREFIX + UUID.randomUUID() + ".tmp"
        var kernelEntered = false
        var kernelReturned = false
        var published = false
        var stageCreated = false
        try {
            val owner = directory.withDescriptor { parent ->
                val raw = NativeRuntimePublication.openStage(parent, stage)
                if (raw < 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                stageCreated = true
                ParcelFileDescriptor.adoptFd(raw)
            }
            owner.use {
                val fd = it.fileDescriptor
                var offset = 0
                while (offset < snapshot.size) {
                    val count = Os.write(fd, snapshot, offset, minOf(32 * 1024, snapshot.size - offset))
                    if (count <= 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                    offset += count
                }
                Os.fsync(fd)
            }
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
            if (stageCreated) try {
                directory.withDescriptor { NativeRuntimePublication.removeStage(it, stage) }
            } catch (_: Exception) { }
        }
    }
    @Synchronized override fun close() {
        try { directory.close() } finally { mutationLock.close() }
    }
    override fun toString() = "RuntimeAtomicPublisher(REDACTED)"
    companion object {
        private const val STAGE_PREFIX = ".native-publish-"
        private fun validTarget(name: String) = RuntimePrivateDirectory.validName(name) && !name.startsWith(STAGE_PREFIX)
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
            var mutationLock: RuntimeDirectoryMutationLock.Lease? = null
            try {
                mutationLock = RuntimeDirectoryMutationLock.acquire(directory)
                return RuntimeAtomicPublisher(directory, implementation, maxBytes, syncDirectory, mutationLock)
            } catch (error: Throwable) {
                try { directory.close() } finally { mutationLock?.close() }
                throw if (error is RuntimeDataFailure || error is Error) error else RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
        }

        /** Build a publisher on an already held Main directory capability.
         *
         * Ownership is copied: the publisher owns a duplicate descriptor, so
         * closing the publisher never invalidates the caller's capability and
         * closing the capability never breaks the publisher. The directory is
         * never re-resolved by path, so a renamed parent or a replacement
         * symlink cannot redirect these writes. Publishers derived from the same
         * directory share its mutation gate. */
        fun openForHeldDirectory(held: RuntimePrivateDirectory, kernel: RuntimePublicationKernel? = null,
                                 maxBytes: Int = 1024 * 1024,
                                 syncDirectory: (FileDescriptor) -> Unit = { Os.fsync(it) }): RuntimeAtomicPublisher {
            // Refuse before touching native code or copying a descriptor.
            held.requireWorker()
            if (maxBytes !in 1..8 * 1024 * 1024) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            // Resolve/load JNI only after Main/worker admission, before any
            // descriptor is copied. An unsupported kernel has no fallback.
            val implementation = try { kernel ?: NativeRuntimePublication }
                catch (_: LinkageError) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
                catch (_: Exception) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
            // Copy the capability: a publisher must never close a borrowed cap,
            // and a failed construction must leave the caller's cap open.
            val owned = held.duplicate()
            var mutationLock: RuntimeDirectoryMutationLock.Lease? = null
            try {
                mutationLock = RuntimeDirectoryMutationLock.acquire(owned)
                return RuntimeAtomicPublisher(owned, implementation, maxBytes, syncDirectory, mutationLock)
            } catch (error: Throwable) {
                try { owned.close() } finally { mutationLock?.close() }
                throw if (error is RuntimeDataFailure || error is Error) error else RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
        }
    }
}
