// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Host-only launch capability. Contains no provider configuration or API key.
 * Never publish this native session token to an Agent/Flutter settings channel. */
internal class NativeBrokerSession(val baseUrl: String, val token: String) {
    override fun toString() = "NativeBrokerSession(REDACTED)"
}

/** Native process only. The injected resolver is a Keystore-backed store in
 * production, never a runtime payload. No file/command/key-getter HTTP routes. */
internal class NativeModelBroker(
    private val resolveProvider: (String, String) -> NativeProvider,
    private val transport: ProviderTransport = OkHttpProviderTransport(),
    private val headerTimeoutMs: Long = 10_000,
    private val streamTimeoutMs: Long = 10 * 60_000,
    private val logger: (ModelBrokerCode) -> Unit = {},
) : Closeable {
    init { require(headerTimeoutMs in 1..60_000 && streamTimeoutMs in 1..10 * 60_000) }
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val streams = ConcurrentHashMap<Socket, StreamCancellation>()
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mbot-model-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "mbot-native-model").apply { isDaemon = true } })
    val session = NativeBrokerSession("http://127.0.0.1:${listener.localPort}",
        ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) })

    init {
        Thread({
            while (!closed.get()) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                var headerDeadline: ScheduledFuture<*>? = null
                try {
                    val deadline = deadlines.schedule({ expire(socket) }, headerTimeoutMs, TimeUnit.MILLISECONDS)
                    headerDeadline = deadline
                    workers.execute { serve(socket, deadline) }
                } catch (_: Exception) {
                    headerDeadline?.cancel(false)
                    try { reply(socket, 503, "broker_unavailable") } catch (_: Exception) { }
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }, "mbot-native-model-accept").apply { isDaemon = true; start() }
    }

    private fun serve(socket: Socket, headerDeadline: ScheduledFuture<*>) {
        var streaming = false
        var streamDeadline: ScheduledFuture<*>? = null
        val cancellation = StreamCancellation()
        try {
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val first = readLine(input, 4096) ?: return
            if (first != "POST /v1/model/stream HTTP/1.1") {
                reply(socket, 404, "not_found"); return
            }
            val headers = linkedMapOf<String, String>()
            var headerBytes = first.length
            while (true) {
                val line = readLine(input, 4096) ?: throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                headerBytes += line.length + 2
                if (headerBytes > 16 * 1024) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon < 1) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                val name = line.substring(0, colon).lowercase(Locale.ROOT)
                if (!name.matches(Regex("[a-z0-9-]+")) || headers.containsKey(name)) {
                    throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                }
                headers[name] = line.substring(colon + 1).trim()
            }
            if (headers.containsKey("origin") || headers["host"] != "127.0.0.1:${listener.localPort}") {
                reply(socket, 403, "forbidden"); return
            }
            if (!MessageDigest.isEqual(("Bearer " + session.token).toByteArray(UTF_8),
                    (headers["authorization"] ?: "").toByteArray(UTF_8))) {
                reply(socket, 401, "unauthorized"); return
            }
            val length = headers["content-length"]
            if (headers.containsKey("transfer-encoding") || length == null || !length.matches(Regex("[0-9]{1,7}")) ||
                length.toInt() !in 1..1024 * 1024 ||
                headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
                throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
            }
            val bytes = ByteArray(length.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                offset += count
            }
            val request = parseRequest(String(bytes, UTF_8))
            headerDeadline.cancel(false)
            // A closed downstream must cancel even when upstream emits nothing
            // and its reader is blocked. Connection: close forbids pipelining.
            streams[socket] = cancellation
            streamDeadline = deadlines.schedule({ expire(socket) }, streamTimeoutMs, TimeUnit.MILLISECONDS)
            socket.soTimeout = 0
            Thread({
                try { input.read() } catch (_: Exception) { }
                cancellation.cancel()
            }, "mbot-native-model-peer").apply { isDaemon = true; start() }
            if (cancellation.cancelled.get()) return
            val provider = resolveProvider(request.providerId, request.modelId)
            val upstream = if (request.payload != null) provider.preparePayload(request.modelId, request.payload)
                else provider.prepare(request.modelId, request.messages!!)
            val call = transport.open(upstream)
            cancellation.attach(call)
            if (cancellation.cancelled.get()) return
            socket.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                    "X-Mbot-Broker-Protocol: 1\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(UTF_8))
                flush()
            }
            streaming = true
            call.use {
                val response = call.execute()
                if (response.status !in 200..299) throw ModelBrokerFailure(ModelBrokerCode.PROVIDER_REJECTED)
                if (response.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) != "text/event-stream") {
                    throw ModelBrokerFailure(ModelBrokerCode.INVALID_RESPONSE)
                }
                response.body.bufferedReader(UTF_8).use { reader ->
                    ProviderSse.copy(reader, provider) { json ->
                        if (cancellation.cancelled.get()) throw ModelBrokerFailure(ModelBrokerCode.CANCELLED)
                        event(socket, json)
                    }
                }
                if (!cancellation.cancelled.get()) event(socket, "[DONE]")
            }
        } catch (failure: Exception) {
            val code = if (cancellation.cancelled.get()) ModelBrokerCode.CANCELLED
                else (failure as? ModelBrokerFailure)?.code ?: ModelBrokerCode.REQUEST_FAILED
            // Only closed enum codes reach logs. Never a Throwable, URI, body,
            // request headers, descriptor, model text or native credentials.
            try { logger(code) } catch (_: Exception) { }
            if (!cancellation.cancelled.get()) {
                try {
                    if (streaming) {
                        event(socket, JSONObject().put("error", code.wire).toString())
                        event(socket, "[DONE]")
                    } else reply(socket, 400, code.wire)
                } catch (_: Exception) { }
            }
        } finally {
            headerDeadline.cancel(false)
            streamDeadline?.cancel(false)
            cancellation.cancel()
            streams.remove(socket)
            sockets.remove(socket)
            try { socket.close() } catch (_: Exception) { }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.close()
        deadlines.shutdownNow()
        streams.values.forEach { it.cancel() }
        sockets.forEach { try { it.close() } catch (_: Exception) { } }
        workers.shutdownNow()
        (transport as? Closeable)?.close()
    }

    private fun expire(socket: Socket) {
        streams[socket]?.cancel()
        try { socket.close() } catch (_: Exception) { }
    }

    private class StreamCancellation {
        val cancelled = AtomicBoolean()
        @Volatile private var call: ProviderCall? = null
        fun attach(value: ProviderCall) { call = value; if (cancelled.get()) value.cancel() }
        fun cancel() { cancelled.set(true); call?.cancel() }
    }

    private class Input(val providerId: String, val modelId: String, val messages: JSONArray?, val payload: JSONObject?)
    private fun parseRequest(raw: String): Input {
        try {
            requireNativeJsonBudget(raw, ModelBrokerCode.INVALID_REQUEST)
            val tokener = JSONTokener(raw)
            val value = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException()
            val keys = value.keys().asSequence().toSet()
            val text = keys == setOf("providerId", "modelId", "messages")
            val payload = keys == setOf("providerId", "modelId", "request")
            if (tokener.nextClean().code != 0 || (!text && !payload)) {
                throw IllegalArgumentException()
            }
            val provider = value.get("providerId") as? String ?: throw IllegalArgumentException()
            val model = value.get("modelId") as? String ?: throw IllegalArgumentException()
            if (provider.isBlank() || provider.length > 200 || model.isBlank() || model.length > 256) throw IllegalArgumentException()
            if (payload) return Input(provider, model, null, value.getJSONObject("request"))
            val messages = value.getJSONArray("messages")
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                if (message.keys().asSequence().toSet() != setOf("role", "content") ||
                    message.get("role") !in setOf("system", "user", "assistant") || message.get("content") !is String) {
                    throw IllegalArgumentException()
                }
            }
            return Input(provider, model, messages, null)
        } catch (_: Exception) { throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST) }
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val ch = input.read()
            if (ch < 0) return null
            if (ch == 10) return bytes.toString("UTF-8").removeSuffix("\r")
            if (bytes.size() >= max) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
            bytes.write(ch)
        }
    }
    private fun event(socket: Socket, json: String) {
        socket.getOutputStream().apply { write("data: $json\n\n".toByteArray(UTF_8)); flush() }
    }
    private fun reply(socket: Socket, status: Int, code: String) {
        val body = code.toByteArray(UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status Rejected\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(UTF_8))
            write(body); flush()
        }
    }
}
