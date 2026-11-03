// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.bot.BotProviderStore
import io.github.ccch1mneyyy.dsh_app.bot.NativeModelRequestHost
import io.github.ccch1mneyyy.dsh_app.bot.NativeModelChannels
import io.github.ccch1mneyyy.dsh_app.model.NativeModelBroker
import io.github.ccch1mneyyy.dsh_app.model.ProviderCall
import io.github.ccch1mneyyy.dsh_app.model.ProviderResponse
import io.github.ccch1mneyyy.dsh_app.model.ProviderTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Node/FD evidence, not a real provider or full mcode test. */
@RunWith(AndroidJUnit4::class)
class IsolatedNodeModelTest {
    @Test fun isolatedNodeUsesNativeOnlyKeysForStreamingCancellationAndEchoRefusal() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "node-key-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val store = BotProviderStore(context)
        val secret = "node-test-only-${UUID.randomUUID()}"
        val headersInjected = AtomicInteger()
        val cancelled = CountDownLatch(1)
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val marker = File(base.filesDir, "node-private-${UUID.randomUUID()}")
        marker.writeText("non-sensitive marker")
        assertTrue(marker.inputStream().use { it.read() } >= 0)
        var providerId: String? = null
        try {
            store.save(mapOf("name" to "Node test", "baseUrl" to "https://example.invalid/v1",
                "apiFormat" to "openai-completions", "models" to listOf("normal", "stalled", "echo"), "key" to secret))
            providerId = ((store.catalog()["providers"] as List<*>).single() as Map<*, *>)["providerId"] as String
            val transport = ProviderTransport { prepared ->
                if (prepared.headers["Authorization"] == "Bearer $secret") headersInjected.incrementAndGet()
                val model = JSONObject(prepared.body).getString("model")
                object : ProviderCall {
                    override fun execute(): ProviderResponse {
                        val body = when (model) {
                            "stalled" -> object : InputStream() {
                                override fun read(): Int { cancelled.await(30, TimeUnit.SECONDS); return -1 }
                            }
                            else -> {
                                val event = JSONObject().put("delta", if (model == "echo") secret else "hello")
                                "data: $event\n\ndata: [DONE]\n\n".byteInputStream()
                            }
                        }
                        return ProviderResponse(200, "text/event-stream", body)
                    }
                    override fun cancel() { if (model == "stalled") cancelled.countDown() }
                    override fun close() { if (model == "stalled") cancelled.countDown() }
                }
            }
            NativeModelBroker(store::resolveForModelBroker, transport) { logs.add(it.wire) }.use { broker ->
                NativeModelChannels(broker.session).use { channels ->
                    fun modelFd(model: String) = channels.open(JSONObject().put("providerId", providerId).put("modelId", model)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi"))).toString())
                    val modelFds = listOf(modelFd("normal"), modelFd("stalled"), modelFd("echo"))
                    val resultPair = ParcelFileDescriptor.createSocketPair()
                    try {
                        Os.setsockoptTimeval(resultPair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                            StructTimeval.fromMillis(5000))
                        Os.setsockoptTimeval(resultPair[1].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                            StructTimeval.fromMillis(10_000))
                        ProbeConnection(base).use { probe ->
                            val identity = probe.identity().get(10, TimeUnit.SECONDS)
                            assertNotEquals(Process.myUid(), identity.uid)
                            NativeModelRequestHost(identity.uid, providerId!!, "normal", probe.peerBinder(), channels::open, channels::close).use { host ->
                            val id = UUID.randomUUID().toString()
                            val data = Bundle().apply {
                                putString("id", id)
                                putString("privatePath", marker.absolutePath)
                                putBinder("model_host", host); putString("model_session", host.session); putString("providerId", providerId)
                                putParcelable("normal", modelFds[0]); putParcelable("stalled", modelFds[1])
                                putParcelable("echo", modelFds[2]); putParcelable("result", resultPair[1])
                            }
                            assertFalse(data.toString().contains(secret))
                            val completion = probe.run(data)
                            modelFds.forEach { it.close() }
                            resultPair[1].close()
                            val reply = completion.get(35, TimeUnit.SECONDS)
                            assertNotEquals(Process.myUid(), reply.uid)
                            assertEquals("ok", reply.data.getString("code"))
                            val bytes = ByteArrayOutputStream()
                            ParcelFileDescriptor.AutoCloseInputStream(resultPair[0]).use { input ->
                                val chunk = ByteArray(4096)
                                while (true) {
                                    val count = input.read(chunk)
                                    if (count < 0) break
                                    check(bytes.size() + count <= 128 * 1024) { "node_result_too_large" }
                                    bytes.write(chunk, 0, count)
                                }
                            }
                            val text = bytes.toString("UTF-8")
                            // Assertions never include the generated Key or raw
                            // report/env/body as a JUnit failure message.
                            assertFalse(text.contains(secret))
                            assertFalse(text.contains(broker.session.token))
                            assertFalse(text.contains(host.session))
                            val report = JSONObject(text)
                            assertEquals("24.22.2", report.getString("version"))
                            val expected = base.assets.open("node-validation.json").bufferedReader().use { JSONObject(it.readText()) }
                            if (expected.getBoolean("requiresIntl")) {
                                assertTrue(report.getBoolean("intlAvailable"))
                                assertEquals("zh-CN", report.getJSONObject("intlSmoke").getString("locale"))
                                assertEquals("Asia/Shanghai", report.getJSONObject("intlSmoke").getString("timeZone"))
                            }
                            if (expected.optBoolean("requiresSqlite")) {
                                val sqlite = report.getJSONObject("sqlite")
                                assertEquals(expected.getString("sqliteEngineVersion"), sqlite.getString("version"))
                                assertEquals("你好", sqlite.getJSONArray("rows").getString(0))
                                assertEquals("second", sqlite.getJSONArray("rows").getString(1))
                                assertEquals(2, sqlite.getJSONArray("rows").length())
                                assertEquals(2, sqlite.getInt("restoredCount"))
                                assertEquals("memory", sqlite.getString("journalMode"))
                                assertEquals(7, sqlite.getInt("jsonValue"))
                            }
                            assertEquals(reply.uid, report.getInt("uid"))
                            assertTrue(report.getBoolean("privateDenied"))
                            assertEquals("done", report.getJSONObject("normal").getString("code"))
                            assertEquals("hello", report.getJSONObject("normal").getJSONArray("events").getJSONObject(0).getString("delta"))
                            assertEquals("broker_cancelled", report.getJSONObject("cancelled").getString("code"))
                            assertEquals("provider_sensitive_response", report.getJSONObject("echoed").getString("code"))
                            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
                            assertEquals("done", report.getJSONObject("dynamic").getString("code"))
                            assertEquals("hello", report.getJSONObject("dynamic").getJSONArray("events").getJSONObject(0).getString("delta"))
                            assertTrue(report.getBoolean("dynamicCancelled"))
                            assertEquals(4, headersInjected.get())
                            assertFalse(store.catalog().toString().contains(secret))
                            assertFalse(logs.any { it.contains(secret) })
                            }
                        }
                    } finally {
                        modelFds.forEach { try { it.close() } catch (_: Exception) { } }
                        resultPair.forEach { try { it.close() } catch (_: Exception) { } }
                    }
                }
            }
        } finally {
            providerId?.let { store.delete(it) }
            base.deleteSharedPreferences(prefix + "minimax_bot_provider")
            marker.delete()
        }
    }

    private data class Reply(val uid: Int, val data: Bundle)
    private class ProbeConnection(private val context: Context) : java.io.Closeable {
        private val connected = CompletableFuture<Messenger>()
        private val result = CompletableFuture<Reply>()
        private val identityResult = CompletableFuture<Reply>()
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
            true
        }))
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(Messenger(service)) }
            override fun onServiceDisconnected(name: ComponentName) { fail() }
            override fun onNullBinding(name: ComponentName) { fail() }
            override fun onBindingDied(name: ComponentName) { fail() }
        }
        init { check(context.bindService(Intent(context, NodeIsolationProbeService::class.java), connection, Context.BIND_AUTO_CREATE)) }
        fun run(data: Bundle): CompletableFuture<Reply> {
            expectedId = data.getString("id")
            connected.get(10, TimeUnit.SECONDS).send(Message.obtain(null, 1).apply { this.data = data; replyTo = receiver })
            return result
        }
        private fun fail() {
            val error = IllegalStateException("node_probe_disconnected")
            connected.completeExceptionally(error); result.completeExceptionally(error); identityResult.completeExceptionally(error)
        }
        override fun close() { context.unbindService(connection); fail(); thread.quitSafely() }
    }
}
