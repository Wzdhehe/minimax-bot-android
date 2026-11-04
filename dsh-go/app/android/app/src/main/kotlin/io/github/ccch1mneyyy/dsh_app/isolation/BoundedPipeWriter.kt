// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.TimeUnit

/** Main response producer. An uncooperative receiver cannot hold a blocking
 * writer forever. Caller closes the FD and releases its resource slot.
 */
internal fun writeBoundedResponsePipe(channel: ParcelFileDescriptor, bytes: ByteArray, timeoutMs: Long,
                                      cancelled: () -> Boolean) {
    if (bytes.size > 1024 * 1024 || timeoutMs !in 100..10_000) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
    val fd = channel.fileDescriptor
    if ((Os.fstat(fd).st_mode and OsConstants.S_IFMT) != OsConstants.S_IFIFO) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
    Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLOUT.toShort() }
    var offset = 0
    while (offset < bytes.size) {
        if (cancelled()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        try {
            poll.revents = 0
            Os.poll(arrayOf(poll), minOf(100L, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)).toInt())
            if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            if (poll.revents.toInt() and OsConstants.POLLOUT == 0) continue
            val count = Os.write(fd, bytes, offset, minOf(32 * 1024, bytes.size - offset))
            if (count <= 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            offset += count
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EINTR && error.errno != OsConstants.EAGAIN) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        }
    }
}
