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
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeDescriptor
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeDescriptorSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
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
    private fun offerFromForeignUid(context: Context, target: IBinder, fd: ParcelFileDescriptor): Reply {
        val connected = CompletableFuture<IBinder>()
        val response = CompletableFuture<Reply>()
        val thread = HandlerThread("mbot-foreign-offer-client").apply { start() }
        val receiver = Messenger(Handler(thread.looper, Handler.Callback { message ->
            if (message.what == 8) response.complete(Reply(message.sendingUid, message.data))
            true
        }))
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) { response.completeExceptionally(IllegalStateException("foreign_probe_disconnected")) }
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("foreign_probe_unavailable")) }
            override fun onBindingDied(name: ComponentName) { response.completeExceptionally(IllegalStateException("foreign_probe_disconnected")) }
        }
        if (!context.bindService(Intent(context, ForeignOfferProbeService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            thread.quitSafely()
            throw IllegalStateException("foreign_probe_unavailable")
        }
        var peer: IBinder? = null
        try {
            peer = connected.get(10, TimeUnit.SECONDS)
            Messenger(peer).send(Message.obtain(null, 1).apply {
                data = Bundle().apply { putBinder("peer", target); putParcelable("connection", fd) }
                replyTo = receiver
            })
            return response.get(10, TimeUnit.SECONDS)
        } finally {
            val stopped = CountDownLatch(1)
            val death = IBinder.DeathRecipient { stopped.countDown() }
            if (peer != null) try { peer.linkToDeath(death, 0) } catch (_: Exception) { stopped.countDown() }
            try {
                context.unbindService(connection)
                if (peer != null) check(stopped.await(10, TimeUnit.SECONDS)) { "foreign_process_exit_unverified" }
            } finally {
                if (peer != null) try { peer.unlinkToDeath(death, 0) } catch (_: Exception) { }
                thread.quitSafely()
            }
        }
    }

    private fun readBoundedReport(fd: ParcelFileDescriptor, first: Byte? = null): String {
        val bytes = ByteArrayOutputStream()
        if (first != null) bytes.write(first.toInt())
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            val chunk = ByteArray(4096)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                check(bytes.size() + count <= 128 * 1024) { "node_result_too_large" }
                bytes.write(chunk, 0, count)
            }
        }
        return bytes.toString("UTF-8")
    }

    private fun probeFailure(report: JSONObject): AssertionError {
        // Failure messages include only finite allowlisted codes, never a raw
        // report, provider response, path, session token or generated Key.
        val phases = setOf("bootstrap", "sqlite_load", "sqlite_memory", "vfs_register", "vfs_open", "vfs_wal",
            "vfs_verify", "vfs_write", "vfs_reopen", "model_streams", "descriptor_transfer", "bot_http_fd", "model_dynamic", "sdk_route", "scoped_model_route", "file_commit", "file_read", "file_mutation", "file_append", "bot_store")
        val codes = setOf("SQLITE_CANTOPEN", "SQLITE_IOERR", "SQLITE_IOERR_SHMOPEN", "SQLITE_BUSY", "SQLITE_READONLY",
            "SQLITE_ERROR", "SQLITE_PERM", "SQLITE_MISUSE", "SQLITE_NOTFOUND", "fd_vfs_unavailable", "fd_vfs_input_invalid", "sqlite_parent_missing",
            "runtime_data_invalid", "runtime_data_denied", "runtime_data_closed", "runtime_data_unavailable", "runtime_data_not_found", "runtime_data_too_large", "runtime_data_outcome_unknown", "runtime_data_reload_required",
            "descriptor_invalid", "descriptor_permissions", "descriptor_not_a_regular_file", "bot_transport_timeout", "bot_transport_invalid", "bot_transport_unavailable", "memory_pending", "memory_backup_failed")
        val phase = report.optString("failedPhase").takeIf { it in phases } ?: "unknown"
        val code = report.optString("failureCode").takeIf { it in codes } ?: "unknown"
        return AssertionError("node_probe_failed_at_${phase}_code_${code}")
    }

    @Test fun connectionOfferedBeforeNodeStartsIsRejectedAndClosed() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val pair = ParcelFileDescriptor.createSocketPair()
        try {
            Os.setsockoptTimeval(pair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                StructTimeval.fromMillis(10_000))
            ProbeConnection(base).use { probe ->
                val identity = probe.identity().get(10, TimeUnit.SECONDS)
                assertNotEquals(Process.myUid(), identity.uid)
                val response = probe.offerBotConnection(pair[1]).get(10, TimeUnit.SECONDS)
                assertEquals(identity.uid, response.uid)
                assertEquals("unavailable", response.data.getString("code"))
                pair[1].close()
                assertEquals(0, Os.read(pair[0].fileDescriptor, ByteArray(1), 0, 1))
            }
        } finally { pair.forEach { try { it.close() } catch (_: Exception) { } } }
    }

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
        var expectsMemoryRecovery = false
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
                        var descriptorFd: ParcelFileDescriptor? = null
                        try {
                            val descriptorSession = RuntimeDescriptorSession(
                                "/runtime/probe.mjs", "97818b2890b4acae1c6bd1200a226ff506680628", 43117,
                                RuntimeDescriptorSession.randomToken(), catalogJson = store.guestCatalogJson())
                            val descriptorDirectory = File(dataDirectory, "descriptor")
                            descriptorFd = RuntimeDescriptor.writeForTransfer(descriptorDirectory, descriptorSession)
                            val encoded = File(descriptorDirectory, RuntimeDescriptor.FILE_NAME).readText()
                            assertFalse(encoded.contains(secret))
                            assertFalse(encoded.contains("https://example.invalid"))
                            assertTrue(JSONObject(encoded).isNull("broker"))
                            // The granted FD must retain its generation even if
                            // the Main owner atomically publishes a new one.
                            val newerDescriptor = RuntimeDescriptorSession(
                                descriptorSession.entryPath, descriptorSession.pinCommit, 43119,
                                RuntimeDescriptorSession.randomToken(), catalogJson = store.guestCatalogJson())
                            RuntimeDescriptor.write(descriptorDirectory, newerDescriptor)
                            Os.setsockoptTimeval(resultPair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                                StructTimeval.fromMillis(30_000))
                            Os.setsockoptTimeval(resultPair[1].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                                StructTimeval.fromMillis(10_000))
                            ProbeConnection(base).use { probe ->
                                val identity = probe.identity().get(10, TimeUnit.SECONDS)
                                assertNotEquals(Process.myUid(), identity.uid)
                                assertTrue(identity.data.getBoolean("database_owner_denied"))
                                assertTrue(identity.data.getBoolean("publication_owner_denied"))
                                NativeModelRequestHost(identity.uid, providerId!!, "normal", probe.peerBinder(), channels::open, channels::close).use { host ->
                                RuntimeFileCommitHost.openBotState(context, "node-state", identity.uid, probe.peerBinder()).let { fileHost ->
                                try {
                                if (attempt == 1 && expectsMemoryRecovery) {
                                    val restoredRoot = File(dataDirectory, "minimax-bot-runtime/bot-state/node-state")
                                    assertEquals(0, restoredRoot.list()!!.count { it.matches(Regex("bot-memory-stage-[a-f0-9]{64}\\.bin")) })
                                }
                                val id = UUID.randomUUID().toString()
                                val data = Bundle().apply {
                                    putString("id", id)
                                    putString("privatePath", marker.absolutePath)
                                    putBinder("model_host", host); putString("model_session", host.session); putString("providerId", providerId)
                                    putBinder("file_host", fileHost); putString("file_session", fileHost.session)
                                    putParcelable("normal", modelFds[0]); putParcelable("stalled", modelFds[1])
                                    putParcelable("echo", modelFds[2]); putParcelable("result", resultPair[1])
                                    putParcelable("runtime_descriptor", descriptorFd)
                                    listOf("db", "wal", "shm", "journal").forEachIndexed { i, name -> putParcelable(name, dataDescriptors[i]) }
                                }
                                assertFalse(data.toString().contains(secret))
                                val completion = probe.run(data)
                                modelFds.forEach { it.close() }
                                resultPair[1].close()
                                val ready = ByteArray(1)
                                assertEquals(1, Os.read(resultPair[0].fileDescriptor, ready, 0, 1))
                                if (ready[0] != 'R'.code.toByte()) {
                                    val diagnostic = readBoundedReport(resultPair[0], ready[0])
                                    val report = try { JSONObject(diagnostic) } catch (_: Exception) { JSONObject() }
                                    throw probeFailure(report)
                                }
                                // A different isolated UID tries a syntactically valid offer
                                // while Node is actively waiting. Its FD must be closed,
                                // not consumed as either of the two authorized grants.
                                val foreignPair = ParcelFileDescriptor.createSocketPair()
                                try {
                                    Os.setsockoptTimeval(foreignPair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                                        StructTimeval.fromMillis(8000))
                                    val foreign = offerFromForeignUid(base, probe.peerBinder(), foreignPair[1])
                                    assertNotEquals(Process.myUid(), foreign.uid)
                                    assertNotEquals(identity.uid, foreign.uid)
                                    assertEquals("sent", foreign.data.getString("code"))
                                    foreignPair[1].close()
                                    assertEquals(0, Os.read(foreignPair[0].fileDescriptor, ByteArray(1), 0, 1))
                                } finally { foreignPair.forEach { try { it.close() } catch (_: Exception) { } } }
                                val request = "GET /fd-probe HTTP/1.1\r\nHost: 127.0.0.1:43117\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                                repeat(2) {
                                    // Create and grant each socket only after Node has started;
                                    // the second grant follows the first complete HTTP response.
                                    val pair = ParcelFileDescriptor.createSocketPair()
                                    try {
                                        Os.setsockoptTimeval(pair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                                            StructTimeval.fromMillis(20_000))
                                        val accepted = probe.offerBotConnection(pair[1]).get(10, TimeUnit.SECONDS)
                                        assertEquals(identity.uid, accepted.uid)
                                        assertEquals("ok", accepted.data.getString("code"))
                                        pair[1].close()
                                        var written = 0
                                        while (written < request.size) {
                                            val count = Os.write(pair[0].fileDescriptor, request, written, request.size - written)
                                            check(count > 0) { "bot_fd_request_failed" }
                                            written += count
                                        }
                                        val botBytes = ByteArrayOutputStream()
                                        val botChunk = ByteArray(1024)
                                        while (true) {
                                            val count = Os.read(pair[0].fileDescriptor, botChunk, 0, botChunk.size)
                                            if (count <= 0) break
                                            check(botBytes.size() + count <= 4096) { "bot_fd_response_too_large" }
                                            botBytes.write(botChunk, 0, count)
                                        }
                                        val botResponse = botBytes.toString("UTF-8")
                                        assertTrue(botResponse.startsWith("HTTP/1.1 200"))
                                        assertTrue(botResponse.contains("fd-ok"))
                                        assertFalse(botResponse.contains(secret))
                                    } finally { pair.forEach { try { it.close() } catch (_: Exception) { } } }
                                }
                                val reply = completion.get(50, TimeUnit.SECONDS)
                                assertNotEquals(Process.myUid(), reply.uid)
                                val text = readBoundedReport(resultPair[0])
                                // Assertions never include the generated Key or raw
                                // report/env/body as a JUnit failure message.
                                assertFalse(text.contains(secret))
                                assertFalse(text.contains(broker.session.token))
                                assertFalse(text.contains(host.session))
                                assertFalse(text.contains(fileHost.session))
                                assertFalse(text.contains(descriptorSession.token))
                                assertFalse(text.contains(newerDescriptor.token))
                                val report = if (text.isBlank()) JSONObject() else JSONObject(text)
                                if (reply.data.getString("code") != "ok") throw probeFailure(report)
                                assertEquals("24.22.2", report.getString("version"))
                                val expected = base.assets.open("node-validation.json").bufferedReader().use { JSONObject(it.readText()) }
                                if (expected.getBoolean("requiresIntl")) {
                                    assertTrue(report.getBoolean("intlAvailable"))
                                    assertEquals("zh-CN", report.getJSONObject("intlSmoke").getString("locale"))
                                    assertEquals("Asia/Shanghai", report.getJSONObject("intlSmoke").getString("timeZone"))
                                }
                                val botStore = report.getJSONObject("botStore")
                                if (report.getBoolean("intlAvailable")) {
                                    assertTrue(botStore.getBoolean("available"))
                                    assertEquals(attempt, botStore.getInt("before"))
                                    assertEquals(attempt + 1, botStore.getInt("after"))
                                    assertEquals(1, botStore.getInt("schedules"))
                                    assertEquals(1, botStore.getInt("usage"))
                                    assertEquals(attempt == 0, botStore.getBoolean("recorded"))
                                    assertEquals(attempt, botStore.getInt("memoryBefore"))
                                    assertEquals(1, botStore.getInt("memoryAfter"))
                                    assertEquals(if (attempt == 0) 1 else 3, botStore.getInt("memoryJournal"))
                                    assertTrue(botStore.getBoolean("memoryBackup"))
                                    assertTrue(botStore.getBoolean("memoryRecovery"))
                                    expectsMemoryRecovery = true
                                } else assertFalse(botStore.getBoolean("available"))
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
                                val descriptorReport = report.getJSONObject("descriptorTransfer")
                                for (name in listOf("pin", "provider", "generation", "brokerAbsent", "closed")) assertTrue(descriptorReport.getBoolean(name))
                                val botTransportReport = report.getJSONObject("botFdTransport")
                                assertTrue(botTransportReport.getBoolean("request"))
                                assertTrue(botTransportReport.getBoolean("noListener"))
                                assertEquals(2, botTransportReport.getInt("connections"))
                                assertTrue(botTransportReport.getBoolean("cancelledWait"))
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
                                val scoped = report.getJSONObject("scopedModelRoute")
                                assertEquals(400, scoped.getInt("unboundStatus"))
                                for (name in listOf("first", "second")) {
                                    val routed = scoped.getJSONObject(name)
                                    assertEquals(200, routed.getInt("status"))
                                    assertTrue(routed.getBoolean("toolCalls"))
                                    assertTrue(routed.getBoolean("done"))
                                }
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
                                val mutations = report.getJSONObject("fileMutations")
                                for (name in listOf("created", "createdConflict", "moved", "renamed")) {
                                    assertEquals("published", mutations.getString(name))
                                }
                                assertEquals("already_exists", mutations.getString("conflict"))
                                assertEquals("removed", mutations.getString("removed"))
                                for (name in listOf("readback", "sourceMissing", "preserved", "replacement", "missing")) {
                                    assertTrue(mutations.getBoolean(name))
                                }
                                val invalidMutations = mutations.getJSONArray("invalid")
                                assertEquals(6, invalidMutations.length())
                                repeat(6) { assertTrue(invalidMutations.getBoolean(it)) }
                                val appends = report.getJSONObject("fileAppends")
                                for (name in listOf("created", "committed", "empty")) assertEquals("published", appends.getString(name))
                                for (name in listOf("binary", "overLimit", "unchanged", "invalid")) assertTrue(appends.getBoolean(name))
                                assertEquals(0, appends.getInt("emptySize"))
                                for (name in listOf("removed", "emptyRemoved")) assertEquals("removed", appends.getString(name))
                                val committed = File(dataDirectory, "minimax-bot-runtime/bot-state/node-state")
                                val names = committed.list()!!.sorted()
                                val expectedNames = mutableListOf("node-empty.bin", "node-snapshot.bin", "node-state.bin")
                                if (report.getBoolean("intlAvailable")) {
                                    val chatFiles = names.filter { it.matches(Regex("bot-chat-[a-f0-9]{64}\\.json")) }
                                    assertEquals(1, chatFiles.size)
                                    expectedNames.addAll(listOf("bot-state.json", "bot-usage.jsonl", chatFiles.single()))
                                    val memoryFiles = names.filter { it.matches(Regex("bot-memory-[a-f0-9]{64}\\.bin")) }
                                    assertEquals(if (attempt == 0) 4 else 3, memoryFiles.size)
                                    expectedNames.addAll(memoryFiles)
                                    val pendingHash = MessageDigest.getInstance("SHA-256").digest(".pending.json".toByteArray()).joinToString("") { "%02x".format(it) }
                                    assertEquals(attempt == 0, names.contains("bot-memory-$pendingHash.bin"))
                                    val memoryStages = names.filter { it.matches(Regex("bot-memory-stage-[a-f0-9]{64}\\.bin")) }
                                    assertEquals(if (attempt == 0) 1 else 0, memoryStages.size)
                                    expectedNames.addAll(memoryStages)
                                }
                                assertEquals(expectedNames.sorted(), names)
                                assertTrue(fileProbeBytes(false).contentEquals(File(committed, "node-snapshot.bin").readBytes()))
                                assertTrue(fileProbeBytes(true).contentEquals(File(committed, "node-state.bin").readBytes()))
                                assertEquals(0L, File(committed, "node-empty.bin").length())
                                assertTrue(committed.listFiles()!!.none { it.readBytes().toString(Charsets.UTF_8).contains(secret) })
                                assertEquals(8, headersInjected.get())
                                assertFalse(store.catalog().toString().contains(secret))
                                assertFalse(logs.any { it.contains(secret) })
                                } finally { fileHost.closeAsync().get(5, TimeUnit.SECONDS) }
                                }
                                }
                            }
                        } finally {
                            try { descriptorFd?.close() } catch (_: Exception) { }
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
