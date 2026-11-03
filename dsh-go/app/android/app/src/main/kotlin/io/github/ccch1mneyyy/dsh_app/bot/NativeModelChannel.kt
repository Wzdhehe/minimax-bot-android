// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerCode
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure
import io.github.ccch1mneyyy.dsh_app.model.NativeBrokerSession
import io.github.ccch1mneyyy.dsh_app.model.requireNativeJsonBudget
import java.net.InetAddress
import java.net.URI

/** Trusted owner creates one pre-authenticated, single-request byte channel.
 * Only its connected FD may cross into an isolated UID; endpoint, native token,
 * provider configuration and Key stay here. The peer receives HTTP/SSE bytes,
 * not an ability to open/authenticate other native broker connections.
 *
 * A receiver closes its FD to cancel. The native broker watches peer EOF even
 * while the provider is stalled. Extra peer input also cancels: no pipelining.
 * Binder transfer/SELinux/Node integration require separate platform evidence.
 */
internal fun openNativeModelChannel(session: NativeBrokerSession, request: String): ParcelFileDescriptor {
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
