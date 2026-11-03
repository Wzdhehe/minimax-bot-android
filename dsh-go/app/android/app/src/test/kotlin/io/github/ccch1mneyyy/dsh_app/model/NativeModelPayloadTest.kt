// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeModelPayloadTest {
    private val secret = "payload-test-only-${UUID.randomUUID()}"
    private fun provider(format: String) = NativeProvider(format, "https://provider.example/v1", secret)
    private fun fixtures() = mapOf(
        "openai-completions" to JSONObject("""{"messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"lookup","arguments":"{\"path\":\"notes/a\"}"}}]},{"role":"tool","tool_call_id":"c1","content":"found"}],"tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object","properties":{"path":{"type":"string"}}}}}],"temperature":0.2}"""),
        "openai-responses" to JSONObject("""{"input":[{"type":"function_call_output","call_id":"c1","output":"found"}],"tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}],"max_output_tokens":256,"reasoning":{"effort":"medium"}}"""),
        "anthropic-messages" to JSONObject("""{"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"c1","name":"lookup","input":{"path":"notes/a"}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"c1","content":"found"}]}],"tools":[{"name":"lookup","input_schema":{"type":"object"}}],"max_tokens":256,"thinking":{"type":"enabled","budget_tokens":128}}"""),
        "google-generative-ai" to JSONObject("""{"contents":[{"role":"model","parts":[{"functionCall":{"name":"lookup","args":{"path":"notes/a"}}}]},{"role":"user","parts":[{"functionResponse":{"name":"lookup","response":{"result":"found"}}}]}],"tools":[{"functionDeclarations":[{"name":"lookup","parameters":{"type":"object"}}]}],"generationConfig":{"temperature":0.2,"maxOutputTokens":256}}"""),
    )

    @Test fun fourProviderToolSchemasAndResultsSurviveWithoutCredentialOrEndpointOverrides() {
        fixtures().forEach { (format, input) ->
            val request = provider(format).preparePayload("m1", input)
            val body = JSONObject(request.body)
            assertFalse(request.body.contains(secret))
            assertFalse(request.url.contains(secret))
            assertFalse(request.toString().contains(secret))
            assertEquals(input.getJSONArray("tools").toString(), body.getJSONArray("tools").toString())
            assertFalse(input.has("stream"))
            assertFalse(input.has("model"))
            if (format == "google-generative-ai") {
                assertFalse(body.has("model")); assertFalse(body.has("stream"))
                assertTrue(request.url.endsWith("/models/m1:streamGenerateContent?alt=sse"))
                assertTrue(request.headers["x-goog-api-key"] == secret)
                assertEquals("found", body.getJSONArray("contents").getJSONObject(1).getJSONArray("parts")
                    .getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response").getString("result"))
            } else {
                assertEquals("m1", body.getString("model")); assertTrue(body.getBoolean("stream"))
                assertTrue(if (format == "anthropic-messages") request.headers["x-api-key"] == secret
                    else request.headers["Authorization"] == "Bearer $secret")
                when (format) {
                    "openai-completions" -> assertEquals("c1", body.getJSONArray("messages").getJSONObject(1).getString("tool_call_id"))
                    "openai-responses" -> assertEquals("function_call_output", body.getJSONArray("input").getJSONObject(0).getString("type"))
                    "anthropic-messages" -> assertEquals("tool_result", body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
                }
            }
        }
    }

    @Test fun bodyCannotSelectCredentialsNetworkLocationOrASecondModel() {
        fixtures().forEach { (format, original) ->
            for (field in listOf("apiKey", "api_key", "headers", "authorization", "baseUrl", "url", "apiFormat")) {
                reject(format, JSONObject(original.toString()).put(field, "caller-override"))
            }
            reject(format, JSONObject(original.toString()).put("model", "unlisted-model"))
            reject(format, JSONObject(original.toString()).put("stream", false))
        }
    }

    @Test fun malformedRequiredFieldsAndTokenSettingsFailBeforeTransport() {
        reject("openai-completions", JSONObject("""{"messages":"not-array"}"""))
        reject("openai-responses", JSONObject("""{"input":123}"""))
        reject("anthropic-messages", JSONObject("""{"messages":[{"role":"user","content":"Hi"}]}"""))
        reject("anthropic-messages", JSONObject("""{"messages":[{"role":"user","content":"Hi"}],"max_tokens":0}"""))
        reject("google-generative-ai", JSONObject("""{"contents":[]}"""))
        reject("openai-completions", JSONObject("""{"messages":[{"role":"user","content":"Hi"}],"tools":{}}"""))
        reject("openai-completions", JSONObject("""{"messages":[{"role":"user","content":"Hi"}],"temperature":"high"}"""))
    }

    @Test fun pinnedSdkCacheAndThinkingOptionsStayInTheProviderBody() {
        for (format in listOf("openai-completions", "openai-responses")) {
            val input = fixtures().getValue(format).put("prompt_cache_key", "session-cache-id")
                .put("prompt_cache_retention", "24h")
            if (format == "openai-completions") {
                input.put("chat_template_kwargs", JSONObject().put("enable_thinking", true).put("preserve_thinking", true))
            }
            val prepared = provider(format).preparePayload("m1", input)
            val body = JSONObject(prepared.body)
            assertEquals("session-cache-id", body.getString("prompt_cache_key"))
            assertEquals("24h", body.getString("prompt_cache_retention"))
            if (format == "openai-completions") assertTrue(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
            assertTrue(prepared.headers["Authorization"] == "Bearer $secret")
            assertFalse(prepared.headers.containsKey("prompt_cache_key"))
        }
    }

    @Test fun toolDataNamedHeadersRemainsDataAndDoesNotChangeNativeAuthentication() {
        val input = JSONObject("""{"messages":[{"role":"tool","tool_call_id":"c1","content":"{\"headers\":{\"Authorization\":\"tool-data\"}}"}]}""")
        val request = provider("openai-completions").preparePayload("m1", input)
        assertTrue(JSONObject(request.body).getJSONArray("messages").getJSONObject(0).getString("content").contains("tool-data"))
        assertTrue(request.headers["Authorization"] == "Bearer $secret")
    }

    private fun reject(format: String, body: JSONObject) {
        try { provider(format).preparePayload("m1", body); fail("Unsafe or malformed model body accepted") }
        catch (error: ModelBrokerFailure) { assertEquals(ModelBrokerCode.INVALID_REQUEST, error.code) }
    }
}
