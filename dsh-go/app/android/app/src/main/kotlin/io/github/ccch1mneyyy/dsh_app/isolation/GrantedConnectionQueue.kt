// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import java.io.Closeable
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded hand-off of already-authorized connections to an isolated runtime.
 * This queue does not authenticate senders; the owner-only IPC must do that
 * before calling [offer]. An accepted item belongs to the queue until [take]
 * transfers ownership to its caller. Shutdown closes every still-queued item.
 */
internal class GrantedConnectionQueue<T : Closeable>(private val capacity: Int = 8) : Closeable {
    enum class Offer { ACCEPTED, CLOSED, FULL, DUPLICATE }

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val pending = ArrayDeque<T>()
    private val identities = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var closed = false

    init { require(capacity in 1..64) }

    /** Takes ownership even when closed/full; a duplicate is already owned. */
    fun offer(item: T): Offer {
        val result = lock.withLock {
            when {
                identities.contains(item) -> Offer.DUPLICATE
                closed -> Offer.CLOSED
                pending.size >= capacity -> Offer.FULL
                else -> {
                    pending.addLast(item)
                    identities.add(item)
                    available.signal()
                    Offer.ACCEPTED
                }
            }
        }
        if (result == Offer.CLOSED || result == Offer.FULL) closeQuietly(item)
        return result
    }

    /** Returns one owned connection or null on shutdown, timeout or interrupt. */
    fun take(timeoutMs: Long): T? {
        require(timeoutMs in 1..30_000)
        return try {
            lock.withLock {
                var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                while (pending.isEmpty() && !closed && remaining > 0) remaining = available.awaitNanos(remaining)
                if (closed || pending.isEmpty()) null else pending.removeFirst().also { identities.remove(it) }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    override fun close() {
        val discarded = lock.withLock {
            if (closed) return
            closed = true
            val items = pending.toList()
            pending.clear()
            identities.clear()
            available.signalAll()
            items
        }
        discarded.forEach(::closeQuietly)
    }

    private fun closeQuietly(item: T) { try { item.close() } catch (_: Exception) { } }
}
