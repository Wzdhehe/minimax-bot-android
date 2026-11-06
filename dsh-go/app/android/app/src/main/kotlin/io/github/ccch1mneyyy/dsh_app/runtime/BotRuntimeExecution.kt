// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

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
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import io.github.ccch1mneyyy.dsh_app.bot.BotProviderStore
import io.github.ccch1mneyyy.dsh_app.bot.NativeModelChannels
import io.github.ccch1mneyyy.dsh_app.bot.NativeModelRequestHost
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDatabaseFiles
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitHost
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeSnapshotHost
import io.github.ccch1mneyyy.dsh_app.model.NativeModelBroker
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One product execution. Source aliases and leases are retained until the
 * START sender finishes AND the process-anchored peer has actually died.
 * Binder ACK, unbind, timeout and cancellation are never exit evidence. */
internal class BotRuntimeExecution(private val context: Context, private val changed: () -> Unit) {
    lateinit var run: BotRuntimeCoordinator.Run
    private val session = RuntimeDescriptorSession.randomToken()
    private val closing = AtomicBoolean()
    private val cleanupOffered = AtomicBoolean()
    private val readyOffered = AtomicBoolean()
    private val startDone = CountDownLatch(1)
    private val connected = CompletableFuture<IBinder>()
    private val identity = CompletableFuture<Int>()
    private val dead = CompletableFuture<Unit>()
    private val cleanup = CompletableFuture<Unit>()
    private val offers = ConcurrentHashMap<String, CompletableFuture<BotConnectionGrants.Receipt>>()
    private val handlerThread = HandlerThread("mbot-product-control").apply { start() }
    @Volatile private var uid = -1
    @Volatile private var peer: IBinder? = null
    @Volatile private var bound = false
    @Volatile var failure: String? = null
        private set
    @Volatile var catalogJson: String? = null
        private set
    private var database: RuntimeDatabaseFiles? = null
    private var fileHost: RuntimeFileCommitHost? = null
    private var snapshotHost: RuntimeSnapshotHost? = null
    private var modelHost: NativeModelRequestHost? = null
    private var channels: NativeModelChannels? = null
    private var broker: NativeModelBroker? = null
    private var healthGate: BotConnectionGrants? = null
    private val death = IBinder.DeathRecipient {
        dead.complete(Unit)
        if (!closing.get()) fail("bot_runtime_exited")
    }
    private val receiver = Messenger(Handler(handlerThread.looper, Handler.Callback { message ->
        val data = message.data
        if (data.getString("session") != session) return@Callback true
        if (message.what == BotNodeWire.IDENTITY && message.sendingUid > 0 && message.sendingUid != Process.myUid()) {
            identity.complete(message.sendingUid)
        } else if (message.sendingUid == uid) when (message.what) {
            BotNodeWire.CONNECTION -> data.getString("id")?.let { id ->
                offers.remove(id)?.complete(BotConnectionGrants.Receipt(message.sendingUid, data.getString("code") == "ok"))
            }
            BotNodeWire.READY -> if (readyOffered.compareAndSet(false, true) && !closing.get()) starter.execute { publishAfterHealth() }
            BotNodeWire.FAILED -> fail(data.getString("code").takeIf { it in failureCodes } ?: "bot_runtime_start_failed")
        }
        true
    }))
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (cleanup.isDone) return
            if (peer != null) return
            peer = service
            try { service.linkToDeath(death, 0); connected.complete(service) }
            catch (_: Exception) { dead.complete(Unit); connected.completeExceptionally(IOException("bot_runtime_unavailable")) }
        }
        override fun onServiceDisconnected(name: ComponentName) { if (!closing.get()) fail("bot_runtime_disconnected") }
        override fun onBindingDied(name: ComponentName) { if (!closing.get()) fail("bot_runtime_disconnected") }
        override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IOException("bot_runtime_unavailable")) }
    }
    fun start() {
        starter.execute {
            try {
                if (closing.get()) return@execute
                BotCodeAssets(context) // Fail before binding if the product is not packaged.
                android.util.Log.i("MiniMaxBotRuntime", "bot_assets_manifest_verified")
                bound = context.bindService(Intent(context, IsolatedBotService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) throw IOException()
                val target = connected.get(10, TimeUnit.SECONDS)
                send(target, BotNodeWire.HELLO)
                uid = identity.get(10, TimeUnit.SECONDS)
                if (closing.get()) return@execute
                android.util.Log.i("MiniMaxBotRuntime", "bot_isolated_peer_verified")
                run.attachBinder(context, target, uid) { gate -> BotSocketConnection.open(context) { fd -> gate.awaitAccepted { offer(fd) } } }
                val store = BotProviderStore(context)
                val catalog = store.guestCatalogJson()
                catalogJson = catalog
                val rows = JSONObject(catalog).getJSONArray("providers")
                val permitted = mutableSetOf<Pair<String, String>>()
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    if (!row.getBoolean("enabled")) continue
                    val models = row.getJSONArray("models")
                    for (model in 0 until models.length()) permitted.add(row.getString("providerId") to models.getString(model))
                }
                val selected = permitted.firstOrNull() ?: ("unconfigured" to "unconfigured")
                val network = NativeModelBroker(store::resolveForModelBroker).also { broker = it }
                val transport = NativeModelChannels(network.session).also { channels = it }
                val model = NativeModelRequestHost(uid, selected.first, selected.second, target, transport::open, transport::close,
                    allowedModels = permitted).also { modelHost = it }
                val files = RuntimeFileCommitHost.openBotState(context, "primary", uid, target).also { fileHost = it }
                val source = RuntimeDatabaseFiles.open(context).also { database = it }
                source.acquireExecutionLease()
                val snapshots = RuntimeSnapshotHost.open(context, source, uid, session).also { snapshotHost = it }
                val descriptor = RuntimeDescriptorSession("/mbot-code/runtime.mjs", "97818b2890b4acae1c6bd1200a226ff506680628",
                    run.port, run.token, catalogJson = catalog)
                RuntimeDescriptor.writeForTransfer(File(context.noBackupFilesDir, "minimax-bot-descriptor"), descriptor).use { descriptorFile ->
                    val data = source.duplicateForTransfer()
                    try {
                        if (closing.get()) return@use
                        send(target, BotNodeWire.START, Bundle().apply {
                            putBinder("model_host", model); putString("model_session", model.session)
                            putBinder("file_host", files); putString("file_session", files.session)
                            putBinder("snapshot_host", snapshots); putString("snapshot_session", session)
                            putParcelable("descriptor", descriptorFile)
                            listOf("db", "wal", "shm", "journal").forEachIndexed { index, key -> putParcelable(key, data[index]) }
                        })
                        android.util.Log.i("MiniMaxBotRuntime", "bot_grants_sent")
                    } finally { data.forEach { it.close() } }
                }
            } catch (_: Exception) { if (!closing.get()) fail("bot_runtime_start_failed") }
            catch (_: LinkageError) { if (!closing.get()) fail("bot_runtime_assets_unavailable") }
            finally { startDone.countDown() }
        }
        deadlines.schedule({ if (run.phase == BotRuntimeCoordinator.Run.Phase.STARTING) fail("bot_runtime_start_timeout") }, 90, TimeUnit.SECONDS)
    }
    private fun send(target: IBinder, what: Int, body: Bundle = Bundle()) {
        body.putString("session", session)
        Messenger(target).send(Message.obtain(null, what).apply { data = body; replyTo = receiver })
    }
    private fun offer(file: ParcelFileDescriptor): CompletableFuture<BotConnectionGrants.Receipt> {
        val result = CompletableFuture<BotConnectionGrants.Receipt>()
        if (closing.get()) { result.completeExceptionally(IOException("bot_runtime_closed")); return result }
        val id = UUID.randomUUID().toString()
        offers[id] = result
        result.whenComplete { _, _ -> offers.remove(id, result) }
        try { send(checkNotNull(peer), BotNodeWire.CONNECTION, Bundle().apply { putString("id", id); putParcelable("connection", file) }) }
        catch (_: Exception) { offers.remove(id); result.completeExceptionally(IOException("bot_runtime_unavailable")) }
        return result
    }
    private fun publishAfterHealth() {
        if (closing.get()) return
        val gate = BotConnectionGrants(uid).also { healthGate = it }
        try {
            for (authorized in listOf(false, true)) {
                val pair = ParcelFileDescriptor.createSocketPair()
                try {
                    Os.setsockoptTimeval(pair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(5000))
                    Os.setsockoptTimeval(pair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(5000))
                    gate.awaitAccepted { offer(pair[1]) }
                    pair[1].close()
                    val authorization = if (authorized) "Authorization: Bearer ${run.token}\r\n" else ""
                    val request = "GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:${run.port}\r\n${authorization}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    var sent = 0
                    while (sent < request.size) { val count = Os.write(pair[0].fileDescriptor, request, sent, request.size - sent); if (count <= 0) throw IOException(); sent += count }
                    val header = java.io.ByteArrayOutputStream()
                    val byte = ByteArray(1)
                    while (header.size() < 8192) {
                        if (Os.read(pair[0].fileDescriptor, byte, 0, 1) != 1) throw IOException()
                        header.write(byte[0].toInt())
                        if (header.size() >= 4 && header.toString("US-ASCII").endsWith("\r\n\r\n")) break
                    }
                    val text = header.toString("US-ASCII")
                    if (!text.endsWith("\r\n\r\n") || !text.startsWith(if (authorized) "HTTP/1.1 200 " else "HTTP/1.1 403 ")) throw IOException()
                } finally { pair.forEach { try { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) } catch (_: Exception) { }; try { it.close() } catch (_: Exception) { } } }
            }
            if (!closing.get() && run.ready()) {
                android.util.Log.i("MiniMaxBotRuntime", "bot_ready")
                changed()
            } else throw IOException()
        } catch (_: Exception) { fail("bot_runtime_health_failed") }
        finally { gate.close(); healthGate = null }
    }
    fun fail(code: String) {
        val safe = code.takeIf { it in failureCodes } ?: "bot_runtime_start_failed"
        if (!closing.get()) { failure = safe; android.util.Log.w("MiniMaxBotRuntime", safe) }
        if (::run.isInitialized) run.close()
        changed()
    }
    fun closeAsync(): CompletableFuture<Unit> {
        closing.set(true)
        healthGate?.close()
        identity.completeExceptionally(IOException("bot_runtime_closed"))
        offers.values.forEach { it.completeExceptionally(IOException("bot_runtime_closed")) }; offers.clear()
        modelHost?.close(); channels?.close(); broker?.close()
        if (cleanupOffered.compareAndSet(false, true)) cleaners.execute {
            try {
                if (!startDone.await(25, TimeUnit.SECONDS)) throw IOException()
                val target = peer
                if (target != null) try { send(target, BotNodeWire.STOP) } catch (_: Exception) { }
                if (bound) { context.unbindService(connection); bound = false }
                if (target != null) dead.get(10, TimeUnit.SECONDS)
                else if (connected.isDone && !connected.isCompletedExceptionally) throw IOException()
                snapshotHost?.close()
                fileHost?.closeAsync()?.get(10, TimeUnit.SECONDS)
                modelHost?.close(); channels?.close(); broker?.close()
                database?.close()
                if (target != null) try { target.unlinkToDeath(death, 0) } catch (_: Exception) { }
                handlerThread.quitSafely()
                cleanup.complete(Unit)
            } catch (_: Exception) { cleanup.completeExceptionally(IOException("bot_runtime_cleanup_unverified")) }
        }
        changed()
        val view = CompletableFuture<Unit>()
        cleanup.whenComplete { _, error -> if (error == null) view.complete(Unit) else view.completeExceptionally(error) }
        return view
    }
    override fun toString() = "BotRuntimeExecution(REDACTED)"
    companion object {
        private val starter = Executors.newSingleThreadExecutor()
        private val cleaners = Executors.newSingleThreadExecutor()
        private val deadlines = Executors.newSingleThreadScheduledExecutor()
        private val failureCodes = setOf("bot_bootstrap_failed", "bot_assets_failed", "bot_filesystem_failed", "bot_runtime_import_failed",
            "bot_runtime_start_failed", "bot_runtime_loop_failed", "bot_runtime_exited", "bot_runtime_disconnected",
            "bot_runtime_assets_unavailable", "bot_runtime_start_timeout", "bot_runtime_health_failed")
    }
}
