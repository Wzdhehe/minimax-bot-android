// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * The live endpoint of the on-device Bot service.
 *
 * The host picks the loopback port and the session token when it writes the
 * runtime descriptor, so it already knows the service it started. Only a
 * running service is published: a stale endpoint would let the UI address a
 * service that no longer exists with a token nobody revoked.
 *
 * This carries the Bot service credential only. The native model broker
 * session stays in native code: it grants model access and is never
 * published to Flutter, to an Agent or to a file the guest can read.
 */
internal object BotRuntimeEndpoint {
    private val lock = Any()

    @Volatile
    private var live: Live? = null

    /** Record the service the host just started. */
    fun publish(baseUrl: String, token: String) {
        val port = baseUrl.removePrefix("http://127.0.0.1:").toIntOrNull()
        require(port != null && port in 1..65535 && baseUrl == "http://127.0.0.1:$port") {
            "a loopback base url is required"
        }
        require(token.matches(Regex("[0-9a-f]{32,64}"))) { "a session token is required" }
        synchronized(lock) { live = Live(baseUrl, token) }
    }

    /** Forget the service: the UI must not keep a credential of a dead run. */
    fun clear() {
        synchronized(lock) { live = null }
    }

    /** The live service, or null when no Bot service is running. */
    fun descriptor(): Map<String, String>? = synchronized(lock) {
        live?.let { mapOf("baseUrl" to it.baseUrl, "token" to it.token) }
    }

    private class Live(val baseUrl: String, val token: String)
}
