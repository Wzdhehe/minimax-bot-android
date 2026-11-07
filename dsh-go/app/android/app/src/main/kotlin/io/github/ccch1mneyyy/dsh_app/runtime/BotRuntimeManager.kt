// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.ccch1mneyyy.dsh_app.bot.BotProviderStore
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Main process product lifecycle. No provider credential is returned to UI.
 * Opening the app starts its own foreground service; no DSH process is used. */
internal object BotRuntimeManager {
    private val lock = Any()
    private val coordinator = BotRuntimeCoordinator()
    private var current: BotRuntimeExecution? = null
    private var lastCode: String? = null
    private var restart = false
    private var launchRequested = false
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val readers = Executors.newFixedThreadPool(2)
    fun status(): Map<String, String> = synchronized(lock) {
        mapOf("phase" to (current?.run?.phase?.name?.lowercase() ?: if (restart || launchRequested) "starting" else if (lastCode == null) "stopped" else "failed"),
            "code" to (current?.failure ?: lastCode ?: "ok"))
    }
    fun observe(listener: () -> Unit) { listeners.add(listener) }
    fun removeObserver(listener: () -> Unit) { listeners.remove(listener) }
    private fun changed() { main.post { listeners.forEach { it() } } }
    fun requestStart(context: Context) {
        synchronized(lock) {
            if (current != null || launchRequested) return
            launchRequested = true
        }
        changed()
        val intent = Intent(context, BotRuntimeService::class.java).setAction(BotRuntimeService.START)
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (_: Exception) { synchronized(lock) { launchRequested = false; if (current == null) lastCode = "bot_start_unavailable" }; changed() }
    }
    fun start(context: Context) {
        val execution = synchronized(lock) {
            launchRequested = false
            if (current != null) return
            lastCode = null
            restart = false
            lateinit var candidate: BotRuntimeExecution
            candidate = BotRuntimeExecution(context.applicationContext) { onExecutionChanged(candidate) }
            try { candidate.run = coordinator.begin { candidate.closeAsync() } }
            catch (_: Exception) { lastCode = "bot_runtime_busy"; changed(); return }
            current = candidate
            candidate
        }
        execution.start()
        changed()
    }
    private fun onExecutionChanged(execution: BotRuntimeExecution) {
        synchronized(lock) { if (current !== execution) return }
        if (execution.run.phase == BotRuntimeCoordinator.Run.Phase.STOPPING || execution.run.phase == BotRuntimeCoordinator.Run.Phase.FAILED) {
            execution.run.closeAsync().whenComplete { _, error ->
                var again = false
                synchronized(lock) {
                    if (current === execution) {
                        lastCode = if (error != null) "bot_runtime_cleanup_unverified" else execution.failure
                        if (error == null) current = null
                        again = restart && error == null
                        if (!again) restart = false
                    }
                }
                changed()
                if (again) main.post { activeContext?.let { requestStart(it) } }
            }
        }
        changed()
    }
    @Volatile private var activeContext: Context? = null
    fun attach(context: Context) { activeContext = context.applicationContext }
    fun stop() {
        val execution = synchronized(lock) { restart = false; launchRequested = false; current }
        execution?.run?.close()
        if (execution != null) onExecutionChanged(execution) else changed()
    }
    fun catalogChanged(context: Context) {
        // Key replacement alone does not require a guest restart: Main resolves
        // it afresh for each model call. Only the non-secret catalog is compared.
        val next = try { BotProviderStore(context).guestCatalogJson() } catch (_: Exception) { return }
        if (synchronized(lock) { current == null }) { requestStart(context); return }
        val execution = synchronized(lock) {
            val value = current ?: return
            if (value.catalogJson == next) return
            restart = true
            value
        }
        execution.run.close()
        onExecutionChanged(execution)
    }
    fun descriptorWhenSettled(reply: (Map<String, String>?) -> Unit) {
        readers.execute {
            // First-install seeding took 62s in the API35 product run. Keep
            // the UI waiting within the launcher's existing 90s startup
            // budget instead of declaring a still-starting service absent.
            val deadline = System.nanoTime() + 90_000_000_000L
            var descriptor: Map<String, String>? = null
            do {
                descriptor = BotRuntimeEndpoint.descriptor()
                if (descriptor != null || status()["phase"] != "starting") break
                try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            } while (System.nanoTime() < deadline)
            val value = descriptor
            main.post { reply(value) }
        }
    }
}
