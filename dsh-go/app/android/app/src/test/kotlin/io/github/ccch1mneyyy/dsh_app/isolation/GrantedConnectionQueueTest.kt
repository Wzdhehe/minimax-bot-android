// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class GrantedConnectionQueueTest {
    private class Connection : Closeable {
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun acceptedConnectionsAreFifoAndOwnershipTransfersToTheConsumer() {
        val queue = GrantedConnectionQueue<Connection>(2)
        val first = Connection(); val second = Connection()
        assertEquals(GrantedConnectionQueue.Offer.ACCEPTED, queue.offer(first))
        assertEquals(GrantedConnectionQueue.Offer.ACCEPTED, queue.offer(second))
        assertSame(first, queue.take(100))
        queue.close()
        assertEquals(0, first.closes.get())
        assertEquals(1, second.closes.get())
        first.close()
    }

    @Test fun fullClosedAndDuplicateOffersHaveExplicitOwnership() {
        val queue = GrantedConnectionQueue<Connection>(1)
        val owned = Connection(); val overflow = Connection(); val afterClose = Connection()
        assertEquals(GrantedConnectionQueue.Offer.ACCEPTED, queue.offer(owned))
        assertEquals(GrantedConnectionQueue.Offer.DUPLICATE, queue.offer(owned))
        assertEquals(0, owned.closes.get())
        assertEquals(GrantedConnectionQueue.Offer.FULL, queue.offer(overflow))
        assertEquals(1, overflow.closes.get())
        queue.close(); queue.close()
        assertEquals(1, owned.closes.get())
        assertEquals(GrantedConnectionQueue.Offer.CLOSED, queue.offer(afterClose))
        assertEquals(1, afterClose.closes.get())
        assertNull(queue.take(100))
    }

    @Test fun closeWakesAWaitingConsumerAndTimeoutDoesNotConsumeLaterConnections() {
        val queue = GrantedConnectionQueue<Connection>()
        assertNull(queue.take(10))
        val later = Connection()
        assertEquals(GrantedConnectionQueue.Offer.ACCEPTED, queue.offer(later))
        assertSame(later, queue.take(100))
        later.close()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val result = executor.submit<Connection?> { started.countDown(); queue.take(30_000) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            queue.close()
            assertNull(result.get(1, TimeUnit.SECONDS))
        } finally { queue.close(); executor.shutdownNow() }
    }

    @Test fun racingOfferAndCloseNeverLeaksTheGrantedConnection() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val queue = GrantedConnectionQueue<Connection>(1)
                val item = Connection(); val start = CountDownLatch(1)
                val offered = executor.submit<GrantedConnectionQueue.Offer> { start.await(); queue.offer(item) }
                val closing = executor.submit { start.await(); queue.close() }
                start.countDown()
                assertTrue(offered.get(1, TimeUnit.SECONDS) in setOf(GrantedConnectionQueue.Offer.ACCEPTED, GrantedConnectionQueue.Offer.CLOSED))
                closing.get(1, TimeUnit.SECONDS)
                assertEquals(1, item.closes.get())
            }
        } finally { executor.shutdownNow() }
    }
}
