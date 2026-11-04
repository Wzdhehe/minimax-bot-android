// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.Process
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor

/** Main-only committed file snapshots. A concurrent rename may leave this FD
 * on the previous (now unlinked) inode; that complete version remains readable.
 * No path, inode, directory or stored file descriptor is returned to an Agent.
 */
internal fun readRuntimeFileSnapshot(directory: RuntimePrivateDirectory, name: String, maxBytes: Int,
                                     cancelled: () -> Boolean = { false }): ByteArray {
    if (maxBytes !in 1..8 * 1024 * 1024) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
    return withRuntimeSnapshotFile(directory, name) { fd, info ->
        if (info.st_size > maxBytes) throw RuntimeDataFailure(RuntimeDataCode.TOO_LARGE)
        val output = ByteArrayOutputStream(minOf(info.st_size.toInt(), 32 * 1024))
        val buffer = ByteArray(minOf(maxBytes + 1, 32 * 1024))
        while (true) {
            if (cancelled()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            val count = try { Os.read(fd, buffer, 0, minOf(buffer.size, maxBytes - output.size() + 1)) }
                catch (error: ErrnoException) { if (error.errno == OsConstants.EINTR) continue else throw error }
            if (count == 0) break
            if (output.size() + count > maxBytes) throw RuntimeDataFailure(RuntimeDataCode.TOO_LARGE)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

/** v1 regular-file metadata only, not a general stat/realpath interface. */
internal fun runtimeFileSize(directory: RuntimePrivateDirectory, name: String): Long =
    withRuntimeSnapshotFile(directory, name) { _, info -> info.st_size }

private fun <T> withRuntimeSnapshotFile(directory: RuntimePrivateDirectory, name: String,
                                      block: (FileDescriptor, StructStat) -> T): T {
    directory.requireWorker()
    if (!RuntimeFileCommitWire.validTarget(name)) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
    var owner: ParcelFileDescriptor? = null
    try {
        val retained = directory.withDescriptor { fd ->
            val raw = NativeRuntimePublication.openRead(fd, name)
            if (raw < 0) throw ErrnoException("runtime_open", -raw)
            ParcelFileDescriptor.adoptFd(raw)
        }
        owner = retained
        val opened = retained.fileDescriptor
        val info = Os.fstat(opened)
        if ((info.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG || info.st_uid != Process.myUid() ||
            info.st_nlink !in 0L..1L || (info.st_mode and 63) != 0 || info.st_size < 0) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
        return block(opened, info)
    } catch (error: Exception) {
        if (error is RuntimeDataFailure) throw error
        val code = if (error is ErrnoException) when (error.errno) {
            OsConstants.ENOENT -> RuntimeDataCode.NOT_FOUND
            OsConstants.ELOOP, OsConstants.EACCES, OsConstants.EPERM, OsConstants.ENOTDIR -> RuntimeDataCode.DENIED
            else -> RuntimeDataCode.UNAVAILABLE
        } else RuntimeDataCode.UNAVAILABLE
        throw RuntimeDataFailure(code)
    } catch (_: LinkageError) {
        throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
    } finally {
        try { owner?.close() } catch (_: Exception) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
    }
}
