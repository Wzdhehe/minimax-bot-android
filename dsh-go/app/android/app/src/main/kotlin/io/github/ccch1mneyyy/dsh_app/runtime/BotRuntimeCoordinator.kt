// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture

/** One Main service owns one coordinator for its multi-Bot runtime. This is
 * native lifecycle assembly, not a process launcher or a readiness protocol.
 * No method is exported to the guest or an arbitrary MethodChannel caller.
 */
internal class BotRuntimeCoordinator {
    private val lock = Any()
    private var current: Run? = null

    /** Reserve a fresh bridge/token. cleanup must promptly revoke this run's
     * model/file/process capabilities and return a future that settles only
     * after their drain/exit is verified. Failure blocks another begin.
     * Never treat a cancelled/unknown remote write as a verified rollback.
     */
    fun begin(cleanup: () -> CompletableFuture<Unit>): Run {
        val run = synchronized(lock) {
            if (current != null) throw IOException("bot_runtime_busy")
            Run(this, cleanup).also { current = it }
        }
        run.observeBridge()
        return run
    }

    private fun released(run: Run) = synchronized(lock) {
        if (current === run) current = null
    }

    override fun toString() = "BotRuntimeCoordinator(REDACTED)"

    class Run internal constructor(
        private val owner: BotRuntimeCoordinator,
        private val cleanup: () -> CompletableFuture<Unit>,
    ) : Closeable {
        enum class Phase { STARTING, READY, STOPPING, STOPPED, FAILED }

        private val lock = Any()
        private var state = Phase.STARTING
        private var grants: BotConnectionGrants? = null
        private var death: Closeable? = null
        private var connector: ((BotConnectionGrants) -> BotLoopbackBridge.Connection)? = null
        private var endpoint: BotRuntimeEndpoint.Lease? = null
        private val drained = CompletableFuture<Unit>()
        val token: String = RuntimeDescriptorSession.randomToken()
        private val bridge = BotLoopbackBridge.bind {
            val connection = synchronized(lock) {
                if (state != Phase.READY) throw IOException("bot_runtime_not_ready")
                checkNotNull(connector) to checkNotNull(grants)
            }
            connection.first(connection.second)
        }

        // Only trusted native code uses these to build its private descriptor.
        val port: Int get() = bridge.port
        val phase: Phase get() = synchronized(lock) { state }

        internal fun observeBridge() { bridge.whenClosed { close() } }

        /** uid is from the authenticated handshake. watchDeath registers on
         * that exact peer, returns its unregister handle and may call back
         * before returning. It must be bounded. Android callers use attachBinder.
         */
        fun attach(
            uid: Int,
            watchDeath: (() -> Unit) -> Closeable,
            connect: (BotConnectionGrants) -> BotLoopbackBridge.Connection,
        ) {
            val gate = synchronized(lock) {
                if (state != Phase.STARTING || grants != null) throw IOException("bot_runtime_peer_invalid")
                BotConnectionGrants(uid).also { grants = it }
            }
            var subscription: Closeable? = null
            try {
                subscription = watchDeath { close() }
                val installed = synchronized(lock) {
                    if (state != Phase.STARTING) false else {
                        death = subscription
                        connector = connect
                        true
                    }
                }
                if (!installed) {
                    throw IOException("bot_runtime_closed")
                }
            } catch (_: Exception) {
                gate.close()
                quietly { subscription?.close() }
                close()
                throw IOException("bot_runtime_peer_unavailable")
            }
        }

        /** Called ONLY after a genuine runtime-ready receipt on this run's
         * private channel. Binding a port, writing a descriptor or observing
         * Binder identity alone is insufficient. This class cannot attest
         * that receipt; the production launcher must supply that proof.
         */
        fun ready(): Boolean {
            try {
                return synchronized(lock) {
                    if (state == Phase.READY) return@synchronized true
                    if (state != Phase.STARTING || death == null || connector == null) return@synchronized false
                    bridge.activate()
                    endpoint = BotRuntimeEndpoint.publish("http://127.0.0.1:$port", token)
                    state = Phase.READY
                    true
                }
            } catch (_: Exception) {
                close()
                return false
            }
        }

        override fun close() {
            val retired = synchronized(lock) {
                if (state == Phase.STOPPING || state == Phase.STOPPED || state == Phase.FAILED) return
                state = Phase.STOPPING
                val values = Triple(endpoint, grants, death)
                endpoint = null; grants = null; death = null; connector = null
                values
            }
            retired.first?.close()
            retired.second?.close()
            bridge.close()
            quietly { retired.third?.close() }
            // No external cleanup callback is run while holding either lock.
            try {
                cleanup().whenComplete { _, error ->
                    if (error != null) failCleanup()
                    else {
                        synchronized(lock) { state = Phase.STOPPED }
                        owner.released(this)
                        drained.complete(Unit)
                    }
                }
            } catch (_: Exception) { failCleanup() }
        }

        /** A dependent view: cancelling a caller's wait must not cancel the
         * private drain or release the owner's slot early. No UI-thread waits. */
        fun closeAsync(): CompletableFuture<Unit> {
            close()
            val view = CompletableFuture<Unit>()
            drained.whenComplete { _, error ->
                if (error == null) view.complete(Unit) else view.completeExceptionally(error)
            }
            return view
        }

        private fun failCleanup() {
            synchronized(lock) { state = Phase.FAILED }
            // Keep the owner occupied: capability drain/exit is unverified.
            drained.completeExceptionally(IOException("bot_runtime_cleanup_unverified"))
        }

        override fun toString() = "BotRuntimeCoordinator.Run(REDACTED)"
        private inline fun quietly(action: () -> Unit) { try { action() } catch (_: Exception) { } }
    }
}
