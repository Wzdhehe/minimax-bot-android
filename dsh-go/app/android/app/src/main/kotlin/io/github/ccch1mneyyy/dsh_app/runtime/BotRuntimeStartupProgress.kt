// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

/** Finite, once-per-phase diagnostics from one isolated execution. Not a
 * readiness receipt. Never emits caller strings, paths or error details. */
internal class BotRuntimeStartupProgress(private val emit: (String) -> Unit) {
    private var next = 0
    private var closed = false

    @Synchronized fun report(phase: String): Boolean {
        val index = PHASES.indexOf(phase)
        if (closed || index < next) return false
        next = index + 1
        try { emit("bot_start_${PHASES[index]}") } catch (_: Exception) { }
        return true
    }

    @Synchronized fun close() { closed = true }

    companion object {
        val PHASES = listOf(
            "assets_loading", "assets_loaded", "filesystem_loading", "filesystem_installed",
            "runtime_importing", "runtime_imported", "runtime_factory_starting", "runtime_factory_started",
            "native_catalog_starting", "native_catalog_projected", "bot_service_starting", "bot_service_started",
            "ingress_started",
        )
    }
}
