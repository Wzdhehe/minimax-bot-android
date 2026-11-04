// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import io.github.ccch1mneyyy.dsh_app.model.NativeModelBroker
import io.github.ccch1mneyyy.dsh_app.model.ProviderTransport
import io.github.ccch1mneyyy.dsh_app.model.ProviderCall
import io.github.ccch1mneyyy.dsh_app.model.ProviderResponse
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ProviderStorageTest {
    @Test
    fun storedKeyIsInjectedNativelyAndEchoedProviderContentIsRejectedOverHttp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "mbot-native-stream-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val store = BotProviderStore(context)
        val secret = "device-test-only-${UUID.randomUUID()}"
        val injected = AtomicBoolean()
        val echo = AtomicBoolean()
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        var id: String? = null
        try {
            store.save(mapOf("name" to "Native stream test", "baseUrl" to "https://example.com/v1",
                "apiFormat" to "openai-completions", "models" to listOf("m1"), "key" to secret))
            id = ((store.catalog()["providers"] as List<*>).single() as Map<*, *>)["providerId"] as String
            val transport = ProviderTransport { request ->
                injected.set(request.headers["Authorization"] == "Bearer $secret")
                object : ProviderCall {
                    override fun execute(): ProviderResponse {
                        val json = JSONObject().put("delta", if (echo.get()) secret else "hello").toString()
                        return ProviderResponse(200, "text/event-stream", "data: $json\n\ndata: [DONE]\n\n".byteInputStream())
                    }
                    override fun cancel() { }
                    override fun close() { }
                }
            }
            NativeModelBroker(store::resolveForModelBroker, transport) { logs.add(it.wire) }.use { broker ->
                fun post(): String {
                    val connection = URI(broker.session.baseUrl + "/v1/model/stream").toURL().openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.connectTimeout = 2000
                        connection.readTimeout = 3000
                        connection.setRequestProperty("Authorization", "Bearer ${broker.session.token}")
                        connection.setRequestProperty("Content-Type", "application/json")
                        val bytes = JSONObject().put("providerId", id).put("modelId", "m1")
                            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hi")))
                            .toString().toByteArray(Charsets.UTF_8)
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                        assertEquals(200, connection.responseCode)
                        assertEquals("1", connection.getHeaderField("X-Mbot-Broker-Protocol"))
                        return connection.inputStream.bufferedReader().use { it.readText() }
                    } finally { connection.disconnect() }
                }
                val normal = post()
                assertTrue(injected.get())
                assertTrue(normal.contains("hello"))
                assertTrue(normal.contains("[DONE]"))
                assertFalse(normal.contains(secret))
                echo.set(true)
                val blocked = post()
                assertTrue(blocked.contains("provider_sensitive_response"))
                assertFalse(blocked.contains(secret))
                assertFalse(logs.any { it.contains(secret) })
                assertFalse(store.catalog().toString().contains(secret))
            }
        } finally {
            id?.let { store.delete(it) }
            base.deleteSharedPreferences(prefix + "minimax_bot_provider")
        }
    }

    @Test
    fun keysAreEncryptedAndCatalogsContainOnlyMetadata() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "mbot-provider-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val store = BotProviderStore(context)
        val secret = "test-only-${UUID.randomUUID()}"
        var id: String? = null
        try {
            store.save(mapOf("name" to "Example", "baseUrl" to "https://example.com/v1", "apiFormat" to "openai-completions", "models" to listOf("m1"), "key" to secret))
            val providers = store.catalog()["providers"] as List<*>
            val row = providers.single() as Map<*, *>
            id = row["providerId"] as String
            assertEquals(true, row["hasApiKey"])
            assertFalse(row.containsKey("key"))
            assertFalse(row.containsKey("apiKey"))
            assertFalse(store.catalog().toString().contains(secret))
            assertFalse(context.getSharedPreferences("minimax_bot_provider", Context.MODE_PRIVATE).all.values.any { it.toString().contains(secret) })
            assertEquals(secret, store.readKeyForBroker(id))
            val nativeProvider = store.resolveForModelBroker(id, "m1")
            val request = nativeProvider.prepare("m1", JSONArray().put(
                JSONObject().put("role", "user").put("content", "Hi")))
            assertEquals("Bearer $secret", request.headers["Authorization"])
            assertFalse(request.url.contains(secret))
            assertFalse(request.body.contains(secret))
            assertFalse(nativeProvider.toString().contains(secret))
            assertFalse(request.toString().contains(secret))
            try {
                store.resolveForModelBroker(id, "unlisted-model")
                fail("Unlisted model accepted")
            } catch (_: io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure) { }
            store.selectDefault(id, "m1")
            assertEquals("m1", store.catalog()["defaultModelId"])
            store.save(mapOf("providerId" to id, "name" to "Changed", "baseUrl" to "https://example.com/v1", "apiFormat" to "openai-responses", "models" to listOf("m2"), "clearKey" to true))
            assertNull(store.readKeyForBroker(id))
            assertNull(store.catalog()["defaultModelId"])
        } finally {
            id?.let { store.delete(it) }
            base.deleteSharedPreferences(prefix + "minimax_bot_provider")
        }
    }

    @Test
    fun theGuestCatalogCarriesTheProviderButNoEndpointAndNoCredential() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "mbot-guest-catalog-test-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name, mode)
        }
        val store = BotProviderStore(context)
        val secret = "test-only-${UUID.randomUUID()}"
        var id: String? = null
        try {
            store.save(mapOf("name" to "Example", "baseUrl" to "https://example.com/v1",
                "apiFormat" to "openai-completions", "models" to listOf("m1", "m2"), "key" to secret))
            id = ((store.catalog()["providers"] as List<*>).single() as Map<*, *>)["providerId"] as String
            store.selectDefault(id, "m1")
            val guest = store.guestCatalogJson()
            // The guest learns the provider, its api format and its models.
            val parsed = JSONObject(guest)
            val providers = parsed.getJSONArray("providers")
            assertEquals(1, providers.length())
            val row = providers.getJSONObject(0)
            assertEquals(id, row.getString("providerId"))
            assertEquals("openai-completions", row.getString("apiFormat"))
            assertEquals(true, row.getBoolean("enabled"))
            assertEquals(listOf("m1", "m2"), (0 until row.getJSONArray("models").length()).map { row.getJSONArray("models").getString(it) })
            assertEquals(id, parsed.getString("defaultProviderId"))
            assertEquals("m1", parsed.getString("defaultModelId"))
            // The row carries exactly the allow-list, nothing else.
            assertEquals(setOf("providerId", "name", "apiFormat", "enabled", "models"), row.keys().asSequence().toSet())
            // No endpoint and no credential may reach the guest catalog.
            assertFalse(guest.contains("example.com"))
            assertFalse(guest.contains("baseUrl"))
            assertFalse(guest.contains("apiKey"))
            assertFalse(guest.contains("maskedApiKey"))
            assertFalse(guest.contains(secret))
        } finally {
            id?.let { store.delete(it) }
            base.deleteSharedPreferences(prefix + "minimax_bot_provider")
        }
    }
}
