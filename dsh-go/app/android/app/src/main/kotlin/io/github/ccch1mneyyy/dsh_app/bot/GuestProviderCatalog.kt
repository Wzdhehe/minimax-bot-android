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
)

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
            rows.put(JSONObject()
                .put("providerId", provider.providerId)
                .put("name", provider.name)
                .put("apiFormat", provider.apiFormat)
                .put("enabled", provider.enabled)
                .put("models", models))
        }
        return JSONObject()
            .put("providers", rows)
            .put("defaultProviderId", defaultProviderId ?: JSONObject.NULL)
            .put("defaultModelId", defaultModelId ?: JSONObject.NULL)
            .toString()
    }
}
