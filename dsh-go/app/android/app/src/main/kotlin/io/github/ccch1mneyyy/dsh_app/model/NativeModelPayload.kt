// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import org.json.JSONArray
import org.json.JSONObject

/** Provider-shaped request bodies from an SDK. Only model API fields cross
 * this boundary: never caller headers, endpoint overrides or credentials.
 * This preserves tool messages/schemas; it never executes a tool natively. */
internal fun NativeProvider.preparePayload(modelId: String, payload: JSONObject): NativeModelRequest {
    val fields = when (apiFormat) {
        "openai-completions" -> setOf("model", "messages", "stream", "tools", "tool_choice", "parallel_tool_calls",
            "functions", "function_call", "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop",
            "presence_penalty", "frequency_penalty", "seed", "stream_options", "response_format", "reasoning_effort",
            "user", "metadata", "store", "service_tier", "prompt_cache_key", "prompt_cache_retention",
            "thinking", "enable_thinking", "chat_template_kwargs", "reasoning", "tool_stream", "provider", "providerOptions")
        "openai-responses" -> setOf("model", "input", "instructions", "stream", "tools", "tool_choice", "parallel_tool_calls",
            "temperature", "top_p", "max_output_tokens", "text", "reasoning", "metadata", "store", "previous_response_id",
            "include", "truncation", "service_tier", "user", "prompt_cache_key", "prompt_cache_retention")
        "anthropic-messages" -> setOf("model", "messages", "system", "stream", "max_tokens", "tools", "tool_choice",
            "thinking", "temperature", "top_p", "top_k", "stop_sequences", "metadata", "service_tier", "output_config")
        "google-generative-ai" -> setOf("contents", "systemInstruction", "generationConfig", "safetySettings", "tools",
            "toolConfig", "cachedContent", "labels")
        else -> throw ModelBrokerFailure(ModelBrokerCode.INVALID_PROVIDER)
    }
    try {
        require(payload.keys().asSequence().all { it in fields })
        if (payload.has("model")) require(payload.get("model") == modelId)
        if (payload.has("stream")) require(payload.get("stream") == true)
        if (payload.has("tools")) require(payload.get("tools") is JSONArray)
        for (name in listOf("temperature", "top_p", "top_k", "max_tokens", "max_completion_tokens", "max_output_tokens",
            "presence_penalty", "frequency_penalty", "seed")) {
            if (payload.has(name)) {
                val value = payload.get(name) as? Number ?: throw IllegalArgumentException()
                require(value.toDouble().isFinite())
                if (name.startsWith("max_")) require(value.toDouble() > 0 && value.toDouble() % 1.0 == 0.0)
            }
        }
        when (apiFormat) {
            "openai-completions", "anthropic-messages" -> {
                require(payload.get("messages") is JSONArray)
                require(payload.getJSONArray("messages").length() > 0)
                if (apiFormat == "anthropic-messages") require(payload.has("max_tokens"))
            }
            "openai-responses" -> require(payload.get("input") is JSONArray || payload.get("input") is String)
            "google-generative-ai" -> {
                require(payload.get("contents") is JSONArray && payload.getJSONArray("contents").length() > 0)
                for (name in listOf("generationConfig", "toolConfig", "systemInstruction")) {
                    if (payload.has(name)) require(payload.get(name) is JSONObject)
                }
            }
        }
        // Reuse the exact text path's endpoint and native authentication logic.
        // A request body cannot choose where or how its Key is injected.
        val configured = prepare(modelId, JSONArray())
        val body = JSONObject(payload.toString())
        if (apiFormat != "google-generative-ai") body.put("model", modelId).put("stream", true)
        return NativeModelRequest(configured.url, configured.headers, body.toString())
    } catch (error: ModelBrokerFailure) {
        throw error
    } catch (_: Exception) {
        throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
    }
}
