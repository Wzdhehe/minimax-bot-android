// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * Stage D (FGS start order). Context.startForegroundService() arms a system
 * timer that only stops when BotRuntimeService.onStartCommand reaches
 * startForeground(). onStartCommand runs on the MAIN thread, so calling
 * startForegroundService() from FlutterPlugin.onAttachedToEngine (still inside
 * engine construction / activity start, with Dart entrypoint, plugin
 * registration and first frame queued behind it) lets that timer run while
 * the main thread is busy elsewhere -> ForegroundServiceDidNotStartInTimeException.
 *
 * This gate defers the actual launch until the main looper is idle and
 * coalesces repeated requests. It is pure Kotlin (no android.*) so it is host
 * testable; BotRuntimeManager supplies the idle scheduler (MessageQueue
 * IdleHandler) and the launch action (startForegroundService).
 *
 * It does NOT touch process isolation: IsolatedBotService stays
 * isolatedProcess=true and Node never runs in the main/same-UID process.
 */
internal class ForegroundStartGate(
    /** Runs [task] once, the next time the owning looper is idle. */
    private val whenIdle: (task: () -> Unit) -> Unit,
    /** Performs the real start; throwing means the start is unavailable. */
    private val launch: () -> Unit,
    /** Called (outside any lock) when [launch] threw. */
    private val onLaunchFailed: (Exception) -> Unit,
) {
    private val lock = Any()
    private var pendingLaunch = false
    /** Bumped by every request and by cancel(), so a stale idle task is a no-op. */
    private var generation = 0L

    /**
     * Requests one launch. Returns true when this call scheduled a new launch,
     * false when one is already pending (coalesced). After the pending launch
     * has run (or failed), a later request schedules again.
     */
    fun request(): Boolean {
        val token = synchronized(lock) {
            if (pendingLaunch) return false
            pendingLaunch = true
            generation += 1
            generation
        }
        try {
            whenIdle { run(token) }
        } catch (error: Exception) {
            // The idle scheduler itself failed, so no launch can happen at all.
            synchronized(lock) { if (generation == token) { pendingLaunch = false; generation += 1 } }
            onLaunchFailed(error)
            return false
        }
        return true
    }

    /** True while a launch is scheduled but has not run yet. */
    val pending: Boolean get() = synchronized(lock) { pendingLaunch }

    /**
     * Drops a pending (not yet run) launch, e.g. on stop(). The idle task, when
     * it later fires, must then do nothing.
     */
    fun cancel(): Unit {
        synchronized(lock) { pendingLaunch = false; generation += 1 }
    }

    /** The idle task: launches only while this request is still the current one. */
    private fun run(token: Long) {
        val start = synchronized(lock) {
            if (!pendingLaunch || generation != token) false
            else { pendingLaunch = false; true }
        }
        if (!start) return
        try { launch() } catch (error: Exception) { onLaunchFailed(error) }
    }
}
