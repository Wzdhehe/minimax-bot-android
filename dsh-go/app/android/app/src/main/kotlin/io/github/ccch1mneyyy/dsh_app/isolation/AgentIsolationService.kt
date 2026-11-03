// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

/** Isolated-process IPC placeholder, not an embedded Agent runtime. No code,
 * shell or credential operation exists. UID/permission behavior needs a device. */
class AgentIsolationService : Service() {
    private lateinit var handler: Handler
    private var broker: Messenger? = null
    private var session: String? = null
    private val pending = mutableMapOf<String, Pending>()
    private class Pending(val host: Messenger, val timeout: Runnable)
    private var brokerDeath: IBinder.DeathRecipient? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper(), Handler.Callback { message ->
            try { handle(message) } catch (_: Exception) { true }
        })
    }
    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder
    override fun onDestroy() { reset(); super.onDestroy() }

    private fun handle(message: Message): Boolean {
        // ApplicationInfo names the owning package's UID, while Process.myUid
        // is isolated here. Never trust a caller UID placed in Bundle fields.
        if (message.sendingUid != applicationInfo.uid) return true
        val data = message.data
        val nonce = data.getString(IsolationWire.KEY_SESSION)
        when (message.what) {
            IsolationWire.MSG_INIT -> {
                val accepted = nonce != null && nonce.matches(Regex("[0-9a-f]{64}")) &&
                    (session == null || session == nonce)
                if (accepted) {
                    val channel = readBroker(data)
                    if (channel != null) {
                        if (broker?.binder != channel.binder) {
                            brokerDeath?.let { broker?.binder?.unlinkToDeath(it, 0) }
                            val expected = channel.binder
                            val callback = IBinder.DeathRecipient {
                                handler.post { if (broker?.binder == expected) reset() }
                            }
                            channel.binder.linkToDeath(callback, 0)
                            brokerDeath = callback
                        }
                        broker = channel
                        session = nonce
                    }
                }
                val reply = Message.obtain(null, IsolationWire.MSG_READY)
                reply.data = BrokerCodec.encodeResponse(BrokerResponse(
                    accepted && broker != null, if (accepted && broker != null) BrokerResponse.OK else BrokerResponse.BUSY
                )).apply { putString(IsolationWire.KEY_SESSION, nonce) }
                try { message.replyTo?.send(reply) } catch (_: RemoteException) { }
            }
            IsolationWire.MSG_REVOKE -> if (nonce == session) reset()
            IsolationWire.MSG_TASK -> if (nonce == session) startTask(message)
            IsolationWire.MSG_BROKER_RESPONSE -> if (nonce == session) finishTask(message)
            else -> return false
        }
        return true
    }

    private fun readBroker(data: Bundle): Messenger? = if (Build.VERSION.SDK_INT >= 33) {
        data.getParcelable(IsolationWire.KEY_BROKER, Messenger::class.java)
    } else {
        @Suppress("DEPRECATION")
        data.getParcelable(IsolationWire.KEY_BROKER) as? Messenger
    }

    private fun startTask(message: Message) {
        val data = message.data
        val id = data.getString(IsolationWire.KEY_ID)
        val host = message.replyTo ?: return
        if (!validIsolationRequestId(id)) return
        val request = BrokerCodec.decodeRequest(data)
        val channel = broker
        val failure = when {
            pending.containsKey(id) || pending.size >= 32 -> BrokerResponse.BUSY
            request == null -> BrokerResponse.UNSUPPORTED_OP
            channel == null -> BrokerResponse.BROKER_UNAVAILABLE
            else -> null
        }
        if (failure != null) { reply(host, id!!, BrokerResponse.error(failure)); return }
        val timeout = Runnable {
            pending.remove(id)?.let {
                reply(it.host, id!!, BrokerResponse.error(if (request!!.op == BrokerOp.WRITE_FILE)
                    BrokerResponse.OUTCOME_UNKNOWN else BrokerResponse.TIMEOUT))
            }
        }
        pending[id!!] = Pending(host, timeout)
        handler.postDelayed(timeout, 15_000)
        val forwarded = Message.obtain(null, IsolationWire.MSG_BROKER_REQUEST)
        forwarded.data = Bundle().apply {
            putString(IsolationWire.KEY_SESSION, session)
            putString(IsolationWire.KEY_ID, id)
            putString(IsolationWire.KEY_OP, request!!.op.wire)
            putString(IsolationWire.KEY_PATH, request.path)
            putString(IsolationWire.KEY_TEXT, request.text)
        }
        forwarded.replyTo = Messenger(handler)
        try { channel!!.send(forwarded) } catch (_: RemoteException) {
            pending.remove(id)
            handler.removeCallbacks(timeout)
            reply(host, id, BrokerResponse.error(if (request!!.op == BrokerOp.WRITE_FILE)
                BrokerResponse.OUTCOME_UNKNOWN else BrokerResponse.BROKER_UNAVAILABLE))
        }
    }

    private fun finishTask(message: Message) {
        val id = message.data.getString(IsolationWire.KEY_ID) ?: return
        val task = pending.remove(id) ?: return
        handler.removeCallbacks(task.timeout)
        reply(task.host, id, BrokerCodec.decodeResponse(message.data))
    }
    private fun reply(host: Messenger, id: String, response: BrokerResponse) {
        val result = Message.obtain(null, IsolationWire.MSG_TASK_RESULT)
        result.data = BrokerCodec.encodeResponse(response).apply {
            putString(IsolationWire.KEY_ID, id)
            putString(IsolationWire.KEY_SESSION, session)
        }
        try { host.send(result) } catch (_: RemoteException) { }
    }
    private fun reset() {
        pending.toMap().forEach { (id, task) ->
            handler.removeCallbacks(task.timeout)
            reply(task.host, id, BrokerResponse.error(BrokerResponse.OUTCOME_UNKNOWN))
        }
        pending.clear()
        try { brokerDeath?.let { broker?.binder?.unlinkToDeath(it, 0) } } catch (_: Exception) { }
        brokerDeath = null
        broker = null
        session = null
    }
}
