// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDescriptorPayloadTest {
    private fun session(catalog: String? = null) = RuntimeDescriptorSession(
        entryPath = "/data/app/private/runtime/entry.mjs",
        pinCommit = "97818b2890b4acae1c6bd1200a226ff506680628",
        port = 4123,
        token = "b".repeat(64),
        brokerBaseUrl = "http://127.0.0.1:43117",
        brokerToken = "a".repeat(64),
        catalogJson = catalog,
    )

    private val catalog =
        """{"providers":[{"providerId":"p1","name":"n","apiFormat":"openai-completions",""" +
            """"enabled":true,"models":["m1"]}],"defaultProviderId":"p1","defaultModelId":"m1"}"""

    @Test
    fun `a descriptor without a catalog keeps its previous shape`() {
        val descriptor = JSONObject(RuntimeDescriptorPayload.encode(session()))
        assertEquals(5, descriptor.length())
        assertFalse(descriptor.has("catalog"))
        assertEquals("http://127.0.0.1:43117", descriptor.getJSONObject("broker").getString("baseUrl"))
    }

    @Test
    fun `an isolated guest needs no loopback broker session`() {
        val existing = session(catalog)
        val isolated = RuntimeDescriptorSession(existing.entryPath, existing.pinCommit, existing.port,
            existing.token, catalogJson = catalog)
        val descriptor = JSONObject(RuntimeDescriptorPayload.encode(isolated))
        assertTrue(descriptor.isNull("broker"))
        assertEquals("p1", descriptor.getJSONObject("catalog").getString("defaultProviderId"))
    }

    @Test
    fun `a partial loopback broker session is refused`() {
        val existing = session()
        for ((baseUrl, token) in listOf("http://127.0.0.1:43117" to null, null to "a".repeat(64))) {
            var refused = false
            try {
                RuntimeDescriptorPayload.encode(RuntimeDescriptorSession(existing.entryPath, existing.pinCommit,
                    existing.port, existing.token, baseUrl, token))
            } catch (_: IllegalArgumentException) { refused = true }
            assertTrue(refused)
        }
    }

    @Test
    fun `a descriptor carries the non-secret catalog`() {
        val descriptor = JSONObject(RuntimeDescriptorPayload.encode(session(catalog)))
        assertEquals(6, descriptor.length())
        assertEquals("p1", descriptor.getJSONObject("catalog").getString("defaultProviderId"))
        assertEquals("m1", descriptor.getJSONObject("catalog").getJSONArray("providers")
            .getJSONObject(0).getJSONArray("models").getString(0))
        assertFalse(descriptor.toString().contains("apiKey"))
    }

    @Test
    fun `a catalog that carries a credential or an endpoint is refused`() {
        for (candidate in listOf(
            """{"providers":[{"providerId":"p1","apiKey":"sk-live"}]}""",
            """{"providers":[{"providerId":"p1","options":{"api_key":"sk-live"}}]}""",
            """{"providers":[{"providerId":"p1","baseUrl":"https://api.minimax.io/v1"}]}""",
            """{"providers":[{"providerId":"p1","url":"https://api.minimax.io/v1"}]}""",
            """{"providers":[{"providerId":"p1","nested":{"secret":"x"}}]}""",
        )) {
            var refused = false
            try {
                RuntimeDescriptorPayload.encode(session(candidate))
            } catch (error: IllegalArgumentException) {
                refused = true
            }
            assertTrue("the catalog must be refused", refused)
        }
    }

    @Test
    fun `a catalog that is not a JSON object is refused`() {
        for (candidate in listOf("", "   ", "[]", "null", "not json", "{", "}")) {
            var refused = false
            try {
                RuntimeDescriptorPayload.encode(session(candidate))
            } catch (error: IllegalArgumentException) {
                refused = true
            }
            assertTrue("the catalog must be refused", refused)
        }
    }

    @Test
    fun `a model id that reads like a secret is not refused`() {
        val candidate = """{"providers":[{"providerId":"p1","models":["apiKey","token"]}]}"""
        val descriptor = JSONObject(RuntimeDescriptorPayload.encode(session(candidate)))
        assertEquals("apiKey", descriptor.getJSONObject("catalog").getJSONArray("providers")
            .getJSONObject(0).getJSONArray("models").getString(0))
    }
}
