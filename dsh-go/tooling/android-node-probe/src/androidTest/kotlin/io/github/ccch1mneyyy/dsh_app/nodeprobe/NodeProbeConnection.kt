// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

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
import android.os.ParcelFileDescriptor
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class Reply(val uid: Int, val data: Bundle)
internal class ProbeConnection(private val context: Context) : java.io.Closeable {
    private val connected = CompletableFuture<Messenger>()
    private val result = CompletableFuture<Reply>()
    private val identityResult = CompletableFuture<Reply>()
    private val offers = ConcurrentHashMap<String, CompletableFuture<Reply>>()
    fun peerBinder(): IBinder = connected.get(10, TimeUnit.SECONDS).binder
    fun identity(): CompletableFuture<Reply> {
        connected.get(10, TimeUnit.SECONDS).send(Message.obtain(null, 3).apply { replyTo = receiver })
        return identityResult
    }
    private val thread = HandlerThread("mbot-node-probe-client").apply { start() }
    @Volatile private var expectedId: String? = null
    private val receiver = Messenger(Handler(thread.looper, Handler.Callback { message ->
        if (message.what == 3 && message.data.getString("code") == "ready") identityResult.complete(Reply(message.sendingUid, message.data))
        if (message.what == 2 && message.data.getString("id") == expectedId) result.complete(Reply(message.sendingUid, message.data))
        if (message.what == 7) message.data.getString("id")?.let { id -> offers.remove(id)?.complete(Reply(message.sendingUid, message.data)) }
        true
    }))
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(Messenger(service)) }
        override fun onServiceDisconnected(name: ComponentName) { fail() }
        override fun onNullBinding(name: ComponentName) { fail() }
        override fun onBindingDied(name: ComponentName) { fail() }
    }
    init { check(context.bindService(Intent(context, NodeIsolationProbeService::class.java), connection, Context.BIND_AUTO_CREATE)) }
    fun run(data: Bundle, what: Int = 1): CompletableFuture<Reply> {
        check(what == 1 || what in 4..6)
        expectedId = data.getString("id")
        connected.get(10, TimeUnit.SECONDS).send(Message.obtain(null, what).apply { this.data = data; replyTo = receiver })
        return result
    }
    fun offerBotConnection(fd: ParcelFileDescriptor): CompletableFuture<Reply> {
        val id = UUID.randomUUID().toString()
        val result = CompletableFuture<Reply>()
        offers[id] = result
        try {
            connected.get(10, TimeUnit.SECONDS).send(Message.obtain(null, 7).apply {
                data = Bundle().apply { putString("id", id); putParcelable("bot_connection", fd) }
                replyTo = receiver
            })
        } catch (error: Exception) {
            offers.remove(id)
            result.completeExceptionally(error)
        }
        return result
    }
    private fun fail() {
        val error = IllegalStateException("node_probe_disconnected")
        connected.completeExceptionally(error); result.completeExceptionally(error); identityResult.completeExceptionally(error)
        offers.values.forEach { it.completeExceptionally(error) }; offers.clear()
    }
    override fun close() {
        val peer = try { connected.getNow(null)?.binder } catch (_: Exception) { null }
        val stopped = CountDownLatch(1)
        val death = IBinder.DeathRecipient { stopped.countDown() }
        if (peer != null) try { peer.linkToDeath(death, 0) } catch (_: Exception) { stopped.countDown() }
        try {
            context.unbindService(connection)
            if (peer != null) check(stopped.await(10, TimeUnit.SECONDS)) { "isolated_process_exit_unverified" }
        } finally {
            if (peer != null) try { peer.unlinkToDeath(death, 0) } catch (_: Exception) { }
            fail(); thread.quitSafely()
        }
    }
}
