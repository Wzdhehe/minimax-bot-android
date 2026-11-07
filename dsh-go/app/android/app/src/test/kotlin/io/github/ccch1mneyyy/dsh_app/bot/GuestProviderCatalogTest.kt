// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestProviderCatalogTest {
    @Test
    fun `catalog carries the projected provider, its format and its models`() {
        val catalog = JSONObject(GuestProviderCatalog.encode(
            listOf(
                GuestProviderRow("p1", "Provider One", "openai-completions", true, listOf("m1", "m2")),
                GuestProviderRow("p2", "Provider Two", "anthropic-messages", false, listOf("m3")),
            ),
            "p1",
            "m1",
        ))
        val providers = catalog.getJSONArray("providers")
        assertEquals(2, providers.length())
        val first = providers.getJSONObject(0)
        assertEquals("p1", first.getString("providerId"))
        assertEquals("Provider One", first.getString("name"))
        assertEquals("openai-completions", first.getString("apiFormat"))
        assertTrue(first.getBoolean("enabled"))
        assertEquals(2, first.getJSONArray("models").length())
        assertEquals("m1", first.getJSONArray("models").getString(0))
        assertFalse(providers.getJSONObject(1).getBoolean("enabled"))
        assertEquals("p1", catalog.getString("defaultProviderId"))
        assertEquals("m1", catalog.getString("defaultModelId"))
    }

    @Test
    fun `the projection is an allow list and never publishes an endpoint or a credential`() {
        val json = GuestProviderCatalog.encode(
            listOf(GuestProviderRow("p1", "Provider One", "openai-completions", true, listOf("m1"))),
            null,
            null,
        )
        val provider = JSONObject(json).getJSONArray("providers").getJSONObject(0)
        assertEquals(5, provider.length())
        assertFalse(json.contains("baseUrl"))
        assertFalse(json.contains("apiKey"))
        assertFalse(json.contains("http"))
        assertTrue(JSONObject(json).isNull("defaultProviderId"))
        assertTrue(JSONObject(json).isNull("defaultModelId"))
    }

    @Test
    fun `an empty provider list still produces a valid catalog`() {
        val catalog = JSONObject(GuestProviderCatalog.encode(emptyList(), null, null))
        assertEquals(0, catalog.getJSONArray("providers").length())
    }

    @Test
    fun `model parameters round trip with exact mcode field names and independent switches`() {
        val settings = GuestModelSettings.parse(listOf("m1", "m2"), listOf(mapOf(
            "modelId" to "m1", "enabled" to false,
            "limit" to mapOf("context" to 1000000, "output" to 128000),
            "effortOptions" to listOf("high", "high", " custom-level "),
            "modalities" to mapOf("input" to listOf("image", "video", "audio", "pdf"), "output" to listOf("text")),
        )))
        assertFalse(settings[0].enabled)
        assertTrue(settings[1].enabled)
        val catalog = JSONObject(GuestProviderCatalog.encode(
            listOf(GuestProviderRow("p1", "Provider", "openai-completions", true, listOf("m1", "m2"), settings)), null, null,
        ))
        val models = catalog.getJSONArray("providers").getJSONObject(0).getJSONArray("modelSettings")
        assertEquals(1000000, models.getJSONObject(0).getJSONObject("limit").getInt("context"))
        assertEquals(128000, models.getJSONObject(0).getJSONObject("limit").getInt("output"))
        assertEquals(2, models.getJSONObject(0).getJSONArray("effortOptions").length())
        assertEquals("text", models.getJSONObject(0).getJSONObject("modalities").getJSONArray("input").getString(0))
        assertEquals(settings.map { it.toMap() }, GuestModelSettings.fromJson(listOf("m1", "m2"), models).map { it.toMap() })
    }

    @Test
    fun `model parameter validation rejects invalid numbers credentials and unknown metadata`() {
        val invalid = listOf(
            mapOf("limit" to mapOf("context" to 0)),
            mapOf("limit" to mapOf("output" to 1.5)),
            mapOf("limit" to mapOf("context" to 9007199254740992L)),
            mapOf("limit" to mapOf("apiKey" to "test-only-key")),
            mapOf("apiKey" to "test-only-key"),
            mapOf("baseUrl" to "https://example.invalid"),
            mapOf("enabled" to "false"),
            mapOf("effortOptions" to listOf("")),
            mapOf("modalities" to mapOf("input" to listOf("executable"))),
            mapOf("modalities" to mapOf("output" to listOf("image"))),
            mapOf("reasoning" to false, "effortOptions" to listOf("high")),
        )
        for (fields in invalid) {
            try {
                GuestModelSettings.parse(listOf("m1"), listOf(mapOf("modelId" to "m1") + fields))
                throw AssertionError("Invalid model settings were accepted")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test
    fun `duplicate or unknown model overrides cannot create broker permission`() {
        for (input in listOf(
            listOf(mapOf("modelId" to "other")),
            listOf(mapOf("modelId" to "m1"), mapOf("modelId" to "m1")),
        )) {
            try {
                GuestModelSettings.parse(listOf("m1", "m2"), input)
                throw AssertionError("Unknown or repeated model accepted")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}
