// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeModelBrokerTest {
    private val secret = "unit-only-${UUID.randomUUID()}"
    private fun provider(format: String = "openai-completions", base: String = "https://provider.example/v1") =
        NativeProvider(format, base, secret)
    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "Be brief"))
        .put(JSONObject().put("role", "user").put("content", "Hi"))

    @Test fun fourProtocolsInjectOnlyNativeHeaders() {
        val paths = mapOf("openai-completions" to "/chat/completions", "openai-responses" to "/responses",
            "anthropic-messages" to "/messages", "google-generative-ai" to "/models/m1:streamGenerateContent?alt=sse")
        paths.forEach { (format, path) ->
            val request = provider(format).prepare("m1", messages())
            assertEquals("https://provider.example/v1$path", request.url)
            assertFalse(request.url.contains(secret))
            assertFalse(request.body.contains(secret))
            assertFalse(request.toString().contains(secret))
            assertFalse(provider(format).toString().contains(secret))
            val body = JSONObject(request.body)
            when (format) {
                "anthropic-messages" -> {
                    assertEquals(secret, request.headers["x-api-key"])
                    assertEquals("Be brief", body.getString("system"))
                    assertEquals(1, body.getJSONArray("messages").length())
                }
                "google-generative-ai" -> {
                    assertEquals(secret, request.headers["x-goog-api-key"])
                    assertTrue(body.has("systemInstruction"))
                    assertEquals("user", body.getJSONArray("contents").getJSONObject(0).getString("role"))
                }
                else -> assertEquals("Bearer $secret", request.headers["Authorization"])
            }
        }
    }

    @Test fun endpointsCannotPutCredentialsInUrlsOrDowngradeTls() {
        for (url in listOf("http://provider.example/v1", "https://user:pass@provider.example/v1",
            "https://provider.example/v1?key=hidden", "https://provider.example/v1#fragment")) {
            try { provider(base = url).prepare("m1", messages()); fail("Endpoint accepted") }
            catch (failure: ModelBrokerFailure) { assertEquals(ModelBrokerCode.INVALID_ENDPOINT, failure.code) }
        }
    }

    @Test fun ssePreservesMultilineAndCrLfAndStopsAtDone() {
        val events = mutableListOf<String>()
        ProviderSse.copy(("event: delta\r\ndata: {\r\ndata: \"delta\": \"hello\"}\r\n\r\n" +
            "data: [DONE]\n\ndata: {\"delta\":\"ignored\"}\n\n").reader(), provider(), events::add)
        assertEquals("hello", JSONObject(events.single()).getString("delta"))
    }

    @Test fun echoedAndUnicodeEscapedKeysNeverLeaveTheFrameGuard() {
        val escaped = secret.map { "\\u%04x".format(it.code) }.joinToString("")
        for (value in listOf(secret, escaped)) {
            val events = mutableListOf<String>()
            try {
                ProviderSse.copy("data: {\"delta\":\"$value\"}\n\n".reader(), provider(), events::add)
                fail("Sensitive frame accepted")
            } catch (failure: ModelBrokerFailure) {
                assertEquals(ModelBrokerCode.SENSITIVE_RESPONSE, failure.code)
                assertTrue(events.isEmpty())
                assertFalse(failure.toString().contains(secret))
            }
        }
    }

    @Test fun oversizedOrMalformedFramesAndProviderErrorsAreClosed() {
        for (data in listOf("not-json", "{\"error\":{\"message\":\"denied\"}}", "x".repeat(256 * 1024 + 1))) {
            try { ProviderSse.copy("data: $data\n\n".reader(), provider()) { fail("Invalid frame emitted") }; fail("Accepted") }
            catch (_: ModelBrokerFailure) { }
        }
    }

    @Test fun truncatedProviderStreamsAreNotNormalCompletion() {
        try {
            ProviderSse.copy("data: {\"delta\":\"partial\"}\n\n".reader(), provider()) { }
            fail("Truncated stream accepted")
        } catch (failure: ModelBrokerFailure) { assertEquals(ModelBrokerCode.INVALID_RESPONSE, failure.code) }
        for ((format, event) in mapOf(
            "openai-completions" to "{\"choices\":[{\"finish_reason\":\"stop\"}]}",
            "openai-responses" to "{\"type\":\"response.completed\"}",
            "anthropic-messages" to "{\"type\":\"message_stop\"}",
            "google-generative-ai" to "{\"candidates\":[{\"finishReason\":\"STOP\"}]}")) {
            val events = mutableListOf<String>()
            ProviderSse.copy("data: $event\n\n".reader(), provider(format), events::add)
            assertEquals(1, events.size)
        }
    }

    @Test fun parserBudgetRejectsDeepTreesBeforeJsonParserAndIgnoresQuotedBraces() {
        val nested = "[".repeat(1000) + "0" + "]".repeat(1000)
        try { requireNativeJsonBudget(nested, ModelBrokerCode.INVALID_REQUEST); fail("Deep JSON accepted") }
        catch (failure: ModelBrokerFailure) { assertEquals(ModelBrokerCode.INVALID_REQUEST, failure.code) }
        try { ProviderSse.copy("data: $nested\n\n".reader(), provider()) { }; fail("Deep provider frame accepted") }
        catch (failure: ModelBrokerFailure) { assertEquals(ModelBrokerCode.INVALID_RESPONSE, failure.code) }
        requireNativeJsonBudget(JSONObject().put("text", "[\\\"{}]".repeat(1000)).toString(), ModelBrokerCode.INVALID_REQUEST)
    }

    @Test fun incompleteHeadersHaveAnAbsoluteDeadlineWithoutResolvingAProvider() {
        val resolutions = java.util.concurrent.atomic.AtomicInteger()
        NativeModelBroker({ _, _ -> resolutions.incrementAndGet(); provider() },
            ProviderTransport { throw IllegalStateException("unexpected transport") }, headerTimeoutMs = 100).use { broker ->
            val uri = java.net.URI(broker.session.baseUrl)
            java.net.Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("POST /v1/model/stream HTTP/1.1\r\n".toByteArray())
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(0, resolutions.get())
        }
    }

    @Test fun streamDeadlineClosesDownstreamAndCancelsAnIdleUpstream() {
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val call = object : ProviderCall {
            override fun execute() = ProviderResponse(200, "text/event-stream", object : java.io.InputStream() {
                override fun read(): Int { release.await(); return -1 }
            })
            override fun cancel() { release.countDown() }
            override fun close() { release.countDown(); ended.countDown() }
        }
        NativeModelBroker({ _, _ -> provider() }, ProviderTransport { call }, streamTimeoutMs = 250).use { broker ->
            val connection = java.net.URI(broker.session.baseUrl + "/v1/model/stream").toURL().openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 2000
                connection.setRequestProperty("Authorization", "Bearer ${broker.session.token}")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write("{\"providerId\":\"p\",\"modelId\":\"m\",\"messages\":[]}".toByteArray()) }
                assertEquals(200, connection.responseCode)
                assertEquals("1", connection.getHeaderField("X-Mbot-Broker-Protocol"))
                assertFalse(connection.inputStream.bufferedReader().readText().contains("[DONE]"))
                assertTrue(ended.await(2, TimeUnit.SECONDS))
            } finally { connection.disconnect() }
        }
    }

    @Test fun downstreamDisconnectCancelsAStalledRealOkHttpCall() {
        val upstream = MockWebServer()
        upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"delta\":\"slow\"}\n\n").setBodyDelay(3, TimeUnit.SECONDS))
        upstream.start()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val cancelled = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val reading = CountDownLatch(1)
        val transport = ProviderTransport { native ->
            val request = Request.Builder().url(upstream.url("/v1/chat/completions"))
                .post(native.body.toRequestBody("application/json".toMediaType()))
            native.headers.forEach { (name, value) -> request.header(name, value) }
            val call = client.newCall(request.build())
            val delegate = OkHttpProviderCall(call)
            object : ProviderCall {
                override fun execute() = delegate.execute().also { reading.countDown() }
                override fun cancel() { delegate.cancel(); if (call.isCanceled()) cancelled.countDown() }
                override fun close() { delegate.close(); returned.countDown() }
            }
        }
        val codes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val broker = NativeModelBroker({ _, _ -> provider() }, transport) { codes.add(it.wire) }
        try {
            val downstream = client.newCall(Request.Builder().url(broker.session.baseUrl + "/v1/model/stream")
                .header("Authorization", "Bearer ${broker.session.token}")
                .post("{\"providerId\":\"p\",\"modelId\":\"m1\",\"messages\":[]}".toRequestBody("application/json".toMediaType())).build())
            val response = downstream.execute()
            assertEquals(200, response.code)
            assertNotNull("No upstream request; native codes=$codes", upstream.takeRequest(2, TimeUnit.SECONDS))
            assertTrue("Upstream headers not received", reading.await(2, TimeUnit.SECONDS))
            downstream.cancel()
            response.close()
            assertTrue("Upstream call not cancelled", cancelled.await(2, TimeUnit.SECONDS))
            assertTrue("Blocked reader did not return", returned.await(2, TimeUnit.SECONDS))
        } finally {
            broker.close(); client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow(); upstream.shutdown()
        }
    }
}
