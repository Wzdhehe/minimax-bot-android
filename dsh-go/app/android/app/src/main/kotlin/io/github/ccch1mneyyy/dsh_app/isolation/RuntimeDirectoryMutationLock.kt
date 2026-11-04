// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.system.Os
import java.io.Closeable

/** Main-only coordination by the held directory inode, never a re-resolved path.
 * All publisher mutations in this process participate. This is not a filesystem
 * lock against unrelated native writers or another process, nor a SQLite lease.
 */
internal object RuntimeDirectoryMutationLock {
    private class Entry(val monitor: Any = Any(), var owners: Int = 0)
    private val entries = mutableMapOf<Pair<Long, Long>, Entry>()

    fun acquire(directory: RuntimePrivateDirectory): Lease {
        val identity = directory.withDescriptor {
            val info = Os.fstat(directory.descriptor.fileDescriptor)
            info.st_dev to info.st_ino
        }
        return synchronized(entries) {
            val entry = entries.getOrPut(identity) { Entry() }
            entry.owners++
            Lease(identity, entry.monitor)
        }
    }

    internal class Lease internal constructor(private val identity: Pair<Long, Long>, private val monitor: Any) : Closeable {
        private var closed = false
        fun <T> withLock(block: () -> T): T = synchronized(monitor) {
            if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            block()
        }
        override fun close() {
            synchronized(monitor) {
                if (closed) return
                closed = true
                synchronized(entries) {
                    val entry = entries[identity]
                    check(entry != null && entry.monitor === monitor && entry.owners > 0)
                    if (--entry.owners == 0) entries.remove(identity)
                }
            }
        }
        override fun toString() = "RuntimeDirectoryMutationLease(REDACTED)"
    }
}
