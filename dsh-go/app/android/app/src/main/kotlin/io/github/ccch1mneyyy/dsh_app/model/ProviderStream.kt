// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import java.io.Closeable
import java.io.InputStream
import java.io.Reader
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

internal interface ProviderCall : Closeable {
    fun execute(): ProviderResponse
    fun cancel()
}

internal class ProviderResponse(val status: Int, val contentType: String?, val body: InputStream)

internal fun interface ProviderTransport {
    fun open(request: NativeModelRequest): ProviderCall
}

/** No interceptors, cookie persistence, cache, redirects, authentication
 * callbacks or retries. Cancellation closes the actual upstream socket. */
internal class OkHttpProviderTransport : ProviderTransport, Closeable {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE).cache(null)
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()

    override fun open(request: NativeModelRequest): ProviderCall {
        if (!request.url.startsWith("https://")) throw ModelBrokerFailure(ModelBrokerCode.INVALID_ENDPOINT)
        val builder = Request.Builder().url(request.url)
            .post(request.body.toRequestBody("application/json".toMediaType()))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        return OkHttpProviderCall(client.newCall(builder.build()))
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

internal class OkHttpProviderCall(private val call: Call) : ProviderCall {
    private var response: okhttp3.Response? = null
    override fun execute(): ProviderResponse {
        val value = call.execute()
        response = value
        val body = value.body ?: throw ModelBrokerFailure(ModelBrokerCode.INVALID_RESPONSE)
        return ProviderResponse(value.code, value.header("Content-Type"), body.byteStream())
    }
    override fun cancel() = call.cancel()
    override fun close() { call.cancel(); response?.close() }
    override fun toString() = "ProviderCall(REDACTED)"
}

/** Reassemble complete SSE data events before forwarding. A key echoed by the
 * provider, including JSON unicode escapes or TCP-split strings, must not cross
 * the native boundary. Error bodies/headers/exceptions are never forwarded. */
internal object ProviderSse {
    private const val MAX_EVENT_CHARS = 256 * 1024
    fun copy(reader: Reader, provider: NativeProvider, emit: (String) -> Unit) {
        val data = StringBuilder()
        var terminal = false
        while (true) {
            val line = line(reader)
            if (line == null || line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val raw = data.toString().trimEnd('\n')
                    data.setLength(0)
                    if (raw == "[DONE]") return
                    requireNativeJsonBudget(raw, ModelBrokerCode.INVALID_RESPONSE)
                    val event = try { JSONObject(raw) } catch (_: Exception) {
                        throw ModelBrokerFailure(ModelBrokerCode.INVALID_RESPONSE)
                    }
                    provider.guardResponse(event)
                    val type = event.optString("type")
                    if (event.has("error") || type in setOf("error", "response.failed", "response.incomplete")) {
                        throw ModelBrokerFailure(ModelBrokerCode.PROVIDER_REJECTED)
                    }
                    emit(event.toString())
                    terminal = terminal || when (provider.apiFormat) {
                        "anthropic-messages" -> type == "message_stop"
                        "openai-responses" -> type == "response.completed"
                        "openai-completions" -> event.optJSONArray("choices")?.let { choices ->
                            (0 until choices.length()).any {
                                (choices.getJSONObject(it).opt("finish_reason") as? String)?.isNotBlank() == true
                            }
                        } == true
                        "google-generative-ai" -> event.optJSONArray("candidates")?.let { candidates ->
                            (0 until candidates.length()).any { candidates.getJSONObject(it).optString("finishReason").isNotEmpty() }
                        } == true
                        else -> false
                    }
                }
                if (line == null) {
                    if (!terminal) throw ModelBrokerFailure(ModelBrokerCode.INVALID_RESPONSE)
                    return
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).removePrefix(" ")).append('\n')
                if (data.length > MAX_EVENT_CHARS) throw ModelBrokerFailure(ModelBrokerCode.INVALID_RESPONSE)
            }
        }
    }

    private fun line(reader: Reader): String? {
        val line = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch < 0) return if (line.isEmpty()) null else line.toString().removeSuffix("\r")
            if (ch == 10) return line.toString().removeSuffix("\r")
            line.append(ch.toChar())
            if (line.length > MAX_EVENT_CHARS) throw ModelBrokerFailure(ModelBrokerCode.INVALID_RESPONSE)
        }
    }
}
