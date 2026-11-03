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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent execution placeholder running in an **isolated process** (`android:isolatedProcess`
 * in the manifest).
 *
 * The system gives this process its own UID: it holds none of the app's permissions, has no
 * network access and cannot read the app's private `filesDir`. The only capability it can
 * reach is the main-process broker, handed to it as a Messenger at init time, and that
 * broker only serves path-guarded workspace files.
 *
 * It is a placeholder, not the Agent runtime: it executes a file task by asking the broker
 * and relaying the answer. No shell, no exec, no credential.
 */
class AgentIsolationService : Service() {

    private lateinit var handler: Handler
    private var brokerMessenger: Messenger? = null
    private val pending = ConcurrentHashMap<Int, Messenger>()
    private val nextId = AtomicInteger(1)

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper(), Handler.Callback { message -> handle(message) })
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(handler).binder

    private fun handle(message: Message): Boolean {
        when (message.what) {
            IsolationWire.MSG_INIT -> {
                brokerMessenger = readBroker(message.data)
                message.replyTo?.send(Message.obtain(null, IsolationWire.MSG_READY))
                return true
            }
            IsolationWire.MSG_TASK -> {
                startTask(message)
                return true
            }
            IsolationWire.MSG_BROKER_RESPONSE -> {
                finishTask(message)
                return true
            }
            else -> return false
        }
    }

    /** The broker channel arrives as a Parcelable Messenger; nothing else crosses over. */
    private fun readBroker(data: Bundle?): Messenger? {
        if (data == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelable(IsolationWire.KEY_BROKER, Messenger::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelable(IsolationWire.KEY_BROKER) as? Messenger
        }
    }

    private fun startTask(message: Message) {
        val data = message.data
        val op = BrokerOp.fromWire(data?.getString(IsolationWire.KEY_OP))
        val broker = brokerMessenger
        val host = message.replyTo
        if (op == null || broker == null) {
            val failure = BrokerResponse.error(
                if (op == null) BrokerResponse.UNSUPPORTED_OP else BrokerResponse.BROKER_UNAVAILABLE
            )
            replyToHost(host, null, failure)
            return
        }
        val id = nextId.getAndIncrement()
        if (host != null) pending[id] = host
        val request = Message.obtain(null, IsolationWire.MSG_BROKER_REQUEST)
        request.data = Bundle().apply {
            putString(IsolationWire.KEY_ID, id.toString())
            putString(IsolationWire.KEY_OP, op.wire)
            putString(IsolationWire.KEY_PATH, data?.getString(IsolationWire.KEY_PATH) ?: "")
            putString(IsolationWire.KEY_TEXT, data?.getString(IsolationWire.KEY_TEXT))
        }
        request.replyTo = Messenger(handler)
        broker.send(request)
    }

    private fun finishTask(message: Message) {
        val id = message.data?.getString(IsolationWire.KEY_ID)?.toIntOrNull()
        val host = id?.let { pending.remove(it) }
        replyToHost(host, id, BrokerCodec.decodeResponse(message.data))
    }

    private fun replyToHost(host: Messenger?, id: Int?, response: BrokerResponse) {
        val reply = Message.obtain(null, IsolationWire.MSG_TASK_RESULT)
        reply.data = BrokerCodec.encodeResponse(response).apply {
            id?.let { putString(IsolationWire.KEY_ID, it.toString()) }
        }
        reply.replyTo = host
        host?.send(reply)
    }
}
