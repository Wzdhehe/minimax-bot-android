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
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDatabaseFiles
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitHost
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
        val dataDirectory = File(base.noBackupFilesDir, "node-data-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
            override fun getNoBackupFilesDir() = dataDirectory
        }
        val store = BotProviderStore(context)
        val secret = "node-test-only-${UUID.randomUUID()}"
        val headersInjected = AtomicInteger()
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val marker = File(base.filesDir, "node-private-${UUID.randomUUID()}")
        marker.writeText("non-sensitive marker")
        var dataFiles = emptyList<File>()
        var databaseOwner: RuntimeDatabaseFiles? = null
        val dataDescriptors = mutableListOf<ParcelFileDescriptor>()
        assertTrue(marker.inputStream().use { it.read() } >= 0)
        var providerId: String? = null
        var previousPid = -1
        try {
            databaseOwner = RuntimeDatabaseFiles.open(context)
            dataFiles = databaseOwner.physicalFiles
            dataDescriptors.addAll(databaseOwner.duplicateForTransfer())
            store.save(mapOf("name" to "Node test", "baseUrl" to "https://example.invalid/v1",
                "apiFormat" to "openai-completions", "models" to listOf("normal", "stalled", "echo"), "key" to secret))
            providerId = ((store.catalog()["providers"] as List<*>).single() as Map<*, *>)["providerId"] as String
            repeat(2) { attempt ->
                val cancelled = CountDownLatch(1)
                headersInjected.set(0)
                val transport = ProviderTransport { prepared ->
                    if (prepared.headers["Authorization"] == "Bearer $secret") headersInjected.incrementAndGet()
                    val content = JSONObject(prepared.body).getJSONArray("messages").getJSONObject(0).getString("content")
                    if (content.startsWith("large-")) check(content == "large-".repeat(14_000)) { "large_body_changed" }
                    val model = JSONObject(prepared.body).getString("model")
                    object : ProviderCall {
                        override fun execute(): ProviderResponse {
                            val body = when (model) {
                                "stalled" -> object : InputStream() {
                                    override fun read(): Int { cancelled.await(30, TimeUnit.SECONDS); return -1 }
                                }
                                else -> {
                                    if (JSONObject(prepared.body).has("tools")) return ProviderResponse(200, "text/event-stream",
                                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n".byteInputStream())
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
                                assertTrue(identity.data.getBoolean("database_owner_denied"))
                                assertTrue(identity.data.getBoolean("publication_owner_denied"))
                                NativeModelRequestHost(identity.uid, providerId!!, "normal", probe.peerBinder(), channels::open, channels::close).use { host ->
                                RuntimeFileCommitHost.open(context, "node-state", identity.uid, probe.peerBinder(), allowReplace = true, allowRead = true).use { fileHost ->
                                val id = UUID.randomUUID().toString()
                                val data = Bundle().apply {
                                    putString("id", id)
                                    putString("privatePath", marker.absolutePath)
                                    putBinder("model_host", host); putString("model_session", host.session); putString("providerId", providerId)
                                    putBinder("file_host", fileHost); putString("file_session", fileHost.session)
                                    putParcelable("normal", modelFds[0]); putParcelable("stalled", modelFds[1])
                                    putParcelable("echo", modelFds[2]); putParcelable("result", resultPair[1])
                                    listOf("db", "wal", "shm", "journal").forEachIndexed { i, name -> putParcelable(name, dataDescriptors[i]) }
                                }
                                assertFalse(data.toString().contains(secret))
                                val completion = probe.run(data)
                                modelFds.forEach { it.close() }
                                resultPair[1].close()
                                val reply = completion.get(35, TimeUnit.SECONDS)
                                assertNotEquals(Process.myUid(), reply.uid)
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
                                assertFalse(text.contains(fileHost.session))
                                val report = if (text.isBlank()) JSONObject() else JSONObject(text)
                                if (reply.data.getString("code") != "ok") {
                                    val phases = setOf("bootstrap", "sqlite_load", "sqlite_memory", "vfs_register", "vfs_open", "vfs_wal",
                                        "vfs_verify", "vfs_write", "vfs_reopen", "model_streams", "model_dynamic", "sdk_route", "file_commit", "file_read")
                                    val codes = setOf("SQLITE_CANTOPEN", "SQLITE_IOERR", "SQLITE_IOERR_SHMOPEN", "SQLITE_BUSY", "SQLITE_READONLY",
                                        "SQLITE_ERROR", "SQLITE_PERM", "SQLITE_MISUSE", "SQLITE_NOTFOUND", "fd_vfs_unavailable", "fd_vfs_input_invalid", "sqlite_parent_missing",
                                        "runtime_data_invalid", "runtime_data_denied", "runtime_data_closed", "runtime_data_unavailable", "runtime_data_not_found", "runtime_data_too_large")
                                    val phase = report.optString("failedPhase").takeIf { it in phases } ?: "unknown"
                                    val code = report.optString("failureCode").takeIf { it in codes } ?: "unknown"
                                    fail("node_probe_failed_at_${phase}_code_${code}")
                                }
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
                                if (expected.optBoolean("requiresFdVfs")) {
                                    val persistent = report.getJSONObject("persistent")
                                    assertEquals("wal", persistent.getString("mode"))
                                    assertEquals(if (attempt == 0) 0 else 2, persistent.getJSONArray("initialRows").length())
                                    assertEquals("你好", persistent.getJSONArray("parallelRows").getString(0))
                                    assertEquals(2, persistent.getInt("count"))
                                    assertEquals("你好", persistent.getJSONArray("reopened").getString(0))
                                    assertEquals("second", persistent.getJSONArray("reopened").getString(1))
                                    assertEquals("ok", persistent.getString("integrity"))
                                    assertTrue(dataFiles[0].length() > 0)
                                    assertTrue(dataFiles.none { it.readBytes().toString(Charsets.UTF_8).contains(secret) })
                                }
                                val currentPid = report.getInt("pid")
                                assertTrue(currentPid > 0)
                                assertNotEquals(previousPid, currentPid)
                                previousPid = currentPid
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
                                assertEquals("done", report.getJSONObject("dynamicLarge").getString("code"))
                                assertEquals("hello", report.getJSONObject("dynamicLarge").getJSONArray("events").getJSONObject(0).getString("delta"))
                                assertEquals(200, report.getJSONObject("sdkRoute").getInt("status"))
                                assertTrue(report.getJSONObject("sdkRoute").getBoolean("toolCalls"))
                                assertTrue(report.getJSONObject("sdkRoute").getBoolean("done"))
                                val fileCommits = report.getJSONObject("fileCommits")
                                for (name in listOf("snapshot", "initial", "empty")) {
                                    assertEquals(if (attempt == 0) "published" else "already_exists", fileCommits.getString(name))
                                }
                                assertEquals("published", fileCommits.getString("replaced"))
                                assertEquals(6, fileCommits.getJSONArray("rejected").length())
                                repeat(6) { assertTrue(fileCommits.getJSONArray("rejected").getBoolean(it)) }
                                val fileReads = report.getJSONObject("fileReads")
                                assertEquals(84 * 1024, fileReads.getInt("size"))
                                assertEquals(0, fileReads.getInt("emptySize"))
                                for (name in listOf("binary", "original", "empty", "missing", "invalidPath", "invalidMetadata")) {
                                    assertTrue(fileReads.getBoolean(name))
                                }
                                val committed = File(dataDirectory, "minimax-bot-runtime/files/node-state")
                                assertEquals(listOf("node-empty.bin", "node-snapshot.bin", "node-state.bin"), committed.list()!!.sorted())
                                assertTrue(fileProbeBytes(false).contentEquals(File(committed, "node-snapshot.bin").readBytes()))
                                assertTrue(fileProbeBytes(true).contentEquals(File(committed, "node-state.bin").readBytes()))
                                assertEquals(0L, File(committed, "node-empty.bin").length())
                                assertTrue(committed.listFiles()!!.none { it.readBytes().toString(Charsets.UTF_8).contains(secret) })
                                assertEquals(6, headersInjected.get())
                                assertFalse(store.catalog().toString().contains(secret))
                                assertFalse(logs.any { it.contains(secret) })
                                }
                                }
                            }
                        } finally {
                            modelFds.forEach { try { it.close() } catch (_: Exception) { } }
                            resultPair.forEach { try { it.close() } catch (_: Exception) { } }
                        }
                    }
                }
            }
        } finally {
            providerId?.let { store.delete(it) }
            base.deleteSharedPreferences(prefix + "minimax_bot_provider")
            marker.delete()
            dataDescriptors.forEach { try { it.close() } catch (_: Exception) { } }
            databaseOwner?.close()
            dataDirectory.deleteRecursively()
        }
    }

}
