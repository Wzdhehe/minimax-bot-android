// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.probe

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.bot.openNativeModelChannel
import io.github.ccch1mneyyy.dsh_app.model.NativeModelBroker
import io.github.ccch1mneyyy.dsh_app.model.NativeProvider
import io.github.ccch1mneyyy.dsh_app.model.ProviderCall
import io.github.ccch1mneyyy.dsh_app.model.ProviderResponse
import io.github.ccch1mneyyy.dsh_app.model.ProviderTransport
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeModelFdTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val request = """{"providerId":"p1","modelId":"m1","messages":[{"role":"user","content":"Hi"}]}"""

    @Test fun isolatedUidCannotReadPrivateFileOrOpenLoopbackEvenThoughOwnerCan() {
        val file = File(context.filesDir, "boundary-probe-${UUID.randomUUID()}")
        file.writeText("non-sensitive marker")
        try {
            assertTrue(file.inputStream().use { it.read() } >= 0)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                ProbeConnection(context).use { probe ->
                    val response = probe.request(IsolationBoundaryProbeService.CHECK_BOUNDARY, Bundle().apply {
                        putString("path", file.absolutePath)
                        putInt("port", listener.localPort)
                    }).get(10, TimeUnit.SECONDS)
                    assertTrue(response.uid >= 0 && response.uid != Process.myUid())
                    assertTrue(response.data.getBoolean("ok"))
                    assertTrue(response.data.getBoolean("private_file_denied"))
                    assertTrue(response.data.getBoolean("internet_permission_denied"))
                    assertTrue(response.data.getBoolean("loopback_connect_denied"))
                }
            }
        } finally { file.delete() }
    }

    @Test fun preconnectedModelFdStreamsWithoutGivingIsolatedUidNativeTokenOrProviderKey() {
        val secret = "fd-test-only-${UUID.randomUUID()}"
        val injected = AtomicBoolean()
        val transport = ProviderTransport { prepared ->
            injected.set(prepared.headers["Authorization"] == "Bearer $secret")
            object : ProviderCall {
                override fun execute() = ProviderResponse(200, "text/event-stream",
                    "data: {\"delta\":\"hello\"}\n\ndata: [DONE]\n\n".byteInputStream())
                override fun cancel() { }
                override fun close() { }
            }
        }
        NativeModelBroker({ _, _ -> NativeProvider("openai-completions", "https://example.com/v1", secret) }, transport).use { broker ->
            ProbeConnection(context).use { probe ->
                val response = openNativeModelChannel(broker.session, request).use { channel ->
                    // send() has duplicated the FD into Binder before the
                    // sender's close. Keep no owner copy alive after transfer.
                    probe.request(IsolationBoundaryProbeService.READ_CHANNEL, Bundle().apply { putParcelable("channel", channel) })
                }.get(10, TimeUnit.SECONDS)
                assertNotEquals(Process.myUid(), response.uid)
                assertTrue(response.data.getBoolean("ok"))
                val text = response.data.getString("text")!!
                assertTrue(injected.get())
                assertTrue(text.startsWith("HTTP/1.1 200 OK"))
                assertTrue(text.contains("hello"))
                assertTrue(text.contains("data: [DONE]"))
                assertFalse(text.contains(secret))
                assertFalse(text.contains(broker.session.token))
            }
        }
    }

    @Test fun closingFdInIsolatedUidCancelsAStalledNativeProviderCall() {
        val opened = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val transport = ProviderTransport {
            opened.countDown()
            object : ProviderCall {
                override fun execute() = ProviderResponse(200, "text/event-stream", object : InputStream() {
                    override fun read(): Int { cancelled.await(10, TimeUnit.SECONDS); return -1 }
                })
                override fun cancel() { cancelled.countDown() }
                override fun close() { cancelled.countDown() }
            }
        }
        NativeModelBroker({ _, _ -> NativeProvider("openai-completions", "https://example.com/v1", "test-only") }, transport).use { broker ->
            ProbeConnection(context).use { probe ->
                val reply = openNativeModelChannel(broker.session, request).use { channel ->
                    assertTrue(opened.await(3, TimeUnit.SECONDS))
                    probe.request(IsolationBoundaryProbeService.READ_CHANNEL, Bundle().apply {
                        putParcelable("channel", channel)
                        putBoolean("prefix_only", true)
                    })
                }.get(10, TimeUnit.SECONDS)
                assertTrue(reply.data.getBoolean("ok"))
                assertNotEquals(Process.myUid(), reply.uid)
                assertTrue("isolated close did not cancel upstream", cancelled.await(3, TimeUnit.SECONDS))
            }
        }
    }

    private data class Reply(val uid: Int, val data: Bundle)
    private class ProbeConnection(private val context: Context) : Closeable {
        private val thread = HandlerThread("mbot-fd-test-client").apply { start() }
        private val connected = CompletableFuture<Messenger>()
        private val pending = ConcurrentHashMap<String, CompletableFuture<Reply>>()
        private val receiver = Messenger(Handler(thread.looper, Handler.Callback { message ->
            message.data.getString("id")?.let { pending.remove(it)?.complete(Reply(message.sendingUid, message.data)) }
            true
        }))
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { fail() }
            override fun onNullBinding(name: ComponentName) { fail() }
            override fun onBindingDied(name: ComponentName) { fail() }
        }
        init {
            check(context.bindService(Intent(context, IsolationBoundaryProbeService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        fun request(what: Int, data: Bundle): CompletableFuture<Reply> {
            val target = connected.get(10, TimeUnit.SECONDS)
            val id = UUID.randomUUID().toString()
            val future = CompletableFuture<Reply>()
            pending[id] = future
            target.send(Message.obtain(null, what).apply {
                this.data = data.apply { putString("id", id) }
                replyTo = receiver
            })
            return future
        }
        private fun fail() {
            val failure = IllegalStateException("probe_disconnected")
            connected.completeExceptionally(failure)
            pending.values.forEach { it.completeExceptionally(failure) }
            pending.clear()
        }
        override fun close() {
            context.unbindService(connection)
            fail()
            thread.quitSafely()
        }
    }
}
