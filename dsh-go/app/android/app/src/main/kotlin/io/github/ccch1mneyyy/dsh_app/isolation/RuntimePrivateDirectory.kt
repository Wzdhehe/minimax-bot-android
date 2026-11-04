// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File

/** Main-only directory capability. Never parcel/transfer its descriptor to an
 * Agent. Relative segments are selected by trusted Main policy, not an IPC root.
 */
internal class RuntimePrivateDirectory private constructor(
    internal val file: File,
    internal val descriptor: ParcelFileDescriptor,
    private val ownerUid: Int,
    private val depth: Int,
) : Closeable {
    @Volatile private var closed = false
    fun requireWorker() {
        if (Process.myUid() != ownerUid) throw RuntimeDataFailure(RuntimeDataCode.OWNER_ONLY)
        if (Looper.myLooper() == Looper.getMainLooper()) throw RuntimeDataFailure(RuntimeDataCode.WORKER_REQUIRED)
        if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
    }
    @Synchronized fun sync() { requireWorker(); Os.fsync(descriptor.fileDescriptor) }
    /** Borrow while open; close cannot recycle this FD during a native openat. */
    @Synchronized fun <T> withDescriptor(block: (Int) -> T): T {
        requireWorker()
        return block(descriptor.fd)
    }
    /** Trusted Main child-cap walk, not a request to grant Agent directory FDs.
     * A create failure can leave a private directory; do not infer its absence.
     * The stored File is informational; nested IO must use this held capability.
     */
    @Synchronized fun openChild(name: String, create: Boolean = false): RuntimePrivateDirectory {
        requireWorker()
        if (depth >= 8 || !validName(name) || name.startsWith(".native-publish-")) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        var child: ParcelFileDescriptor? = null
        try {
            val raw = NativeRuntimePublication.openChildDirectory(descriptor.fd, name, create)
            if (raw < 0) throw ErrnoException("runtime_child", -raw)
            child = ParcelFileDescriptor.adoptFd(raw)
            val result = RuntimePrivateDirectory(File(file, name), child, ownerUid, depth + 1)
            child = null
            return result
        } catch (error: Exception) {
            val code = if (error is ErrnoException) when (error.errno) {
                OsConstants.ENOENT -> RuntimeDataCode.NOT_FOUND
                OsConstants.ELOOP, OsConstants.ENOTDIR, OsConstants.EACCES, OsConstants.EPERM -> RuntimeDataCode.DENIED
                OsConstants.EINVAL -> RuntimeDataCode.INVALID
                else -> RuntimeDataCode.UNAVAILABLE
            } else RuntimeDataCode.UNAVAILABLE
            throw RuntimeDataFailure(code)
        } catch (_: LinkageError) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
        finally { try { child?.close() } catch (_: Exception) { } }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { descriptor.close() } catch (_: Exception) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
    }
    override fun toString() = "RuntimePrivateDirectory(REDACTED)"

    companion object {
        fun requireOwnerWorker(context: Context) {
            if (Process.myUid() != context.applicationInfo.uid) throw RuntimeDataFailure(RuntimeDataCode.OWNER_ONLY)
            if (Looper.myLooper() == Looper.getMainLooper()) throw RuntimeDataFailure(RuntimeDataCode.WORKER_REQUIRED)
        }
        fun open(context: Context, relativeSegments: List<String>): RuntimePrivateDirectory {
            requireOwnerWorker(context)
            val segments = relativeSegments.toList()
            if (segments.size > 8 || segments.any { !validName(it) }) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            try {
                var directory = context.noBackupFilesDir
                requireDirectory(directory, privateMode = false)
                for (name in listOf("minimax-bot-runtime") + segments) {
                    val child = File(directory, name)
                    try { Os.mkdir(child.path, 448 /* 0700 */) }
                    catch (error: ErrnoException) { if (error.errno != OsConstants.EEXIST) throw error }
                    requireDirectory(child, privateMode = true)
                    val parent = openDirectory(directory)
                    try { Os.fsync(parent) } finally { Os.close(parent) }
                    directory = child
                }
                val fd = openDirectory(directory)
                var descriptor: ParcelFileDescriptor? = null
                var transferred = false
                try {
                    // Do not transfer the duplicate until closing the original
                    // has succeeded; otherwise a failing close leaks ownership.
                    try { descriptor = ParcelFileDescriptor.dup(fd) }
                    finally { Os.close(fd) }
                    val result = RuntimePrivateDirectory(directory, descriptor!!, Process.myUid(), segments.size)
                    transferred = true
                    return result
                } finally {
                    if (!transferred) try { descriptor?.close() } catch (_: Exception) { }
                }
            } catch (_: Exception) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
        }
        fun validName(name: String): Boolean = name != "." && name != ".." && name.matches(Regex("[A-Za-z0-9._-]{1,128}"))
        private fun requireDirectory(directory: File, privateMode: Boolean) {
            val info = Os.lstat(directory.path)
            if ((info.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFDIR || info.st_uid != Process.myUid() ||
                (privateMode && (info.st_mode and 63) != 0)) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        }
        private fun openDirectory(directory: File): java.io.FileDescriptor {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                val info = Os.fstat(fd)
                if ((info.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFDIR || info.st_uid != Process.myUid()) {
                    throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                }
                return fd
            } catch (error: Throwable) { Os.close(fd); throw error }
        }
    }
}
