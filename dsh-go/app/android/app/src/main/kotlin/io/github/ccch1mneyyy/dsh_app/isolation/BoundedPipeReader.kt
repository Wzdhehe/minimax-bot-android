// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.util.concurrent.TimeUnit

/** Read one admitted request, requiring exactly length bytes followed by EOF.
 * Caller owns/closes the descriptor. Never accept a regular file/socket as a
 * request body. Limits must come from trusted native policy, not the request.
 */
internal fun readBoundedRequestPipe(
    channel: ParcelFileDescriptor, length: Int, maxBytes: Int,
    timeoutMs: Long, cancelled: () -> Boolean,
): ByteArray {
    if (maxBytes !in 1..8 * 1024 * 1024 || length !in 0..maxBytes || timeoutMs !in 100..10_000) {
        throw RuntimeDataFailure(RuntimeDataCode.INVALID)
    }
    try {
        val fd = channel.fileDescriptor
        if ((Os.fstat(fd).st_mode and OsConstants.S_IFMT) != OsConstants.S_IFIFO) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        if (flags and OsConstants.O_NONBLOCK == 0) Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        val bytes = ByteArray(length)
        val extra = ByteArray(1)
        var offset = 0
        while (true) {
            if (cancelled()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            try {
                poll.revents = 0
                Os.poll(arrayOf(poll), minOf(100L, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)).toInt())
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
                if (poll.revents.toInt() and (OsConstants.POLLIN or OsConstants.POLLHUP) == 0) continue
                val count = if (offset == length) Os.read(fd, extra, 0, 1) else Os.read(fd, bytes, offset, length - offset)
                if (count == 0) {
                    if (offset != length) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
                    return bytes
                }
                if (offset == length) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
                offset += count
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EINTR && error.errno != OsConstants.EAGAIN) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
        }
    } catch (error: Exception) {
        throw if (error is RuntimeDataFailure) error else RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
    }
}
