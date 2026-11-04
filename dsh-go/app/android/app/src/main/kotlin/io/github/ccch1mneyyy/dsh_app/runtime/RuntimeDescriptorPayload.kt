// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * The trusted hand-off from the Android host to the guest runtime.
 *
 * The descriptor carries the guest entry path, the pinned upstream commit, the
 * loopback port and the session token of the local Bot service, plus an
 * optional native model broker session. The host
 * writes it into its own private directory with mode 0600. An isolated guest
 * cannot open that pathname; the trusted host must explicitly transfer a
 * read-only FD via [RuntimeDescriptor.writeForTransfer].
 *
 * It never travels through argv or the environment: a process list, an
 * inherited variable or a world-readable file would hand a live session to
 * the Agent. A descriptor that is not an owner-only regular file owned by
 * the trusted owner is refused, and a pre-existing symlink is replaced rather
 * than followed. A loopback broker URL here does not imply network access from
 * an isolated UID; model requests use the separate native capability.
 */
internal class RuntimeDescriptorSession(
    val entryPath: String,
    val pinCommit: String,
    val port: Int,
    val token: String,
    val brokerBaseUrl: String? = null,
    val brokerToken: String? = null,
    /**
     * The non-secret provider catalog, encoded by [io.github.ccch1mneyyy.dsh_app.bot.GuestProviderCatalog].
     * Absent when native has no provider configured yet.
     */
    val catalogJson: String? = null,
) {
    companion object {
        fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * The descriptor encoding, free of Android imports so the security-relevant
 * serialization can be exercised off-device.
 */
internal object RuntimeDescriptorPayload {
    /** Field names that may never appear in the guest catalog. */
    private val CREDENTIAL_FIELD =
        Regex("api[-_.]?key|secret|token|credential|password|passwd|authorization|cookie", RegexOption.IGNORE_CASE)

    /** Field names that would carry the real upstream endpoint into the guest. */
    private val ENDPOINT_FIELD = Regex("^(baseurl|base_url|url|endpoint|host|hostname)$", RegexOption.IGNORE_CASE)

    /** Encode the descriptor fields. The catalog is spliced in verbatim. */
    fun encode(session: RuntimeDescriptorSession): String {
        val baseUrl = session.brokerBaseUrl
        val token = session.brokerToken
        require((baseUrl == null) == (token == null)) { "broker session incomplete" }
        val broker = if (baseUrl == null || token == null) "null" else
            "{\"baseUrl\":" + quote(baseUrl) + ",\"token\":" + quote(token) + "}"
        val catalog = session.catalogJson?.let { json ->
            require(json.isNotBlank()) { "catalog must not be empty" }
            require(json.startsWith("{") && json.endsWith("}")) { "catalog must be a JSON object" }
            assertCatalogFieldsAreSafe(json)
            ",\"catalog\":" + json
        } ?: ""
        return "{\"entryPath\":" + quote(session.entryPath) +
            ",\"pinCommit\":" + quote(session.pinCommit) +
            ",\"port\":" + session.port +
            ",\"token\":" + quote(session.token) +
            ",\"broker\":" + broker +
            catalog + "}"
    }

    /**
     * The guest catalog may not carry a credential or a real endpoint. The
     * rule matches the runtime side exactly: field names are inspected, so a
     * model id that happens to read like a secret is not refused.
     */
    private fun assertCatalogFieldsAreSafe(json: String) {
        val root = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw IllegalArgumentException("catalog must be a JSON object")
        }
        assertFieldsAreSafe(root)
    }

    private fun assertFieldsAreSafe(value: Any?) {
        when (value) {
            is JSONObject -> for (key in value.keys()) {
                require(!CREDENTIAL_FIELD.containsMatchIn(key)) { "catalog field is a credential" }
                require(!ENDPOINT_FIELD.matches(key)) { "catalog field is an endpoint" }
                assertFieldsAreSafe(value.get(key))
            }
            is JSONArray -> for (index in 0 until value.length()) {
                assertFieldsAreSafe(value.get(index))
            }
        }
    }

    private fun quote(value: String): String {
        val out = StringBuilder()
        for (character in value) {
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character < ' ' -> out.append("\\u%04x".format(character.code))
                else -> out.append(character)
            }
        }
        return "\"$out\""
    }
}
