// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One Main-owned bridge generation. The trusted connector must return an
 * independently owned connection already granted to the isolated Bot. There
 * is no caller-selected destination/descriptor API or provider Key resolver.
 * HTTP authentication remains in the real Bot; bytes are never parsed/logged.
 * Binding is NOT readiness: the launcher activates only after guest handshake.
 */
internal class BotLoopbackBridge private constructor(
    private val listener: ServerSocket,
    private val capacity: Int,
    private val connect: () -> Connection,
) : Closeable {
    /** Own both directions. close must unblock stream IO; shutdownOutput must
     * preserve the input direction so a request EOF does not lose its reply. */
    interface Connection : Closeable {
        val input: InputStream
        val output: OutputStream
        fun shutdownOutput()
    }

    val port: Int = listener.localPort
    private val lock = Any()
    private var closed = false
    private var active = false
    private val sessions = mutableSetOf<Session>()
    private val closedSignal = CompletableFuture<Unit>()
    private val acceptor = Thread({ acceptLoop() }, "mbot-bridge-accept").apply { isDaemon = true; start() }

    fun activate() = synchronized(lock) {
        check(!closed) { "bot_bridge_closed" }
        active = true
    }

    /** Trusted lifecycle hook, including an already-closed listener. Handlers
     * must return promptly; no HTTP request data or credentials are passed. */
    fun whenClosed(action: () -> Unit) { closedSignal.thenRun { action() } }

    private fun acceptLoop() {
        try {
            while (true) {
                val socket = listener.accept()
                val session = synchronized(lock) {
                    if (closed || !active || sessions.size >= capacity) null
                    else Session(socket).also { sessions.add(it) }
                }
                if (session == null) quietly { socket.close() }
                else try { workers.execute { session.run() } } catch (_: Exception) { session.close() }
            }
        } catch (_: Exception) {
            // Unexpected listener failure retires the generation. No platform
            // exception, remote request or credential is written to diagnostics.
            close()
        }
    }

    private inner class Session(private val downstream: Socket) : Closeable {
        private val gate = Any()
        private var stopped = false
        private var upstream: Connection? = null
        private var completed = 0

        fun run() {
            try {
                val owned = connect()
                val accepted = synchronized(gate) {
                    if (stopped) false else { upstream = owned; true }
                }
                if (!accepted) { quietly { owned.close() }; return }
                workers.execute {
                    try { copy(owned.input, downstream.getOutputStream()) { downstream.shutdownOutput() } }
                    catch (_: Exception) { close() }
                }
                copy(downstream.getInputStream(), owned.output) { owned.shutdownOutput() }
            } catch (_: Exception) { close() }
        }

        private fun copy(input: InputStream, output: OutputStream, halfClose: () -> Unit) {
            try {
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    if (count > 0) { output.write(bytes, 0, count); output.flush() }
                }
                output.flush()
                halfClose()
                val finished = synchronized(gate) { ++completed == 2 }
                if (finished) close()
            } catch (_: Exception) { close() }
        }

        override fun close() {
            val owned = synchronized(gate) {
                if (stopped) return
                stopped = true
                upstream.also { upstream = null }
            }
            quietly { downstream.close() }
            quietly { owned?.close() }
            synchronized(lock) { sessions.remove(this) }
        }
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            active = false
            sessions.toList()
        }
        quietly { listener.close() }
        retired.forEach { it.close() }
        closedSignal.complete(Unit)
        // A connector blocked in foreign IPC may return later. Session.run
        // closes that late connection; close never waits on uncooperative IPC.
        // Workers are globally bounded across generations so repeated restart
        // cannot allocate an unbounded number of stuck connector threads.
    }

    override fun toString() = "BotLoopbackBridge(REDACTED)"

    companion object {
        private val workers = ThreadPoolExecutor(0, 32, 30, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(), { task -> Thread(task, "mbot-bridge-copy").apply { isDaemon = true } })
        fun bind(capacity: Int = 4, connect: () -> Connection): BotLoopbackBridge {
            require(capacity in 1..16) { "bot_bridge_capacity_invalid" }
            val listener = ServerSocket()
            try {
                listener.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), capacity)
                return BotLoopbackBridge(listener, capacity, connect)
            } catch (_: Exception) {
                quietly { listener.close() }
                throw java.io.IOException("bot_bridge_unavailable")
            }
        }
        private inline fun quietly(action: () -> Unit) { try { action() } catch (_: Exception) { } }
    }
}
