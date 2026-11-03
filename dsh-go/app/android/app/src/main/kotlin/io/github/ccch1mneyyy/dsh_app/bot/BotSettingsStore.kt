// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class BotSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("minimax_bot_provider", Context.MODE_PRIVATE)
    private val alias = "minimax_bot_provider_v1"
    private val aad = "minimax-bot-provider-v1".toByteArray(Charsets.UTF_8)

    fun load(): Map<String, Any> = mapOf(
        "baseUrl" to (prefs.getString("baseUrl", null) ?: "https://api.minimax.io/v1"),
        "model" to (prefs.getString("model", null) ?: ""),
        "hasKey" to prefs.contains("ciphertext"),
    )

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized
    fun save(baseUrl: String, model: String, key: String?, clearKey: Boolean) {
        val base = baseUrl.trim()
        val uri = URI(base)
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(model.trim().isNotEmpty() && model.length <= 256)
        require(!(clearKey && key != null))
        val editor = prefs.edit().putString("baseUrl", base).putString("model", model.trim())
        if (clearKey) editor.remove("ciphertext").remove("iv")
        else if (key != null) {
            require(key.isNotBlank() && key.length <= 8192)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret())
            cipher.updateAAD(aad)
            editor.putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            editor.putString("ciphertext", Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
        check(editor.commit()) { "Settings write failed" }
    }

    /** Native broker only; deliberately not exposed on the MethodChannel. */
    @Synchronized
    fun readKeyForBroker(): String? {
        val encrypted = prefs.getString("ciphertext", null) ?: return null
        val iv = prefs.getString("iv", null) ?: error("Missing encrypted settings IV")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.updateAAD(aad)
        return cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
