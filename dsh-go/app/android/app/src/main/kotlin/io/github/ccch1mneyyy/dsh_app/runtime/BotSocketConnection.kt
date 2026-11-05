// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Main-owned socketpair endpoint; never accepts a caller-selected FD/path.
 * The trusted grant callback must send an IPC duplicate to the intended
 * isolated UID and verify its acknowledgement, with a bounded wait/death
 * handler. Returning means accepted; throwing means the connection failed.
 * No provider credentials or HTTP request data enter the grant callback.
 */
internal class BotSocketConnection private constructor(
    private val channel: ParcelFileDescriptor,
) : BotLoopbackBridge.Connection {
    private val closed = AtomicBoolean()
    override val input = ParcelFileDescriptor.AutoCloseInputStream(channel)
    override val output = ParcelFileDescriptor.AutoCloseOutputStream(channel)

    override fun shutdownOutput() {
        if (closed.get()) throw IOException("bot_connection_closed")
        try { Os.shutdown(channel.fileDescriptor, OsConstants.SHUT_WR) }
        catch (_: Exception) { throw IOException("bot_connection_unavailable") }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing a descriptor alone need not interrupt an already-blocked
        // socket syscall. Shutdown wakes both directions before releasing it.
        try { Os.shutdown(channel.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) { }
        try { input.close() } catch (_: Exception) { }
        try { output.close() } catch (_: Exception) { }
        try { channel.close() } catch (_: Exception) { }
    }

    override fun toString() = "BotSocketConnection(REDACTED)"

    companion object {
        fun open(context: Context, grant: (ParcelFileDescriptor) -> Unit): BotSocketConnection {
            check(Process.myUid() == context.applicationInfo.uid) { "bot_connection_owner_only" }
            var pair: Array<ParcelFileDescriptor>? = null
            try {
                val channels = ParcelFileDescriptor.createSocketPair().also { pair = it }
                // A blocked peer must not occupy a bridge writer forever.
                // No receive timeout: a legitimate SSE connection can be idle.
                Os.setsockoptTimeval(channels[0].fileDescriptor, OsConstants.SOL_SOCKET,
                    OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(10_000))
                grant(channels[1])
                return BotSocketConnection(channels[0])
            } catch (_: Exception) {
                pair?.get(0)?.let {
                    try { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) { }
                    try { it.close() } catch (_: Exception) { }
                }
                throw IOException("bot_connection_unavailable")
            } finally {
                // The callback may only transfer a duplicate. Never leave a
                // Main-owned copy of the guest endpoint keeping EOF alive.
                try { pair?.get(1)?.close() } catch (_: Exception) { }
            }
        }
    }
}
