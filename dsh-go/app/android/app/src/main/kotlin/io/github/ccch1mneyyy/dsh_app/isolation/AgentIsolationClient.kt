// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Trusted main-process owner of the file-only isolation probe. READY means
 * correlated IPC is ready, not that Node, a model or an Android sandbox was
 * validated. No descriptor/API key is sent to the isolated process. */
class AgentIsolationClient private constructor(
    private val context: Context,
    private val timeoutMs: Long,
) : Closeable {
    private val worker = HandlerThread("mbot-isolation-client").apply { start() }
    private val closed = AtomicBoolean()
    private var cleaned = false
    private var ready = false
    private var initSent = false
    private var broker: Messenger? = null
    private var agent: Messenger? = null
    private var agentUid = -1
    private val ownerUid = Process.myUid()
    private val session = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private val completion = CompletableFuture<AgentIsolationClient>()
    private val disposal = CompletableFuture<Unit>()
    private val pending = mutableMapOf<String, Pending>()
    private class Pending(val op: BrokerOp, val future: CompletableFuture<BrokerResponse>, val timeout: Runnable)
    private val bound = mutableListOf<ServiceConnection>()
    private val handler = Handler(worker.looper, Handler.Callback { message ->
        if (!closed.get()) {
            try { receive(message) } catch (_: RuntimeException) { cleanup(BrokerResponse.BROKER_UNAVAILABLE) }
        }
        true
    })
    private val replies = Messenger(handler)
    private val startupTimeout = Runnable { cleanup(BrokerResponse.TIMEOUT) }

    /** Observed only after the private READY and broker registration handshake. */
    val isolatedUid: Int get() = agentUid

    companion object {
        fun connect(context: Context, timeoutMs: Long = 10_000): CompletableFuture<AgentIsolationClient> {
            require(timeoutMs in 100..60_000)
            val client = AgentIsolationClient(context.applicationContext, timeoutMs)
            client.completion.whenComplete { _, _ -> if (client.completion.isCancelled) client.close() }
            client.handler.post { client.bind() }
            return client.completion
        }
    }

    private inner class Connection(private val isolated: Boolean) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            handler.post {
                if (closed.get()) return@post
                if (isolated) agent = Messenger(service) else broker = Messenger(service)
                initialize()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { handler.post { cleanup(BrokerResponse.BROKER_UNAVAILABLE) } }
        override fun onBindingDied(name: ComponentName) { handler.post { cleanup(BrokerResponse.BROKER_UNAVAILABLE) } }
        override fun onNullBinding(name: ComponentName) { handler.post { cleanup(BrokerResponse.BROKER_UNAVAILABLE) } }
    }

    private fun bind() {
        if (closed.get()) { cleanup(BrokerResponse.BROKER_UNAVAILABLE); return }
        handler.postDelayed(startupTimeout, timeoutMs)
        for ((service, isolated) in listOf(AgentBrokerService::class.java to false, AgentIsolationService::class.java to true)) {
            if (closed.get()) { cleanup(BrokerResponse.BROKER_UNAVAILABLE); return }
            val connection = Connection(isolated)
            try {
                if (!context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE)) {
                    cleanup(BrokerResponse.BROKER_UNAVAILABLE); return
                }
                bound.add(connection)
            } catch (_: RuntimeException) { cleanup(BrokerResponse.BROKER_UNAVAILABLE); return }
        }
    }

    private fun initialize() {
        if (initSent || broker == null || agent == null) return
        initSent = true
        send(agent, IsolationWire.MSG_INIT, Bundle().apply { putParcelable(IsolationWire.KEY_BROKER, broker) })
    }

    private fun receive(message: Message) {
        val data = message.data
        if (data.getString(IsolationWire.KEY_SESSION) != session) return
        when (message.what) {
            IsolationWire.MSG_READY -> {
                if (ready || agentUid >= 0 || message.sendingUid < 0 || message.sendingUid == ownerUid) {
                    cleanup(BrokerResponse.UNAUTHORIZED); return
                }
                val response = BrokerCodec.decodeResponse(data)
                if (!response.ok) { cleanup(response.code); return }
                agentUid = message.sendingUid
                send(broker, IsolationWire.MSG_REGISTER, Bundle().apply { putInt(IsolationWire.KEY_AGENT_UID, agentUid) })
            }
            IsolationWire.MSG_REGISTERED -> {
                if (message.sendingUid != ownerUid || agentUid < 0) { cleanup(BrokerResponse.UNAUTHORIZED); return }
                val response = BrokerCodec.decodeResponse(data)
                if (!response.ok) { cleanup(response.code); return }
                ready = true
                handler.removeCallbacks(startupTimeout)
                completion.complete(this)
            }
            IsolationWire.MSG_TASK_RESULT -> {
                if (!ready || message.sendingUid != agentUid) return
                val id = data.getString(IsolationWire.KEY_ID) ?: return
                val task = pending.remove(id) ?: return
                handler.removeCallbacks(task.timeout)
                task.future.complete(BrokerCodec.decodeResponse(data))
            }
        }
    }

    fun request(request: BrokerRequest): CompletableFuture<BrokerResponse> {
        val future = CompletableFuture<BrokerResponse>()
        if (closed.get()) return CompletableFuture.completedFuture(BrokerResponse.error(BrokerResponse.BROKER_UNAVAILABLE))
        if (!handler.post {
            if (closed.get() || !ready) { future.complete(BrokerResponse.error(BrokerResponse.BROKER_UNAVAILABLE)); return@post }
            if (pending.size >= 32) { future.complete(BrokerResponse.error(BrokerResponse.BUSY)); return@post }
            // Binder has a per-process transaction budget. Larger files need a
            // future FD protocol; do not put them into a near-1MiB Bundle.
            if (request.path.length > 4096 || (request.text?.toByteArray(Charsets.UTF_8)?.size ?: 0) > 256 * 1024) {
                future.complete(BrokerResponse.error(BrokerResponse.TOO_LARGE)); return@post
            }
            val id = UUID.randomUUID().toString()
            val timeout = Runnable {
                pending.remove(id)?.future?.complete(BrokerResponse.error(
                    if (request.op == BrokerOp.WRITE_FILE) BrokerResponse.OUTCOME_UNKNOWN else BrokerResponse.TIMEOUT))
            }
            pending[id] = Pending(request.op, future, timeout)
            handler.postDelayed(timeout, timeoutMs)
            send(agent, IsolationWire.MSG_TASK, Bundle().apply {
                putString(IsolationWire.KEY_ID, id)
                putString(IsolationWire.KEY_OP, request.op.wire)
                putString(IsolationWire.KEY_PATH, request.path)
                putString(IsolationWire.KEY_TEXT, request.text)
            })
        }) future.complete(BrokerResponse.error(BrokerResponse.BROKER_UNAVAILABLE))
        return future
    }

    private fun send(target: Messenger?, what: Int, data: Bundle) {
        val message = Message.obtain(null, what)
        message.data = data.apply { putString(IsolationWire.KEY_SESSION, session) }
        message.replyTo = replies
        try { target?.send(message) ?: cleanup(BrokerResponse.BROKER_UNAVAILABLE) }
        catch (_: android.os.RemoteException) { cleanup(BrokerResponse.BROKER_UNAVAILABLE) }
        catch (_: RuntimeException) { cleanup(BrokerResponse.BROKER_UNAVAILABLE) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) handler.post { cleanup(BrokerResponse.BROKER_UNAVAILABLE) }
    }

    /** Waits for this owner's pending-future cleanup, revoke sends and unbinds.
     * It is not an acknowledgement that the remote process has exited or a
     * queued file write has rolled back. Do not block the Android UI thread. */
    fun closeAsync(): CompletableFuture<Unit> { close(); return disposal }

    private fun cleanup(code: String) {
        if (cleaned) return
        cleaned = true
        closed.set(true)
        handler.removeCallbacks(startupTimeout)
        for (channel in listOf(agent, broker)) {
            val revoke = Message.obtain(null, IsolationWire.MSG_REVOKE)
            revoke.data = Bundle().apply { putString(IsolationWire.KEY_SESSION, session) }
            try { channel?.send(revoke) } catch (_: android.os.RemoteException) { }
            catch (_: RuntimeException) { }
        }
        pending.values.forEach {
            handler.removeCallbacks(it.timeout)
            it.future.complete(BrokerResponse.error(if (it.op == BrokerOp.WRITE_FILE) BrokerResponse.OUTCOME_UNKNOWN else code))
        }
        pending.clear()
        completion.completeExceptionally(IllegalStateException(code))
        bound.forEach { try { context.unbindService(it) } catch (_: RuntimeException) { } }
        bound.clear()
        broker = null; agent = null
        worker.quitSafely()
        disposal.complete(Unit)
    }
}
