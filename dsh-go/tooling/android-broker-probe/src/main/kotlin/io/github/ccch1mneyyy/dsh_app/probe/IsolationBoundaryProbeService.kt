// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.probe

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Parcel
import io.github.ccch1mneyyy.dsh_app.isolation.ModelRequestWire
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import io.github.ccch1mneyyy.dsh_app.isolation.ModelRequestClient
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure

/** Test-target-only OS probe. Never included in the product app. No shell,
 * arbitrary network address, file contents or credentials are returned by the
 * boundary check. The channel check reads only an already-connected FD. */
class IsolationBoundaryProbeService : Service() {
    companion object {
        const val CHECK_BOUNDARY = 1
        const val READ_CHANNEL = 2
        const val IDENTITY = 3
        const val REQUEST_MODEL = 4
        const val CHECK_DATA_FD = 5
    }
    private val channels = ConcurrentHashMap.newKeySet<ParcelFileDescriptor>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(4))
    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper(), Handler.Callback { message ->
            if (message.sendingUid == applicationInfo.uid) accept(message)
            true
        }))
    }
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        channels.forEach { try { it.close() } catch (_: Exception) { } }
        workers.shutdownNow()
        super.onDestroy()
    }
    private fun accept(message: Message) {
        // Android recycles Message once this handler returns.
        val what = message.what
        val data = message.data
        val id = data.getString("id") ?: return
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return
        val reply = message.replyTo ?: return
        val channel = if (Build.VERSION.SDK_INT >= 33) data.getParcelable("channel", ParcelFileDescriptor::class.java)
            else { @Suppress("DEPRECATION") (data.getParcelable("channel") as? ParcelFileDescriptor) }
        channel?.let { channels.add(it) }
        try {
            workers.execute {
                val result = try {
                    when (what) {
                        CHECK_BOUNDARY -> boundary(data)
                        READ_CHANNEL -> readChannel(data, channel)
                        IDENTITY -> Bundle().apply { putBoolean("ok", true) }
                        REQUEST_MODEL -> requestModel(data, id)
                        CHECK_DATA_FD -> checkDataFd(data, channel)
                        else -> Bundle().apply { putBoolean("ok", false) }
                    }
                } catch (_: Exception) { Bundle().apply { putBoolean("ok", false) } }
                finally {
                    channel?.let { channels.remove(it); try { it.close() } catch (_: Exception) { } }
                }
                val response = Message.obtain(null, what)
                response.data = result.apply { putString("id", id) }
                try { reply.send(response) } catch (_: Exception) { }
            }
        } catch (_: RuntimeException) {
            channel?.let { channels.remove(it); try { it.close() } catch (_: Exception) { } }
        }
    }
    private fun checkDataFd(data: Bundle, channel: ParcelFileDescriptor?): Bundle {
        if (channel == null) return Bundle()
        val operation = data.getString("operation") ?: return Bundle()
        val path = data.getString("path") ?: return Bundle()
        if (path.length > 4096) return Bundle()
        val denied = try { File(path).inputStream().use { it.read() }; false } catch (_: Exception) { true }
        var errno = 0
        val permitted = try { DataFileFdProbe.perform(channel.fileDescriptor, operation); true }
            catch (error: android.system.ErrnoException) { errno = error.errno; false }
        return Bundle().apply {
            putBoolean("ok", true); putBoolean("permitted", permitted); putInt("errno", errno)
            putBoolean("path_denied", denied)
        }
    }
    private fun requestModel(data: Bundle, id: String): Bundle {
        val host = data.getBinder("model_host") ?: return Bundle()
        val session = data.getString("model_session") ?: return Bundle()
        var body = data.getString("model_body") ?: return Bundle()
        if (data.getBoolean("large_body")) body = JSONObject(body).put("messages", JSONArray().put(
            JSONObject().put("role", "user").put("content", "large-".repeat(14_000)))).toString()
        if (data.getBoolean("oversize_body")) body = "x".repeat(ModelRequestWire.MAX_BODY_BYTES + 1)
        val client = ModelRequestClient(host, session)
        return try {
            if (data.getBoolean("stall_body")) return stalledBody(host, session, id, data.getBoolean("cancel_during_body"))
            if (data.getBoolean("cancel_before_open")) client.cancel(id)
            client.open(id, body).use { fd -> readChannel(Bundle(), fd) }
        } catch (error: ModelBrokerFailure) {
            Bundle().apply { putBoolean("ok", false); putString("code", error.code.wire) }
        }
    }
    private fun stalledBody(host: IBinder, session: String, id: String, cancel: Boolean): Bundle {
        val pipe = ParcelFileDescriptor.createPipe()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ModelRequestWire.DESCRIPTOR)
            data.writeString(session); data.writeString(id); data.writeInt(10)
            pipe[0].writeToParcel(data, 0)
            val cancellation = if (cancel) Thread {
                Thread.sleep(100)
                try { ModelRequestClient(host, session).cancel(id) } catch (_: Exception) { }
            }.apply { isDaemon = true; start() } else null
            host.transact(ModelRequestWire.OPEN_FD, data, reply, 0)
            cancellation?.join(2_000)
            return Bundle().apply { putBoolean("ok", false); putString("code", reply.readString()) }
        } finally { pipe.forEach { it.close() }; data.recycle(); reply.recycle() }
    }
    private fun boundary(data: Bundle): Bundle {
        val path = data.getString("path") ?: return Bundle()
        val port = data.getInt("port", -1)
        if (path.length > 4096 || port !in 1..65535) return Bundle()
        val fileDenied = try { File(path).inputStream().use { it.read() }; false } catch (_: Exception) { true }
        val networkDenied = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }; false
        } catch (_: Exception) { true }
        return Bundle().apply {
            putBoolean("ok", true)
            putBoolean("private_file_denied", fileDenied)
            putBoolean("internet_permission_denied", checkSelfPermission("android.permission.INTERNET") != PackageManager.PERMISSION_GRANTED)
            putBoolean("loopback_connect_denied", networkDenied)
        }
    }
    private fun readChannel(data: Bundle, channel: ParcelFileDescriptor?): Bundle {
        if (channel == null) return Bundle()
        // Socket options belong to the native owner. SELinux may permit read
        // on a transferred Unix FD while refusing setopt on that same FD.
        val prefixOnly = data.getBoolean("prefix_only")
        val output = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(channel).use { input ->
            if (prefixOnly) {
                val byte = input.read()
                if (byte < 0) return Bundle()
                output.write(byte)
            } else {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 64 * 1024) return Bundle()
                    output.write(buffer, 0, count)
                }
            }
        }
        return Bundle().apply {
            putBoolean("ok", true)
            putString("text", output.toString("UTF-8"))
        }
    }
}
