// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal enum class ModelBrokerCode(val wire: String) {
    INVALID_REQUEST("broker_request_invalid"), INVALID_PROVIDER("invalid_provider"),
    MISSING_KEY("missing_key"), INVALID_ENDPOINT("invalid_endpoint"),
    PROVIDER_REJECTED("provider_rejected"), REQUEST_FAILED("provider_request_failed"),
    INVALID_RESPONSE("broker_invalid_response"), SENSITIVE_RESPONSE("provider_sensitive_response"),
    CANCELLED("broker_cancelled"), UNAVAILABLE("broker_unavailable")
}

internal class ModelBrokerFailure(val code: ModelBrokerCode) : Exception(code.wire)

/** Native-only credential snapshot. Deliberately not a data class: neither
 * toString nor generated copy/component functions may reveal a stored key. */
internal class NativeProvider(
    val apiFormat: String,
    val baseUrl: String,
    private val apiKey: String,
) {
    init {
        if (apiKey.isEmpty() || apiKey.length > 8192 || apiKey.any { it.code !in 33..126 }) {
            throw ModelBrokerFailure(ModelBrokerCode.MISSING_KEY)
        }
    }

    fun prepare(modelId: String, messages: JSONArray): NativeModelRequest {
        val base = try { URI(baseUrl) } catch (_: Exception) {
            throw ModelBrokerFailure(ModelBrokerCode.INVALID_ENDPOINT)
        }
        if (base.scheme != "https" || base.host.isNullOrBlank() || base.rawUserInfo != null ||
            base.rawQuery != null || base.rawFragment != null) {
            throw ModelBrokerFailure(ModelBrokerCode.INVALID_ENDPOINT)
        }
        val headers = mutableMapOf("Content-Type" to "application/json", "Accept" to "text/event-stream")
        val body = JSONObject().put("model", modelId).put("stream", true)
        val suffix = when (apiFormat) {
            "openai-completions" -> {
                headers["Authorization"] = "Bearer $apiKey"
                body.put("messages", messages)
                "/chat/completions"
            }
            "openai-responses" -> {
                headers["Authorization"] = "Bearer $apiKey"
                body.put("input", messages)
                "/responses"
            }
            "anthropic-messages" -> {
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
                val system = mutableListOf<String>()
                val chat = JSONArray()
                for (index in 0 until messages.length()) {
                    val message = messages.getJSONObject(index)
                    if (message.getString("role") == "system") system.add(message.getString("content"))
                    else chat.put(message)
                }
                body.put("messages", chat).put("max_tokens", 4096)
                if (system.isNotEmpty()) body.put("system", system.joinToString("\n"))
                "/messages"
            }
            "google-generative-ai" -> {
                headers["x-goog-api-key"] = apiKey
                val contents = JSONArray()
                val system = JSONArray()
                for (index in 0 until messages.length()) {
                    val message = messages.getJSONObject(index)
                    val part = JSONObject().put("text", message.getString("content"))
                    val role = message.getString("role")
                    if (role == "system") system.put(part)
                    else contents.put(JSONObject().put("role", if (role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(part)))
                }
                body.remove("model")
                body.remove("stream")
                body.put("contents", contents)
                if (system.length() > 0) body.put("systemInstruction", JSONObject().put("parts", system))
                val model = java.net.URLEncoder.encode(modelId, "UTF-8").replace("+", "%20")
                "/models/$model:streamGenerateContent?alt=sse"
            }
            else -> throw ModelBrokerFailure(ModelBrokerCode.INVALID_PROVIDER)
        }
        val target = baseUrl.trimEnd('/') + suffix
        return NativeModelRequest(target, headers, body.toString())
    }

    fun guardResponse(value: Any?, depth: Int = 0) {
        if (depth > 64) throw ModelBrokerFailure(ModelBrokerCode.INVALID_RESPONSE)
        when (value) {
            is String -> if (value.contains(apiKey)) throw ModelBrokerFailure(ModelBrokerCode.SENSITIVE_RESPONSE)
            is JSONObject -> for (name in value.keys()) {
                guardResponse(name, depth + 1)
                guardResponse(value.get(name), depth + 1)
            }
            is JSONArray -> for (index in 0 until value.length()) guardResponse(value.get(index), depth + 1)
        }
    }

    override fun toString() = "NativeProvider(REDACTED)"
}

internal class NativeModelRequest(val url: String, val headers: Map<String, String>, val body: String) {
    override fun toString() = "NativeModelRequest(REDACTED)"
}
