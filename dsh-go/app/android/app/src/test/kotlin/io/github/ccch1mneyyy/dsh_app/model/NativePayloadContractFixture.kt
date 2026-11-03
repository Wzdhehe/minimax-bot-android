// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.model

import java.io.File
import java.util.UUID
import org.json.JSONObject

/** JVM-only consumer of bodies captured from unchanged pinned provider SDKs.
 * The generated fake Key never leaves this process or appears in diagnostics. */
object NativePayloadContractFixture {
    @JvmStatic fun main(args: Array<String>) {
        val contracts = JSONObject(File(args.single()).readText()).getJSONObject("requests")
        val formats = contracts.keys().asSequence().toList()
        check(formats.toSet() == setOf("openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai"))
        val key = "contract-only-${UUID.randomUUID()}"
        formats.forEach { format ->
            val request = NativeProvider(format, "https://provider.invalid/v1", key)
                .preparePayload("m1", contracts.getJSONObject(format))
            check(request.url.startsWith("https://provider.invalid/v1/")) { "native_endpoint_changed" }
            check(!request.url.contains(key) && !request.body.contains(key) && !request.toString().contains(key)) { "native_surface_unsafe" }
            val header = when (format) {
                "anthropic-messages" -> "x-api-key"
                "google-generative-ai" -> "x-goog-api-key"
                else -> "Authorization"
            }
            check(request.headers[header] == if (header == "Authorization") "Bearer $key" else key) { "native_auth_missing" }
            check(JSONObject(request.body).getJSONArray("tools").length() == 1) { "tool_schema_lost" }
        }
        println("accepted=${formats.size}")
    }
}
