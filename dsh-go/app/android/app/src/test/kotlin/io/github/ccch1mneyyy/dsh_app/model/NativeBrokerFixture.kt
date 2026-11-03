// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

/** JVM-only native process fixture. The fake key is generated here, never
 * passed in environment/argv/stdout, and never given to the Node test runner.
 * Only the Keystore resolver and remote HTTPS endpoint are substituted; the
 * native HTTP server, SSE guard and OkHttp cancellation are production code. */
object NativeBrokerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val artifacts = File(args.single()).canonicalFile
        val key = "fixture-only-${UUID.randomUUID()}"
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val injected = AtomicInteger()
        val cancelClosed = AtomicBoolean()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
        val upstream = MockWebServer()
        upstream.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                check(request.getHeader("Authorization") == "Bearer $key") { "native_auth_missing" }
                injected.incrementAndGet()
                val body = JSONObject(request.body.readUtf8())
                val model = body.getString("model")
                val response = MockResponse().setHeader("Content-Type", "text/event-stream")
                return when (model) {
                    "tools" -> {
                        check(body.getBoolean("stream") && body.getJSONArray("messages").getJSONObject(0).getString("role") == "tool" &&
                            body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name") == "lookup") { "tool_contract_missing" }
                        val event = JSONObject("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c2","type":"function","function":{"name":"lookup","arguments":"{\"path\":\"notes/b\"}"}}]}}]}""")
                        response.setChunkedBody("data: $event\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n", 3)
                    }
                    "error" -> response.setResponseCode(401).setBody("provider denied $key")
                    "redirect" -> response.setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/?key=$key")
                    "echo" -> response.setChunkedBody("data: {\"delta\":\"$key\"}\n\n", 3)
                    "escaped" -> response.setChunkedBody("data: {\"delta\":\"${key.map { "\\u%04x".format(it.code) }.joinToString("")}\"}\n\n", 3)
                    "cancel" -> response.setChunkedBody("data: {\"delta\":\"first\"}\n\n" +
                        "data: {\"delta\":\"later\"}\n\n".repeat(1000), 32).throttleBody(32, 100, TimeUnit.MILLISECONDS)
                    "truncated" -> response.setBody("data: {\"delta\":\"partial\"}\n\n")
                    else -> response.setChunkedBody("data: {\"delta\":\"hello\"}\r\n\r\n" +
                        "data: {\"delta\":\" world\"}\n\ndata: [DONE]\n\n", 3)
                }
            }
        }
        upstream.start()
        val transport = ProviderTransport { native ->
            val model = JSONObject(native.body).getString("model")
            if (model == "throw") throw IllegalStateException("fake provider exception $key")
            val request = Request.Builder().url(upstream.url("/v1/chat/completions"))
                .post(native.body.toRequestBody("application/json".toMediaType()))
            native.headers.forEach { (name, value) -> request.header(name, value) }
            val call = OkHttpProviderCall(client.newCall(request.build()))
            object : ProviderCall {
                override fun execute() = call.execute()
                override fun cancel() = call.cancel()
                override fun close() { call.close(); if (model == "cancel") cancelClosed.set(true) }
            }
        }
        val allowed = setOf("stream", "cancel", "error", "echo", "escaped", "throw", "redirect", "truncated", "tools")
        val provider = NativeProvider("openai-completions", "https://fixture-provider.invalid/v1", key)
        val broker = NativeModelBroker({ id, model ->
            if (id != "native-provider" || model !in allowed) throw ModelBrokerFailure(ModelBrokerCode.INVALID_PROVIDER)
            provider
        }, transport) { code -> logs.add(code.wire) }
        try {
            println(JSONObject().put("baseUrl", broker.session.baseUrl).put("token", broker.session.token))
            System.out.flush()
            for (command in generateSequence(::readlnOrNull)) {
                if (command == "stop") break
                if (command != "report") continue
                val filesSafe = artifacts.walkTopDown().filter { it.isFile }.all { !it.readText().contains(key) }
                println(JSONObject().put("artifactsSafe", filesSafe).put("logsSafe", logs.none { it.contains(key) })
                    .put("keyInjected", injected.get() > 0).put("cancelClosed", cancelClosed.get())
                    .put("nativeSurfacesSafe", !provider.toString().contains(key) && !broker.session.toString().contains(key)))
                System.out.flush()
            }
        } finally {
            broker.close(); client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow(); upstream.shutdown()
        }
    }
}
