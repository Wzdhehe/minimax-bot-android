// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.system.StructPollfd
import android.system.ErrnoException
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerCode
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure
import io.github.ccch1mneyyy.dsh_app.model.NativeBrokerSession
import io.github.ccch1mneyyy.dsh_app.model.requireNativeJsonBudget
import java.net.InetAddress
import java.net.URI

/** Main-only owner of bounded, single-request model byte channels. Only an
 * AF_UNIX socketpair endpoint crosses Binder; the app-owned TCP connection,
 * native capability and all provider credentials remain in the owner process.
 * API26 rejects the earlier direct TCP FD transfer; never loosen its policy.
 *
 * One nonblocking poll worker and 32KiB pending buffer per channel (at most 4).
 * Peer EOF/input, owner close, broker shutdown and deadline all cancel. No
 * request multiplexing, new request bytes, URL selection or credential getter.
 */
internal class NativeModelChannels(
    private val session: NativeBrokerSession,
    maxChannels: Int = 4,
    private val timeoutMs: Long = 10 * 60_000,
) : Closeable {
    init { require(maxChannels in 1..4 && timeoutMs in 1..10 * 60_000) }
    private val closed = AtomicBoolean()
    private val slots = Semaphore(maxChannels)
    private val active = ConcurrentHashMap.newKeySet<Bridge>()
    private val workers = ThreadPoolExecutor(maxChannels, maxChannels, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(maxChannels), { task -> Thread(task, "mbot-model-channel").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    /** Off the UI thread. Caller closes its returned copy after Binder send,
     * or on send failure. The receiver closes its copy on every exit path. */
    fun open(request: String): ParcelFileDescriptor {
        if (closed.get() || !slots.tryAcquire()) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
        var tcp: ParcelFileDescriptor? = null
        var pair: Array<ParcelFileDescriptor>? = null
        var bridge: Bridge? = null
        var adopted = false
        try {
            tcp = connectAuthenticatedModelRequest(session, request)
            pair = ParcelFileDescriptor.createSocketPair()
            // Set options while both endpoints still belong to the owner.
            // Isolated receivers can read this FD but may not set socket opts.
            Os.setsockoptTimeval(pair[1].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(60_000))
            bridge = Bridge(tcp, pair[0], timeoutMs)
            active.add(bridge)
            if (closed.get()) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
            val channel = bridge
            workers.execute {
                try { channel.pump() }
                catch (_: Exception) { /* Stable closure, no platform diagnostic text. */ }
                finally { channel.close(); active.remove(channel); slots.release() }
            }
            adopted = true
            return pair[1]
        } catch (error: Exception) {
            throw if (error is ModelBrokerFailure) error else ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
        } finally {
            if (!adopted) {
                bridge?.let { active.remove(it); it.close() }
                try { tcp?.close() } catch (_: Exception) { }
                pair?.forEach { try { it.close() } catch (_: Exception) { } }
                slots.release()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.forEach { it.close() }
        active.clear()
        workers.shutdownNow()
    }
    override fun toString() = "NativeModelChannels(REDACTED)"

    private class Bridge(
        private val tcp: ParcelFileDescriptor,
        private val peer: ParcelFileDescriptor,
        timeoutMs: Long,
    ) : Closeable {
        private val closed = AtomicBoolean()
        private val expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        init {
            for (descriptor in listOf(tcp, peer)) {
                val fd = descriptor.fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            }
        }
        fun pump() {
            val input = StructPollfd().apply { fd = tcp.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val output = StructPollfd().apply { fd = peer.fileDescriptor }
            val both = arrayOf(output, input)
            val onlyPeer = arrayOf(output)
            val buffer = ByteArray(32 * 1024)
            var offset = 0
            var length = 0
            while (!closed.get() && !Thread.currentThread().isInterrupted && System.nanoTime() < expires) {
                // Stop reading upstream while output is pending. Do not poll a
                // hung-up TCP fd then: HUP is always reported and would spin.
                output.events = (OsConstants.POLLIN or if (length > 0) OsConstants.POLLOUT else 0).toShort()
                input.revents = 0
                output.revents = 0
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(expires - System.nanoTime())
                try { Os.poll(if (length > 0) onlyPeer else both, minOf(1000L, remainingMs.coerceAtLeast(1)).toInt()) }
                catch (error: ErrnoException) { if (error.errno == OsConstants.EINTR) continue else return }
                if (closed.get()) return
                val peerEvents = output.revents.toInt()
                // Any input, shutdown or error is cancellation, never another
                // HTTP request forwarded into the authenticated connection.
                if (peerEvents and (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) return
                val tcpEvents = input.revents.toInt()
                if (tcpEvents and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) return
                if (length == 0 && tcpEvents and (OsConstants.POLLIN or OsConstants.POLLHUP) != 0) {
                    length = try { Os.read(tcp.fileDescriptor, buffer, 0, buffer.size) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue else return
                    }
                    offset = 0
                    if (length <= 0) return
                }
                if (length > 0) {
                    val written = try { Os.write(peer.fileDescriptor, buffer, offset, length) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue else return
                    }
                    if (written <= 0) return
                    offset += written
                    length -= written
                }
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            for (descriptor in listOf(tcp, peer)) {
                try { Os.shutdown(descriptor.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) { }
                try { descriptor.close() } catch (_: Exception) { }
            }
        }
    }
}

private fun connectAuthenticatedModelRequest(session: NativeBrokerSession, request: String): ParcelFileDescriptor {
    val bytes = request.toByteArray(Charsets.UTF_8)
    if (bytes.size !in 1..1024 * 1024) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
    requireNativeJsonBudget(request, ModelBrokerCode.INVALID_REQUEST)
    val endpoint = try { URI(session.baseUrl) }
        catch (_: Exception) { throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST) }
    if (endpoint.scheme != "http" || endpoint.host != "127.0.0.1" || endpoint.port !in 1..65535 ||
        endpoint.rawUserInfo != null || endpoint.rawQuery != null || endpoint.rawFragment != null ||
        !endpoint.rawPath.isNullOrEmpty() || !session.token.matches(Regex("[0-9a-f]{64}"))) {
        throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
    }
    val header = ("POST /v1/model/stream HTTP/1.1\r\nHost: 127.0.0.1:${endpoint.port}\r\n" +
        "Authorization: Bearer ${session.token}\r\nContent-Type: application/json\r\n" +
        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
    // Use raw close, not a Socket wrapper whose shutdown could affect a dup.
    // No input stream exists on this side, so response bytes cannot be consumed
    // before the descriptor is transferred to its single receiver.
    val socket = try { Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0) }
        catch (_: Exception) { throw ModelBrokerFailure(ModelBrokerCode.REQUEST_FAILED) }
    try {
        Os.setsockoptTimeval(socket, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(10_000))
        Os.setsockoptTimeval(socket, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(60_000))
        Os.connect(socket, InetAddress.getByName("127.0.0.1"), endpoint.port)
        for (part in listOf(header, bytes)) {
            var offset = 0
            while (offset < part.size) {
                val count = Os.write(socket, part, offset, part.size - offset)
                if (count <= 0) throw ModelBrokerFailure(ModelBrokerCode.REQUEST_FAILED)
                offset += count
            }
        }
        return ParcelFileDescriptor.dup(socket)
    } catch (_: Exception) {
        // Never propagate platform error text, request headers or capabilities.
        throw ModelBrokerFailure(ModelBrokerCode.REQUEST_FAILED)
    } finally {
        try { Os.close(socket) } catch (_: Exception) { }
    }
}
