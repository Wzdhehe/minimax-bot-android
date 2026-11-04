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
}
