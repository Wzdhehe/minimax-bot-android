// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.os.IBinder
import android.os.Process
import java.io.Closeable

/** Main-only adapter. isolatedUid must be obtained from a correlated private
 * Messenger handshake's sendingUid, never accepted from a Bundle/HTTP body.
 * linkToDeath proves peer lifetime, not its UID or runtime readiness.
 */
internal fun BotRuntimeCoordinator.Run.attachBinder(
    context: Context,
    peer: IBinder,
    isolatedUid: Int,
    connect: (BotConnectionGrants) -> BotLoopbackBridge.Connection,
) {
    check(Process.myUid() == context.applicationInfo.uid) { "bot_runtime_owner_only" }
    require(isolatedUid > 0 && isolatedUid != Process.myUid()) { "bot_runtime_peer_invalid" }
    attach(isolatedUid, { stopped ->
        val death = IBinder.DeathRecipient { stopped() }
        peer.linkToDeath(death, 0)
        Closeable { try { peer.unlinkToDeath(death, 0) } catch (_: Exception) { } }
    }, connect)
}
