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

@RunWith(AndroidJUnit4::class)
class ProviderStorageTest {
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
}
