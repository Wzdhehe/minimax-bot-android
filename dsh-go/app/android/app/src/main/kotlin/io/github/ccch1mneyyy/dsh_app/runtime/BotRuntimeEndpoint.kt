// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.Closeable

/**
 * The live endpoint of the on-device Bot service.
 *
 * The host picks the loopback port and the session token when it writes the
 * runtime descriptor, so it already knows the service it started. Only a
 * running service is published: a stale endpoint would let the UI address a
 * service that no longer exists with a token nobody revoked.
 * Readiness and death detection belong to the trusted launcher; this holder
 * neither probes a port nor revokes descriptor copies already sent to Dart.
 *
 * This carries the Bot service credential only. The native model broker
 * session stays in native code: it grants model access and is never
 * published to Flutter, to an Agent or to a file the guest can read.
 */
internal object BotRuntimeEndpoint {
    private val lock = Any()

    private var live: Live? = null

    /** Publish only after the host bridge is ready. The trusted launcher owns
     * this credential-free lease; an older run cannot clear a newer run. */
    fun publish(baseUrl: String, token: String): Lease {
        val port = baseUrl.removePrefix("http://127.0.0.1:").toIntOrNull()
        require(port != null && port in 1..65535 && baseUrl == "http://127.0.0.1:$port") {
            "a loopback base url is required"
        }
        require(token.matches(Regex("[0-9a-f]{32,64}"))) { "a session token is required" }
        val owner = Any()
        val lease = Lease(owner)
        synchronized(lock) { live = Live(baseUrl, token, owner) }
        return lease
    }

    class Lease internal constructor(private val owner: Any) : Closeable {
        override fun close() {
            synchronized(lock) { if (live?.owner === owner) live = null }
        }
        override fun toString() = "BotRuntimeEndpoint.Lease(REDACTED)"
    }

    /** The live service, or null when no Bot service is running. */
    fun descriptor(): Map<String, String>? = synchronized(lock) {
        live?.let { mapOf("baseUrl" to it.baseUrl, "token" to it.token) }
    }

    private class Live(val baseUrl: String, val token: String, val owner: Any)
}
