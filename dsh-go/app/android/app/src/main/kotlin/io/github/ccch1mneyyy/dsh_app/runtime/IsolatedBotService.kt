// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.ccch1mneyyy.dsh_app.isolation.GrantedConnectionQueue
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object BotNodeWire {
    const val HELLO = 1
    const val START = 2
    const val READY = 3
    const val CONNECTION = 4
    const val STOP = 5
    const val FAILED = 6
    const val IDENTITY = 7
}

/** Reads only admitted immutable code assets from this signed APK. Never a
 * Bundle-supplied filename, user-data path or provider credential. */
internal class BotCodeAssets(private val context: Context) {
    private val manifest: JSONObject
    val abi: String
    init {
        val bytes = context.assets.open("mbot/product-manifest.json").use { stream ->
            val result = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (result.size() + count > 64 * 1024) throw IOException("bot_assets_invalid")
                result.write(chunk, 0, count)
            }
            result.toByteArray()
        }
        manifest = JSONObject(String(bytes, Charsets.UTF_8))
        if (manifest.getString("pinCommit") != "97818b2890b4acae1c6bd1200a226ff506680628") throw IOException("bot_assets_invalid")
        abi = manifest.getString("abi")
        if (abi !in listOf("x86_64", "arm64-v8a") || abi !in Build.SUPPORTED_ABIS) throw IOException("bot_architecture_unavailable")
        val files = manifest.getJSONObject("files")
        if (files.keys().asSequence().toSet() != names.keys) throw IOException("bot_assets_invalid")
    }
    fun read(name: String): ByteArray {
        val path = names[name] ?: throw IOException("bot_assets_invalid")
        val entry = manifest.getJSONObject("files").getJSONObject(name)
        val size = entry.getInt("bytes")
        val expected = entry.getString("sha256")
        if (entry.getString("path") != path || size !in 1..64 * 1024 * 1024 || !expected.matches(Regex("[0-9a-f]{64}"))) throw IOException("bot_assets_invalid")
        val bytes = ByteArray(size)
        context.assets.open("mbot/$path").use { stream ->
            var offset = 0
            while (offset < size) {
                val count = stream.read(bytes, offset, size - offset)
                if (count <= 0) throw IOException("bot_assets_invalid")
                offset += count
            }
            if (stream.read() != -1) throw IOException("bot_assets_invalid")
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != expected) throw IOException("bot_assets_invalid")
        return bytes
    }
    companion object {
        private val names = mapOf("bootstrap" to "bootstrap.cjs", "runtime" to "runtime.mjs", "filesystem" to "filesystem.mjs",
            "resources" to "resources.json", "sqlite-wrapper" to "sqlite-wrapper.cjs")
    }
}

/** Product execution process. Every role/session check uses framework UID;
 * the only program is the APK bootstrap. A stopped VM is never restarted. */
