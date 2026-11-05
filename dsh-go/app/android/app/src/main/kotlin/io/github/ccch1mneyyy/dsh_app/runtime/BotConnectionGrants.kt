// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One trusted native run's admission gate. expectedUid must come from its
 * authenticated Binder handshake, never an HTTP field or reply payload.
 * Call on bridge workers only; no socket or provider Key is handled here.
 */
internal class BotConnectionGrants(
    private val expectedUid: Int,
    private val capacity: Int = 4,
    private val timeoutMs: Long = 10_000,
) : Closeable {
    /** uid is Messenger's system-supplied sendingUid, not a Bundle field. */
    class Receipt(val uid: Int, val accepted: Boolean) {
        override fun toString() = "BotConnectionGrants.Receipt(REDACTED)"
    }

    private class Pending { val result = CompletableFuture<Receipt?>() }
    private val lock = Any()
    private var closed = false
    private val pending = mutableSetOf<Pending>()

    init {
        require(expectedUid > 0) { "bot_grant_uid_invalid" }
        require(capacity in 1..16) { "bot_grant_capacity_invalid" }
        require(timeoutMs in 100..10_000) { "bot_grant_timeout_invalid" }
    }

    /** offer must return promptly after IPC enqueue. close can revoke the
     * private wait even when that external callback/future ignores cancel;
     * it cannot interrupt a foreign synchronous send. A late send/receipt
     * never authorizes a stopped run. The caller still owns/closes its FDs.
     */
    fun awaitAccepted(offer: () -> CompletableFuture<Receipt>) {
        val request = synchronized(lock) {
            if (closed) throw failure("closed")
            if (pending.size >= capacity) throw failure("busy")
            Pending().also { pending.add(it) }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var remote: CompletableFuture<Receipt>? = null
        try {
            if (synchronized(lock) { closed }) throw failure("closed")
            remote = offer()
            remote.whenComplete { receipt, error -> request.result.complete(if (error == null) receipt else null) }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException()
            val receipt = request.result.get(remaining, TimeUnit.NANOSECONDS)
            synchronized(lock) {
                // Linearize acceptance against close, not just the future's
                // completion; a receipt queued before stop is insufficient.
                if (closed || request !in pending) throw failure("closed")
                if (receipt == null) throw failure("unavailable")
                if (receipt.uid != expectedUid || !receipt.accepted) throw failure("denied")
                pending.remove(request)
            }
        } catch (_: TimeoutException) {
            throw failure(if (synchronized(lock) { closed }) "closed" else "timeout")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure("unavailable")
        } catch (error: IOException) {
            // Only errors produced below escape. External offer exceptions,
            // including IOExceptions with sensitive messages, are sanitized.
            throw if (error is GrantFailure) error else failure("unavailable")
        } catch (_: Exception) {
            throw failure("unavailable")
        } finally {
            synchronized(lock) { pending.remove(request) }
            // Do not invoke external future handlers from close or under the
            // gate lock. They can run inline and are not trusted to be quick.
            try { remote?.cancel(false) } catch (_: Exception) { }
        }
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            pending.toList().also { pending.clear() }
        }
        retired.forEach { it.result.complete(null) }
    }

    override fun toString() = "BotConnectionGrants(REDACTED)"

    private class GrantFailure(code: String) : IOException("bot_grant_$code")
    private fun failure(code: String) = GrantFailure(code)
}
