// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import org.json.JSONArray
import org.json.JSONObject

/** One provider row as the guest runtime is allowed to see it. */
internal data class GuestProviderRow(
    val providerId: String,
    val name: String,
    val apiFormat: String,
    val enabled: Boolean,
    val models: List<String>,
    val modelSettings: List<GuestModelSettings> = emptyList(),
)

/** Only mcode's non-secret UserModelInputView fields cross the boundary.
 * Construct through validation, never serialize a caller's arbitrary map. */
internal class GuestModelSettings private constructor(
    val modelId: String,
    val enabled: Boolean,
    private val json: String,
) {
    fun toJson(): JSONObject = JSONObject(json)
    fun toMap(): Map<String, Any?> = asMap(toJson())

    companion object {
        private val fields = setOf("modelId", "enabled", "limit", "effortOptions", "modalities", "reasoning")
        private val modalities = setOf("text", "image", "pdf", "video", "audio")

        private fun plain(value: Any?): Any? = when (value) {
            is JSONObject -> asMap(value)
            is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
            JSONObject.NULL -> null
            else -> value
        }
        private fun asMap(value: JSONObject): Map<String, Any?> =
            value.keys().asSequence().associateWith { plain(value.get(it)) }
        fun fromJson(models: List<String>, input: JSONArray?): List<GuestModelSettings> =
            parse(models, input?.let { (0 until it.length()).map { index -> asMap(it.getJSONObject(index)) } })

        fun parse(models: List<String>, input: List<*>?): List<GuestModelSettings> {
            require(input == null || input.size <= models.size)
            val specified = linkedMapOf<String, Map<*, *>>()
            for (value in input.orEmpty()) {
                val row = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid model settings")
                require(row.keys.all { it in fields })
                val id = row["modelId"] as? String ?: throw IllegalArgumentException("Missing model id")
                require(id in models && specified.put(id, row) == null)
            }
            return models.map { id ->
                val row = specified[id].orEmpty()
                require(!row.containsKey("enabled") || row["enabled"] is Boolean)
                val enabled = row["enabled"] as? Boolean ?: true
                val out = JSONObject().put("modelId", id).put("enabled", enabled)
                if (row.containsKey("limit")) {
                    val limits = row["limit"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid limits")
                    require(limits.keys.all { it == "context" || it == "output" })
                    val json = JSONObject()
                    for ((name, value) in limits) {
                        require(value is Int || value is Long)
                        val number = (value as Number).toLong()
                        require(number in 1..9007199254740991L)
                        json.put(name as String, number)
                    }
                    out.put("limit", json)
                }
                if (row.containsKey("reasoning")) {
                    require(row["reasoning"] is Boolean)
                    out.put("reasoning", row["reasoning"])
                }
                if (row.containsKey("effortOptions")) {
                    val options = row["effortOptions"] as? List<*> ?: throw IllegalArgumentException("Invalid efforts")
                    require(options.size <= 32 && options.all { it is String && it.isNotBlank() && it.length <= 64 })
                    require(row["reasoning"] != false || options.isEmpty())
                    out.put("effortOptions", JSONArray(options.map { (it as String).trim() }.distinct()))
                }
                if (row.containsKey("modalities")) {
                    val modes = row["modalities"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid modalities")
                    require(modes.keys.all { it == "input" || it == "output" })
                    val json = JSONObject()
                    for ((name, value) in modes) {
                        val values = value as? List<*> ?: throw IllegalArgumentException("Invalid modalities")
                        require(values.size <= 5 && values.all { it is String && it in modalities })
                        // The Bot produces text; declaring input support doesn't add an upload UI.
                        require(name != "output" || values.all { it == "text" })
                        json.put(name as String, JSONArray((listOf("text") + values).distinct()))
                    }
                    out.put("modalities", json)
                }
                GuestModelSettings(id, enabled, out.toString())
            }
        }
    }
}

/**
 * The non-secret provider catalog handed to the guest runtime.
 *
 * This is an allow-list projection: the encoder only ever writes the five
 * fields of [GuestProviderRow], so a row that still carries a base URL or a
 * credential cannot reach the guest even when the caller passes it in. The
 * real endpoint and the real key stay on the native side and are resolved per
 * request by the native broker.
 */
internal object GuestProviderCatalog {
    /** Encode the guest catalog as the JSON object the runtime descriptor carries. */
    fun encode(
        providers: List<GuestProviderRow>,
        defaultProviderId: String?,
        defaultModelId: String?,
    ): String {
        val rows = JSONArray()
        for (provider in providers) {
            val models = JSONArray()
            for (model in provider.models) models.put(model)
            val row = JSONObject()
                .put("providerId", provider.providerId)
                .put("name", provider.name)
                .put("apiFormat", provider.apiFormat)
                .put("enabled", provider.enabled)
                .put("models", models)
            if (provider.modelSettings.isNotEmpty()) {
                row.put("modelSettings", JSONArray(provider.modelSettings.map { it.toJson() }))
            }
            rows.put(row)
        }
        return JSONObject()
            .put("providers", rows)
            .put("defaultProviderId", defaultProviderId ?: JSONObject.NULL)
            .put("defaultModelId", defaultModelId ?: JSONObject.NULL)
            .toString()
    }
}
