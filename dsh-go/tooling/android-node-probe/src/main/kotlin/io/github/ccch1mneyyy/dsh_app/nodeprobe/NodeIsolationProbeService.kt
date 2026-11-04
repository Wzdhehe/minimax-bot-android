// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.app.Service
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
import io.github.ccch1mneyyy.dsh_app.isolation.ModelRequestClient
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDatabaseFiles
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeAtomicPublisher
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitClient
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDeletionResult
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimePublicationResult
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataFailure
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataCode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object NodeProbeRuntime {
    @Volatile private var modelClient: ModelRequestClient? = null
    @Volatile private var fileClient: RuntimeFileCommitClient? = null
    fun configureModelBroker(host: IBinder, session: String) { modelClient = ModelRequestClient(host, session) }
    fun configureFileBroker(host: IBinder, session: String) { fileClient = RuntimeFileCommitClient(host, session) }
    private fun fileError(code: RuntimeDataCode): Int = when (code) {
        RuntimeDataCode.INVALID -> 3; RuntimeDataCode.DENIED -> 4; RuntimeDataCode.CLOSED -> 5
        RuntimeDataCode.NOT_FOUND -> 7; RuntimeDataCode.TOO_LARGE -> 8; else -> 6
    }
    fun readRuntimeFile(name: String): ByteArray {
        val client = fileClient ?: return byteArrayOf(6)
        return try {
            val bytes = client.read(name)
            ByteArray(bytes.size + 1).also { bytes.copyInto(it, 1) }
        } catch (error: RuntimeDataFailure) { byteArrayOf(fileError(error.code).toByte()) }
        catch (_: Exception) { byteArrayOf(6) }
    }
    fun sizeRuntimeFile(name: String): Long {
        val client = fileClient ?: return -6L
        return try { client.size(name) }
        catch (error: RuntimeDataFailure) { -fileError(error.code).toLong() }
        catch (_: Exception) { -6L }
    }
    fun commitRuntimeFile(name: String, bytes: ByteArray, replace: Boolean): Int {
        val client = fileClient ?: return 6
        return try {
            when (if (replace) client.replace(name, bytes) else client.publishIfAbsent(name, bytes)) {
                RuntimePublicationResult.PUBLISHED -> 0
                RuntimePublicationResult.ALREADY_EXISTS -> 1
                RuntimePublicationResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) {
            fileError(error.code)
        } catch (_: Exception) { 2 }
    }
    fun appendRuntimeFile(name: String, bytes: ByteArray): Int {
        val client = fileClient ?: return 6
        return try {
            when (client.append(name, bytes)) {
                RuntimePublicationResult.PUBLISHED -> 0
                RuntimePublicationResult.ALREADY_EXISTS -> 2 // Append cannot report an exclusive-create conflict.
                RuntimePublicationResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) { fileError(error.code) }
        catch (_: Exception) { 2 }
    }
    fun moveRuntimeFile(source: String, target: String, replace: Boolean): Int {
        val client = fileClient ?: return 6
        return try {
            when (client.move(source, target, replace)) {
                RuntimePublicationResult.PUBLISHED -> 0
                RuntimePublicationResult.ALREADY_EXISTS -> 1
                RuntimePublicationResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) { fileError(error.code) }
        catch (_: Exception) { 2 }
    }
    fun removeRuntimeFile(name: String): Int {
        val client = fileClient ?: return 6
        return try {
            when (client.remove(name)) {
                RuntimeDeletionResult.REMOVED -> 0
                RuntimeDeletionResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) { fileError(error.code) }
        catch (_: Exception) { 2 }
    }
    fun openModelFd(id: String, body: String): Int = try { modelClient?.open(id, body)?.detachFd() ?: -1 } catch (_: Exception) { -1 }
    fun cancelModelFd(id: String) { try { modelClient?.cancel(id) } catch (_: Exception) { } }
    fun load() { System.loadLibrary("mbot_node_probe") }
    external fun start(script: String, normal: Int, stalled: Int, echo: Int, result: Int, privatePath: String, nativeLibraryDir: String, providerId: String, dataFds: IntArray): Int
}

/** Verification APK only. One fixed APK script per isolated process; no JS,
 * commands, model credentials or endpoint descriptors are accepted over IPC. */
class NodeIsolationProbeService : Service() {
    private val started = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor()
    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper(), Handler.Callback { message ->
            if (message.sendingUid == applicationInfo.uid) {
                if (message.what == 1) runProbe(message)
                else if (message.what in 4..6 && started.compareAndSet(false, true)) {
                    val data = message.data
                    val reply = message.replyTo
                    val operation = message.what
                    worker.execute {
                        val response = Message.obtain(null, 2)
                        response.data = Bundle().apply {
                            putString("id", data.getString("id"))
                            putString("code", when (operation) {
                                5 -> runFileCommitDrainProbe(data)
                                6 -> UnconsumedSnapshotProbe.open(data)
                                else -> runFileCommitProbe(data)
                            })
                        }
                        try { reply?.send(response) } catch (_: Exception) { }
                    }
                }
                else if (message.what == 3) {
                    val response = Message.obtain(null, 3)
                    val denied = try { RuntimeDatabaseFiles.open(this).close(); false }
                        catch (error: RuntimeDataFailure) { error.code == RuntimeDataCode.OWNER_ONLY }
                        catch (_: Exception) { false }
                    val publicationDenied = try { RuntimeAtomicPublisher.open(this, listOf("forbidden")).close(); false }
                        catch (error: RuntimeDataFailure) { error.code == RuntimeDataCode.OWNER_ONLY }
                        catch (_: Exception) { false }
                    response.data = Bundle().apply {
                        putString("code", "ready")
                        putBoolean("database_owner_denied", denied)
                        putBoolean("publication_owner_denied", publicationDenied)
                    }
                    try { message.replyTo?.send(response) } catch (_: Exception) { }
                }
            }
            true
        }))
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        UnconsumedSnapshotProbe.close()
        worker.shutdownNow()
        super.onDestroy()
        // node::Start is one-shot here; do not attempt reinitialization. Only
        // kill this verification service's isolated UID, never the App owner.
        if (Process.myUid() != applicationInfo.uid) Process.killProcess(Process.myPid())
    }
    private fun descriptor(data: Bundle, name: String): ParcelFileDescriptor? =
        if (Build.VERSION.SDK_INT >= 33) data.getParcelable(name, ParcelFileDescriptor::class.java)
        else { @Suppress("DEPRECATION") (data.getParcelable(name) as? ParcelFileDescriptor) }

    private fun runProbe(message: Message) {
        val data = message.data
        val reply = message.replyTo ?: return
        val id = data.getString("id") ?: return
        val fds = listOf("normal", "stalled", "echo", "result", "db", "wal", "shm", "journal").map { descriptor(data, it) }
        val privatePath = data.getString("privatePath")
        val host = data.getBinder("model_host")
        val session = data.getString("model_session")
        val fileHost = data.getBinder("file_host")
        val fileSession = data.getString("file_session")
        val providerId = data.getString("providerId")
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,128}")) || fds.any { it == null } ||
            privatePath == null || privatePath.length > 4096 || host == null || session == null ||
            !session.matches(Regex("[0-9a-f]{64}")) || fileHost == null || fileSession == null || !fileSession.matches(Regex("[0-9a-f]{64}")) ||
            providerId == null || providerId.length !in 1..200 || !started.compareAndSet(false, true)) {
            fds.forEach { try { it?.close() } catch (_: Exception) { } }
            return
        }
        worker.execute {
            var code = "node_start_failed"
            try {
                val script = assets.open("node-fd-probe.cjs").bufferedReader(Charsets.UTF_8).use { it.readText() }
                NodeProbeRuntime.configureModelBroker(host, session)
                NodeProbeRuntime.configureFileBroker(fileHost, fileSession)
                NodeProbeRuntime.load()
                // Ownership goes to the fixed Node script. It closes model
                // FDs on every stream path and the result FD after writing.
                val raw = fds.map { it!!.detachFd() }
                val exit = NodeProbeRuntime.start(script, raw[0], raw[1], raw[2], raw[3], privatePath, applicationInfo.nativeLibraryDir, providerId, raw.drop(4).toIntArray())
                code = if (exit == 0) "ok" else "node_nonzero_exit"
            } catch (_: UnsatisfiedLinkError) { code = "node_library_unavailable" }
            catch (_: Throwable) { code = "node_start_failed" }
            finally { fds.forEach { try { it?.close() } catch (_: Exception) { } } }
            val response = Message.obtain(null, 2)
            response.data = Bundle().apply { putString("id", id); putString("code", code) }
            try { reply.send(response) } catch (_: Exception) { }
        }
    }
}
