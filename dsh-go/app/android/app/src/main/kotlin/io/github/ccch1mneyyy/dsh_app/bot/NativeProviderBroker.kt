// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.content.Context
import java.io.Closeable
import io.github.ccch1mneyyy.dsh_app.model.NativeModelBroker

/** Trusted main-process launcher owns this lifecycle. Nothing is registered on
 * a Flutter or Agent channel. The future launcher passes only session endpoint
 * and session token privately to the host, never provider credentials. */
internal class NativeProviderBroker(context: Context) : Closeable {
    private val store = BotProviderStore(context.applicationContext)
    private val broker = NativeModelBroker(resolveProvider = store::resolveForModelBroker)
    val session get() = broker.session
    override fun close() = broker.close()
    override fun toString() = "NativeProviderBroker(REDACTED)"
}
