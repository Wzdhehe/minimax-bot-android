// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Provider metadata is exportable; API keys are not. This store does not
 * synchronize BYOK config into a runtime or authorize untrusted execution. */
internal class BotProviderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("minimax_bot_provider", Context.MODE_PRIVATE)
    private val formats = setOf("anthropic-messages", "openai-completions", "openai-responses", "google-generative-ai")
    private fun alias(id: String) = "mbot_provider_v2_$id"
    private fun aad(id: String) = "mbot-provider-v2:$id".toByteArray(Charsets.UTF_8)
    private fun key(id: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias(id), null) as? SecretKey)?.let { return it }
        check(create) { "Provider key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun encrypt(editor: android.content.SharedPreferences.Editor, id: String, value: String) {
        require(value.isNotBlank() && value.length <= 8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(id, true))
        cipher.updateAAD(aad(id))
        editor.putString("provider_iv_$id", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        editor.putString("provider_key_$id", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
    }
    private fun rows(): JSONArray = JSONArray(prefs.getString("providers_v2", null) ?: "[]")
    private fun rowList(rows: JSONArray): List<JSONObject> = (0 until rows.length()).map { rows.getJSONObject(it) }

    /** Migrate once, wholly inside native. Remove the old ciphertext in the
     * same preference commit so deleting the migrated provider deletes its key. */
    private fun migrate() {
        if (prefs.contains("providers_v2")) return
        val legacy = BotSettingsStore(context)
        val settings = legacy.load()
        val model = settings["model"] as String
        val hasKey = settings["hasKey"] == true
        val editor = prefs.edit()
        val rows = JSONArray()
        if (hasKey || model.isNotBlank()) {
            val id = "legacy-minimax"
            rows.put(JSONObject().put("providerId", id).put("name", "MiniMax")
                .put("apiFormat", "openai-completions").put("baseUrl", settings["baseUrl"])
                .put("models", JSONArray(if (model.isBlank()) emptyList<String>() else listOf(model))).put("enabled", true))
            legacy.readKeyForBroker()?.let { encrypt(editor, id, it) }
            if (model.isNotBlank()) editor.putString("default_provider_v2", id).putString("default_model_v2", model)
        }
        editor.remove("ciphertext").remove("iv").remove("baseUrl").remove("model")
        check(editor.putString("providers_v2", rows.toString()).commit())
    }

    @Synchronized
    fun catalog(): Map<String, Any?> {
        migrate()
        val providers = rowList(rows()).map { row ->
            val id = row.getString("providerId")
            val models = row.getJSONArray("models")
            val hasKey = prefs.contains("provider_key_$id")
            mapOf("providerId" to id, "name" to row.getString("name"), "baseUrl" to row.getString("baseUrl"),
                "apiFormat" to row.getString("apiFormat"), "enabled" to row.optBoolean("enabled", true),
                "models" to (0 until models.length()).map { models.getString(it) },
                "hasApiKey" to hasKey, "maskedApiKey" to if (hasKey) "••••••••" else null)
        }
        return mapOf("providers" to providers, "defaultProviderId" to prefs.getString("default_provider_v2", null), "defaultModelId" to prefs.getString("default_model_v2", null))
    }

    @Synchronized
    fun save(input: Map<*, *>) {
        migrate()
        val current = rowList(rows())
        val existingId = input["providerId"] as? String
        require(existingId == null || current.any { it.getString("providerId") == existingId })
        require(existingId != null || current.size < 100)
        val id = existingId ?: UUID.randomUUID().toString()
        val name = (input["name"] as? String)?.trim() ?: ""
        val base = (input["baseUrl"] as? String)?.trim() ?: ""
        val format = input["apiFormat"] as? String
        val uri = try { URI(base) } catch (_: Exception) { throw IllegalArgumentException("Invalid endpoint") }
        require(name.isNotEmpty() && name.length <= 128 && format in formats)
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val models = (input["models"] as? List<*>)?.map { require(it is String); it.trim() }?.distinct() ?: emptyList()
        require(models.isNotEmpty() && models.size <= 1000 && models.all { it.isNotEmpty() && it.length <= 256 })
        val enabled = input["enabled"] as? Boolean ?: true
        val rawKey = input["key"] as? String
        val clearKey = input["clearKey"] == true
        require(!(clearKey && rawKey != null))
        val row = JSONObject().put("providerId", id).put("name", name).put("baseUrl", base).put("apiFormat", format)
            .put("models", JSONArray(models)).put("enabled", enabled)
        val next = JSONArray(current.filter { it.getString("providerId") != id } + row)
        val editor = prefs.edit().putString("providers_v2", next.toString())
        if (rawKey != null) encrypt(editor, id, rawKey)
        if (clearKey) editor.remove("provider_key_$id").remove("provider_iv_$id")
        if (prefs.getString("default_provider_v2", null) == id && (!enabled || prefs.getString("default_model_v2", null) !in models)) {
            editor.remove("default_provider_v2").remove("default_model_v2")
        }
        check(editor.commit())
    }

    @Synchronized
    fun delete(id: String) {
        migrate()
        val current = rowList(rows())
        require(current.any { it.getString("providerId") == id })
        val editor = prefs.edit().putString("providers_v2", JSONArray(current.filter { it.getString("providerId") != id }).toString())
            .remove("provider_key_$id").remove("provider_iv_$id")
        if (prefs.getString("default_provider_v2", null) == id) editor.remove("default_provider_v2").remove("default_model_v2")
        check(editor.commit())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias(id)) }
    }

    @Synchronized
    fun selectDefault(id: String, model: String) {
        migrate()
        val provider = rowList(rows()).firstOrNull { it.getString("providerId") == id }
        require(provider != null && provider.optBoolean("enabled", true))
        val models = provider.getJSONArray("models")
        require((0 until models.length()).any { models.getString(it) == model })
        check(prefs.edit().putString("default_provider_v2", id).putString("default_model_v2", model).commit())
    }

    /** Never registered on a MethodChannel. A future broker may call this. */
    @Synchronized
    fun readKeyForBroker(id: String): String? {
        require(rowList(rows()).any { it.getString("providerId") == id })
        val encrypted = prefs.getString("provider_key_$id", null) ?: return null
        val iv = prefs.getString("provider_iv_$id", null) ?: error("Provider IV unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(id, false), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD(aad(id))
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