class IsolatedBotService : Service() {
    private val started = AtomicBoolean()
    private val stopping = AtomicBoolean()
    private val acceptingConnections = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor()
    private var session: String? = null
    private var owner: IBinder? = null
    private val ownerDeath = IBinder.DeathRecipient { retire() }
    private val main = Handler(Looper.getMainLooper())
    private val hardStop = Runnable { retire() }
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper(), Handler.Callback { message ->
        if (Process.myUid() == applicationInfo.uid) { stopSelf(); return@Callback true }
        if (message.what == BotNodeWire.CONNECTION) receiveConnection(message)
        else if (message.sendingUid == applicationInfo.uid) {
            val nonce = try { message.data.getString("session") } catch (_: Exception) { null }
            if (nonce != null && nonce.matches(Regex("[0-9a-f]{64}"))) when (message.what) {
                BotNodeWire.HELLO -> if (session == null || session == nonce) {
                    session = nonce
                    send(message.replyTo, BotNodeWire.IDENTITY, nonce)
                }
                BotNodeWire.START -> if (session == nonce) start(message, nonce)
                BotNodeWire.STOP -> if (session == nonce) {
                    stopping.set(true); acceptingConnections.set(false)
                    EmbeddedBotNode.stop()
                    main.postDelayed(hardStop, 5000) // ACK/graceful return is not retirement evidence.
                }
            }
        }
        true
    })) }
    override fun onCreate() {
        super.onCreate()
        if (Process.myUid() == applicationInfo.uid) { stopSelf(); return }
        if (!created.compareAndSet(false, true)) { retire(); return }
        processAnchor = messenger.binder // Retained until actual process death.
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() { retire(); super.onDestroy() }
    private fun descriptor(data: Bundle, key: String): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= 33) data.getParcelable(key, ParcelFileDescriptor::class.java)
        else { @Suppress("DEPRECATION") (data.getParcelable(key) as? ParcelFileDescriptor) }
    private fun send(reply: Messenger?, what: Int, nonce: String, code: String? = null, id: String? = null): Boolean = try {
        if (reply == null) false else {
            reply.send(Message.obtain(null, what).apply { data = Bundle().apply {
                putString("session", nonce); code?.let { putString("code", it) }; id?.let { putString("id", it) }
            } }); true
        }
    } catch (_: Exception) { false }
    private fun receiveConnection(message: Message) {
        val file = try { descriptor(message.data, "connection") } catch (_: Exception) { null }
        if (message.sendingUid != applicationInfo.uid) { try { file?.close() } catch (_: Exception) { }; return }
        val nonce = message.data.getString("session")
        val id = message.data.getString("id")
        val valid = nonce == session && id != null && id.matches(Regex("[a-zA-Z0-9_-]{1,128}")) && file != null
        if (!valid) { try { file?.close() } catch (_: Exception) { }; return }
        val offer = if (!stopping.get() && acceptingConnections.get()) EmbeddedBotNode.offerBotConnection(file)
            else { file.close(); GrantedConnectionQueue.Offer.CLOSED }
        send(message.replyTo, BotNodeWire.CONNECTION, nonce!!, if (offer == GrantedConnectionQueue.Offer.ACCEPTED) "ok" else "unavailable", id)
    }
    private fun start(message: Message, nonce: String) {
        val data = message.data
        val files = listOf("descriptor", "db", "wal", "shm", "journal").map { try { descriptor(data, it) } catch (_: Exception) { null } }
        val model = data.getBinder("model_host"); val modelSession = data.getString("model_session")
        val fileHost = data.getBinder("file_host"); val fileSession = data.getString("file_session")
        val snapshots = data.getBinder("snapshot_host"); val snapshotSession = data.getString("snapshot_session")
        val reply = message.replyTo
        if (files.any { it == null } || model == null || fileHost == null || snapshots == null || reply == null ||
            listOf(modelSession, fileSession, snapshotSession).any { it == null || !it.matches(Regex("[0-9a-f]{64}")) } ||
            stopping.get() || !started.compareAndSet(false, true)) {
            files.forEach { try { it?.close() } catch (_: Exception) { } }; return
        }
        owner = reply.binder
        try { reply.binder.linkToDeath(ownerDeath, 0) } catch (_: Exception) { files.forEach { it?.close() }; retire(); return }
        worker.execute {
            try {
                val assets = BotCodeAssets(this)
                EmbeddedBotNode.configure(this, model, modelSession!!, fileHost, fileSession!!, snapshots, snapshotSession!!,
                    assets::read,
                    ready = {
                        if (stopping.get()) false else {
                            acceptingConnections.set(true)
                            send(reply, BotNodeWire.READY, nonce)
                        }
                    }, failed = { phase ->
                        val safe = phase.takeIf { it in setOf("bootstrap", "assets", "filesystem", "runtime_import", "runtime_start", "runtime_loop") } ?: "bootstrap"
                        send(reply, BotNodeWire.FAILED, nonce, "bot_${safe}_failed")
                    })
                EmbeddedBotNode.load()
                val raw = files.map { it!!.detachFd() }
                val result = EmbeddedBotNode.start(String(assets.read("bootstrap"), Charsets.UTF_8), raw[0], applicationInfo.uid,
                    applicationInfo.sourceDir + "!/lib/" + assets.abi, raw.drop(1).toIntArray())
                if (!stopping.get() || result != 0) send(reply, BotNodeWire.FAILED, nonce, "bot_runtime_exited")
            } catch (_: Throwable) { send(reply, BotNodeWire.FAILED, nonce, "bot_runtime_start_failed") }
            finally { files.forEach { try { it?.close() } catch (_: Exception) { } }; retire() }
        }
    }
    private fun retire() {
        stopping.set(true); acceptingConnections.set(false)
        EmbeddedBotNode.stop()
        main.removeCallbacks(hardStop)
        if (Process.myUid() != applicationInfo.uid) Process.killProcess(Process.myPid()) else stopSelf()
    }
    private companion object {
        val created = AtomicBoolean()
        @Volatile var processAnchor: IBinder? = null
    }
}
