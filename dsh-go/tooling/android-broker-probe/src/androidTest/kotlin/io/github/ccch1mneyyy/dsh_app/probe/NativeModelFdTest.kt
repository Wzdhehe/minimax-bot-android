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
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.bot.NativeModelChannels
import io.github.ccch1mneyyy.dsh_app.bot.NativeModelRequestHost
import io.github.ccch1mneyyy.dsh_app.isolation.ModelRequestClient
import io.github.ccch1mneyyy.dsh_app.model.NativeModelBroker
import io.github.ccch1mneyyy.dsh_app.model.NativeProvider
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerCode
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure
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

    @Test fun explicitlyGrantedDataFdCanBeReadWhilePathAccessRemainsDenied() = dataFd("read")
    @Test fun explicitlyGrantedDataFdCanBeWrittenAndSyncedWhilePathAccessRemainsDenied() = dataFd("write")
    @Test fun explicitlyGrantedDataFdCanBeTruncatedWhilePathAccessRemainsDenied() = dataFd("truncate")
    @Test fun explicitlyGrantedDataFdCanHoldAnExclusiveRangeLockWhilePathAccessRemainsDenied() = dataFd("lock")
    @Test fun explicitlyGrantedDataFdCanBeMappedReadWriteWhilePathAccessRemainsDenied() = dataFd("map")

    private fun dataFd(operation: String) {
        val marker = File(context.filesDir, "data-fd-${UUID.randomUUID()}")
        marker.writeText("marker-before")
        try {
            ParcelFileDescriptor.open(marker, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                Os.ftruncate(fd.fileDescriptor, 4096)
                // Same primitives in the owner prove the file/FS support them.
                DataFileFdProbe.perform(fd.fileDescriptor, operation)
                Os.ftruncate(fd.fileDescriptor, 4096)
                val initial = "marker-before".toByteArray()
                Os.pwrite(fd.fileDescriptor, initial, 0, initial.size, 0)
                ProbeConnection(context).use { probe ->
                    val response = probe.request(IsolationBoundaryProbeService.CHECK_DATA_FD, Bundle().apply {
                        putParcelable("channel", fd); putString("operation", operation); putString("path", marker.absolutePath)
                    }).get(10, TimeUnit.SECONDS)
                    assertNotEquals(Process.myUid(), response.uid)
                    assertTrue("data FD probe failed", response.data.getBoolean("ok"))
                    assertTrue("granted FD $operation denied, errno=${response.data.getInt("errno")}", response.data.getBoolean("permitted"))
                    assertTrue(response.data.getBoolean("path_denied"))
                    if (operation == "write") assertTrue(marker.readText().startsWith("marker-after"))
                    if (operation == "truncate") assertEquals(8192L, marker.length())
                }
            }
        } finally { marker.delete() }
    }

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
            NativeModelChannels(broker.session).use { channels ->
                ProbeConnection(context).use { probe ->
                    val response = channels.open(request).use { channel ->
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
            NativeModelChannels(broker.session).use { channels ->
                ProbeConnection(context).use { probe ->
                    val reply = channels.open(request).use { channel ->
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
    }

    @Test fun ownerCloseAndCapacityLimitBoundNativeModelChannels() {
        val opened = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        NativeModelBroker({ _, _ -> NativeProvider("openai-completions", "https://example.com/v1", "test-only") },
            stalledTransport(opened, cancelled)).use { broker ->
            NativeModelChannels(broker.session, maxChannels = 1).use { channels ->
                channels.open(request).use {
                    assertTrue(opened.await(3, TimeUnit.SECONDS))
                    try { channels.open(request).close(); fail("capacity limit bypassed") }
                    catch (error: ModelBrokerFailure) { assertEquals(ModelBrokerCode.UNAVAILABLE, error.code) }
                    channels.close()
                    assertTrue(cancelled.await(3, TimeUnit.SECONDS))
                    try { channels.open(request).close(); fail("closed channel owner accepted a request") }
                    catch (error: ModelBrokerFailure) { assertEquals(ModelBrokerCode.UNAVAILABLE, error.code) }
                }
            }
        }
    }

    @Test fun channelDeadlineCancelsAProviderWhileTheReceiverKeepsItsFdOpen() {
        val opened = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        NativeModelBroker({ _, _ -> NativeProvider("openai-completions", "https://example.com/v1", "test-only") },
            stalledTransport(opened, cancelled)).use { broker ->
            NativeModelChannels(broker.session, timeoutMs = 500).use { channels ->
                channels.open(request).use {
                    assertTrue(opened.await(3, TimeUnit.SECONDS))
                    assertTrue("channel deadline did not cancel provider", cancelled.await(3, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun stalledTransport(opened: CountDownLatch, cancelled: CountDownLatch) = ProviderTransport {
        opened.countDown()
        object : ProviderCall {
            override fun execute() = ProviderResponse(200, "text/event-stream", object : InputStream() {
                override fun read(): Int { cancelled.await(10, TimeUnit.SECONDS); return -1 }
            })
            override fun cancel() { cancelled.countDown() }
            override fun close() { cancelled.countDown() }
        }
    }

    @Test fun isolatedRuntimeDynamicallyOpensModelFdWithUidRouteSessionAndCancellationChecks() {
        val secret = "request-test-only-${UUID.randomUUID()}"
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val transport = ProviderTransport { prepared ->
            check(prepared.headers["Authorization"] == "Bearer $secret") { "native_auth_missing" }
            val content = org.json.JSONObject(prepared.body).getJSONArray("messages").getJSONObject(0).getString("content")
            if (content.startsWith("large-")) check(content == "large-".repeat(14_000)) { "large_body_changed" }
            calls.incrementAndGet()
            object : ProviderCall {
                override fun execute() = ProviderResponse(200, "text/event-stream",
                    "data: {\"delta\":\"dynamic\"}\n\ndata: [DONE]\n\n".byteInputStream())
                override fun cancel() { }
                override fun close() { }
            }
        }
        NativeModelBroker({ _, _ -> NativeProvider("openai-completions", "https://example.invalid/v1", secret) }, transport).use { broker ->
            NativeModelChannels(broker.session).use { channels ->
                ProbeConnection(context).use { probe ->
                    val identity = probe.request(IsolationBoundaryProbeService.IDENTITY, Bundle()).get(10, TimeUnit.SECONDS)
                    assertNotEquals(Process.myUid(), identity.uid)
                    NativeModelRequestHost(identity.uid, "p1", "m1", probe.peerBinder(), channels::open, channels::close, readTimeoutMs = 2_000).use { host ->
                        fun invoke(body: String = request, session: String = host.session, cancel: Boolean = false,
                            large: Boolean = false, oversize: Boolean = false, stall: Boolean = false, cancelDuring: Boolean = false): Reply =
                            probe.request(IsolationBoundaryProbeService.REQUEST_MODEL, Bundle().apply {
                                putBinder("model_host", host); putString("model_session", session)
                                putString("model_body", body); putBoolean("cancel_before_open", cancel)
                                putBoolean("large_body", large); putBoolean("oversize_body", oversize)
                                putBoolean("stall_body", stall); putBoolean("cancel_during_body", cancelDuring)
                            }).get(10, TimeUnit.SECONDS)
                        val response = invoke()
                        assertEquals(identity.uid, response.uid)
                        assertTrue(response.data.getBoolean("ok"))
                        val text = response.data.getString("text")!!
                        assertTrue(text.contains("dynamic") && text.contains("data: [DONE]"))
                        assertFalse(text.contains(secret) || text.contains(broker.session.token) || text.contains(host.session))
                        assertFalse(host.toString().contains(secret) || host.toString().contains(host.session))
                        assertTrue(invoke(large = true).data.getBoolean("ok"))
                        assertEquals(ModelBrokerCode.INVALID_PROVIDER.wire,
                            invoke(request.replace("\"m1\"", "\"other\"")).data.getString("code"))
                        assertEquals(ModelBrokerCode.UNAVAILABLE.wire, invoke(session = "0".repeat(64)).data.getString("code"))
                        assertEquals(ModelBrokerCode.CANCELLED.wire, invoke(cancel = true).data.getString("code"))
                        assertEquals(ModelBrokerCode.INVALID_REQUEST.wire, invoke(oversize = true).data.getString("code"))
                        assertEquals(ModelBrokerCode.CANCELLED.wire, invoke(large = true, cancel = true).data.getString("code"))
                        assertEquals(ModelBrokerCode.UNAVAILABLE.wire, invoke(stall = true).data.getString("code"))
                        assertEquals(ModelBrokerCode.CANCELLED.wire, invoke(stall = true, cancelDuring = true).data.getString("code"))
                        // Even the owner UID cannot bypass this capability's observed isolated-UID binding.
                        try { ModelRequestClient(host, host.session).open(UUID.randomUUID().toString(), request).close(); fail("owner UID bypassed binding") }
                        catch (error: ModelBrokerFailure) { assertEquals(ModelBrokerCode.UNAVAILABLE, error.code) }
                        assertEquals(2, calls.get())
                        host.close()
                        assertEquals(ModelBrokerCode.UNAVAILABLE.wire, invoke().data.getString("code"))
                    }
                }
            }
        }
    }

    private data class Reply(val uid: Int, val data: Bundle)
    private class ProbeConnection(private val context: Context) : Closeable {
        private val thread = HandlerThread("mbot-fd-test-client").apply { start() }
        private val connected = CompletableFuture<Messenger>()
        fun peerBinder(): IBinder = connected.get(5, TimeUnit.SECONDS).binder
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
            try {
                target.send(Message.obtain(null, what).apply {
                    this.data = data.apply { putString("id", id) }
                    replyTo = receiver
                })
            } catch (_: android.os.RemoteException) {
                pending.remove(id)
                throw IllegalStateException("probe_transfer_failed_alive=${target.binder.isBinderAlive}")
            }
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
