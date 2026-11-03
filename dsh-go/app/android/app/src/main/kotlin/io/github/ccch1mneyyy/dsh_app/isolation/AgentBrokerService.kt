// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
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
    private lateinit var gate: IsolationSessionGate

    override fun onCreate() {
        super.onCreate()
        gate = IsolationSessionGate(Process.myUid())
        val workspace = File(filesDir, "workspace")
        if (!workspace.exists()) workspace.mkdirs()
        broker = try {
            WorkspaceBroker(WorkspacePathPolicy.open(workspace))
        } catch (e: WorkspacePathError) {
            null
        }
        val worker = HandlerThread("mbot-agent-broker").apply { start() }
        thread = worker
        messenger = Messenger(Handler(worker.looper, Handler.Callback { message ->
            try { handle(message) } catch (_: RuntimeException) { true }
        }))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread?.quitSafely()
        thread = null
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        if (message.what == IsolationWire.MSG_REGISTER) {
            val data = message.data
            val code = gate.register(message.sendingUid, data.getInt(IsolationWire.KEY_AGENT_UID, -1),
                data.getString(IsolationWire.KEY_SESSION))
            val reply = Message.obtain(null, IsolationWire.MSG_REGISTERED)
            reply.data = BrokerCodec.encodeResponse(BrokerResponse(code == BrokerResponse.OK, code)).apply {
                putString(IsolationWire.KEY_SESSION, data.getString(IsolationWire.KEY_SESSION))
            }
            try { message.replyTo?.send(reply) } catch (_: android.os.RemoteException) { }
            return true
        }
        if (message.what == IsolationWire.MSG_REVOKE) {
            gate.revoke(message.sendingUid, message.data.getString(IsolationWire.KEY_SESSION))
            return true
        }
        if (message.what != IsolationWire.MSG_BROKER_REQUEST) return false
        val data = message.data
        val id = data.getString(IsolationWire.KEY_ID)
        if (!validIsolationRequestId(id)) return true
        val request = BrokerCodec.decodeRequest(data)
        val response = when {
            !gate.permits(message.sendingUid, data.getString(IsolationWire.KEY_SESSION)) -> BrokerResponse.error(BrokerResponse.UNAUTHORIZED)
            request == null -> BrokerResponse.error(BrokerResponse.UNSUPPORTED_OP)
            broker == null -> BrokerResponse.error(BrokerResponse.BROKER_UNAVAILABLE)
            else -> broker!!.handle(request)
        }
        val reply = Message.obtain(null, IsolationWire.MSG_BROKER_RESPONSE)
        reply.data = BrokerCodec.encodeResponse(boundedIsolationResponse(response)).apply {
            putString(IsolationWire.KEY_ID, id)
            putString(IsolationWire.KEY_SESSION, data.getString(IsolationWire.KEY_SESSION))
        }
        try { message.replyTo?.send(reply) } catch (_: android.os.RemoteException) { }
        return true
    }
}
