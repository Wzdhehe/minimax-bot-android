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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object NodeProbeRuntime {
    @Volatile private var modelClient: ModelRequestClient? = null
    fun configureModelBroker(host: IBinder, session: String) { modelClient = ModelRequestClient(host, session) }
    fun openModelFd(id: String, body: String): Int = try { modelClient?.open(id, body)?.detachFd() ?: -1 } catch (_: Exception) { -1 }
    fun cancelModelFd(id: String) { try { modelClient?.cancel(id) } catch (_: Exception) { } }
    fun load() { System.loadLibrary("mbot_node_probe") }
    external fun start(script: String, normal: Int, stalled: Int, echo: Int, result: Int, privatePath: String, nativeLibraryDir: String, providerId: String): Int
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
                else if (message.what == 3) {
                    val response = Message.obtain(null, 3)
                    response.data = Bundle().apply { putString("code", "ready") }
                    try { message.replyTo?.send(response) } catch (_: Exception) { }
                }
            }
            true
        }))
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
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
        val fds = listOf("normal", "stalled", "echo", "result").map { descriptor(data, it) }
        val privatePath = data.getString("privatePath")
        val host = data.getBinder("model_host")
        val session = data.getString("model_session")
        val providerId = data.getString("providerId")
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,128}")) || fds.any { it == null } ||
            privatePath == null || privatePath.length > 4096 || host == null || session == null ||
            !session.matches(Regex("[0-9a-f]{64}")) || providerId == null || providerId.length !in 1..200 || !started.compareAndSet(false, true)) {
            fds.forEach { try { it?.close() } catch (_: Exception) { } }
            return
        }
        worker.execute {
            var code = "node_start_failed"
            try {
                val script = assets.open("node-fd-probe.cjs").bufferedReader(Charsets.UTF_8).use { it.readText() }
                NodeProbeRuntime.configureModelBroker(host, session)
                NodeProbeRuntime.load()
                // Ownership goes to the fixed Node script. It closes model
                // FDs on every stream path and the result FD after writing.
                val raw = fds.map { it!!.detachFd() }
                val exit = NodeProbeRuntime.start(script, raw[0], raw[1], raw[2], raw[3], privatePath, applicationInfo.nativeLibraryDir, providerId)
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
