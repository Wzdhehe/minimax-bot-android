// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import java.io.File

/**
 * Main-process workspace broker.
 *
 * The isolated Agent process (`AgentIsolationService`) has its own UID, no app permissions,
 * no network and no access to `filesDir`, so this service is the only path from the Agent
 * side to the workspace. It answers path-guarded file requests and nothing else: no exec,
 * no shell, and no credential operation exists in [BrokerOp], so an API key can never be
 * handed to the isolated side through this channel.
 */
class AgentBrokerService : Service() {

    private var broker: WorkspaceBroker? = null
    private lateinit var messenger: Messenger
    private var thread: HandlerThread? = null

    override fun onCreate() {
        super.onCreate()
        val workspace = File(filesDir, "workspace")
        if (!workspace.exists()) workspace.mkdirs()
        broker = try {
            WorkspaceBroker(WorkspacePathPolicy.open(workspace))
        } catch (e: WorkspacePathError) {
            null
        }
        val worker = HandlerThread("mbot-agent-broker").apply { start() }
        thread = worker
        messenger = Messenger(Handler(worker.looper, Handler.Callback { message -> handle(message) }))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread?.quitSafely()
        thread = null
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        if (message.what != IsolationWire.MSG_BROKER_REQUEST) return false
        val data = message.data
        val request = BrokerCodec.decodeRequest(data)
        val response = when {
            request == null -> BrokerResponse.error(BrokerResponse.UNSUPPORTED_OP)
            broker == null -> BrokerResponse.error(BrokerResponse.BROKER_UNAVAILABLE)
            else -> broker!!.handle(request)
        }
        val reply = Message.obtain(null, IsolationWire.MSG_BROKER_RESPONSE)
        reply.data = BrokerCodec.encodeResponse(response)
        reply.replyTo = message.replyTo
        message.replyTo?.send(reply)
        return true
    }
}
