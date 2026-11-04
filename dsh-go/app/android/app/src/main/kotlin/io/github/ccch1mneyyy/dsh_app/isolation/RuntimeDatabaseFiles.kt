// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException

internal enum class RuntimeDataCode(val wire: String) {
    OWNER_ONLY("runtime_data_owner_only"), WORKER_REQUIRED("runtime_data_worker_required"),
    UNAVAILABLE("runtime_data_unavailable"), CLOSED("runtime_data_closed"),
    INVALID("runtime_data_invalid"),
    DENIED("runtime_data_denied"),
}

internal class RuntimeDataFailure(val code: RuntimeDataCode) : IOException(code.wire) {
    override fun toString() = "RuntimeDataFailure(${code.wire})"
}

/** Trusted Main file ownership, not a complete runtime lease or revocation API.
 * Only the pinned v2 database and its three fixed sidecars can be opened. No
 * caller pathname, directory FD, credential or shell surface is exposed.
 * The launcher must stop the isolated execution unit before retiring ownership;
 * closing these copies alone cannot revoke a descriptor already transferred.
 */
internal class RuntimeDatabaseFiles private constructor(
    internal val physicalFiles: List<File>,
    private val descriptors: List<ParcelFileDescriptor>,
) : Closeable {
    private var closed = false

    /** Caller owns these duplicates. No physical path or directory is returned. */
    @Synchronized fun duplicateForTransfer(): List<ParcelFileDescriptor> {
        if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
        val result = mutableListOf<ParcelFileDescriptor>()
        var transferred = false
        try {
            descriptors.forEach { result.add(ParcelFileDescriptor.dup(it.fileDescriptor)) }
            val duplicates = result.toList()
            transferred = true
            return duplicates
        } catch (_: Exception) {
            throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } finally {
            if (!transferred) result.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failed = false
        descriptors.forEach { try { it.close() } catch (_: Exception) { failed = true } }
        // Never delete, truncate or rename persistent files during close.
        if (failed) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
    }

    override fun toString() = "RuntimeDatabaseFiles(REDACTED)"

    companion object {
        private val names = listOf("runtime-state.sqlite", "runtime-state.sqlite-wal", "runtime-state.sqlite-shm", "runtime-state.sqlite-journal")
        private const val PRIVATE_FILE = 384 // 0600

        fun open(context: Context): RuntimeDatabaseFiles {
            RuntimePrivateDirectory.requireOwnerWorker(context)
            val descriptors = mutableListOf<ParcelFileDescriptor>()
            var transferred = false
            try {
                val files = RuntimePrivateDirectory.open(context, listOf("v2", "sqlite")).use { directory ->
                    val paths = names.map { File(directory.file, it) }
                    val inodes = mutableSetOf<Pair<Long, Long>>()
                    for (file in paths) {
                        val fd = Os.open(file.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_CLOEXEC or
                            OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, PRIVATE_FILE)
                        try {
                            val info = Os.fstat(fd)
                            if ((info.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG || info.st_uid != Process.myUid() ||
                                info.st_nlink != 1L || (info.st_mode and 63) != 0 || !inodes.add(info.st_dev to info.st_ino)) {
                                throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                            }
                            Os.fsync(fd)
                            descriptors.add(ParcelFileDescriptor.dup(fd))
                        } finally { Os.close(fd) }
                    }
                    directory.sync()
                    paths
                }
                val result = RuntimeDatabaseFiles(files, descriptors.toList())
                transferred = true
                return result
            } catch (_: Exception) {
                // Never propagate platform paths, causes or contents to logs.
                throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            } finally {
                if (!transferred) descriptors.forEach { try { it.close() } catch (_: Exception) { } }
            }
        }

    }
}
