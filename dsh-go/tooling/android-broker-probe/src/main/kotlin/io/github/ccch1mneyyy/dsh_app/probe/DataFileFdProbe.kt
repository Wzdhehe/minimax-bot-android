// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.probe

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor

/** Verification only. The owner explicitly grants one non-sensitive file FD.
 * No directory, filename resolver, native credentials or arbitrary code API. */
internal object DataFileFdProbe {
    fun perform(fd: FileDescriptor, operation: String) {
        check((Os.fstat(fd).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG)
        when (operation) {
            "read" -> {
                val expected = "marker-before".toByteArray()
                val bytes = ByteArray(expected.size)
                check(Os.pread(fd, bytes, 0, bytes.size, 0) == bytes.size && bytes.contentEquals(expected))
            }
            "write" -> {
                val bytes = "marker-after".toByteArray()
                check(Os.pwrite(fd, bytes, 0, bytes.size, 0) == bytes.size)
                Os.fsync(fd)
            }
            "truncate" -> Os.ftruncate(fd, 8192)
            "lock" -> ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(fd)).use { output ->
                val lock = output.channel.tryLock(0, 1, false) ?: error("file_lock_unavailable")
                lock.release()
            }
            "map" -> {
                val address = Os.mmap(0, 4096, OsConstants.PROT_READ or OsConstants.PROT_WRITE, OsConstants.MAP_SHARED, fd, 0)
                try { Os.msync(address, 4096, OsConstants.MS_SYNC) } finally { Os.munmap(address, 4096) }
            }
            else -> error("unsupported_probe_operation")
        }
    }
}
