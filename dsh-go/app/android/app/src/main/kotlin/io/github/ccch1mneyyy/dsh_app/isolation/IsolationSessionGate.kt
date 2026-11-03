// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import java.security.MessageDigest

/** One isolated execution lease per broker service. Android glue must supply
 * Messenger's sendingUid, never a UID copied from an untrusted Bundle. This
 * pure policy does not prove how Android assigns or transports that identity. */
internal class IsolationSessionGate(private val ownerUid: Int) {
    private var agentUid: Int? = null
    private var session: String? = null

    @Synchronized
    fun register(callerUid: Int, uid: Int, nonce: String?): String {
        if (callerUid != ownerUid) return BrokerResponse.UNAUTHORIZED
        if (uid < 0 || uid == ownerUid || nonce == null || !nonce.matches(Regex("[0-9a-f]{64}"))) {
            return BrokerResponse.UNAUTHORIZED
        }
        if (session != null && (agentUid != uid || !same(session, nonce))) return BrokerResponse.BUSY
        agentUid = uid
        session = nonce
        return BrokerResponse.OK
    }

    @Synchronized
    fun permits(callerUid: Int, nonce: String?): Boolean =
        agentUid == callerUid && same(session, nonce)

    @Synchronized
    fun revoke(callerUid: Int, nonce: String?): Boolean {
        if (callerUid != ownerUid || !same(session, nonce)) return false
        agentUid = null
        session = null
        return true
    }

    private fun same(left: String?, right: String?): Boolean = left != null && right != null &&
        MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}

internal fun validIsolationRequestId(id: String?): Boolean =
    id != null && id.matches(Regex("[A-Za-z0-9_-]{1,128}"))

/** File payloads use bounded Bundles until an FD transport is implemented. */
internal fun boundedIsolationResponse(response: BrokerResponse): BrokerResponse {
    if ((response.text?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 256 * 1024 ||
        (response.entries?.size ?: 0) > WorkspaceBroker.MAX_DIRECTORY_ENTRIES ||
        (response.entries?.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 8 } ?: 0) > WorkspaceBroker.MAX_DIRECTORY_BYTES) {
        return BrokerResponse.error(BrokerResponse.TOO_LARGE)
    }
    return response
}
