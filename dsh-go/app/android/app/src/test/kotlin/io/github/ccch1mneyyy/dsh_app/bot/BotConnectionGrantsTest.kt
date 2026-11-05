// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.github.ccch1mneyyy.dsh_app.runtime.BotConnectionGrants
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BotConnectionGrantsTest {
    private fun accepted(uid: Int = 99001) = CompletableFuture.completedFuture(BotConnectionGrants.Receipt(uid, true))
    private fun refused(code: String, action: () -> Unit) {
        try { action(); fail("grant_refusal_expected") }
        catch (error: IOException) { assertEquals("bot_grant_$code", error.message); assertNull(error.cause) }
    }

    @Test fun onlyAnAcceptedReceiptFromTheBoundUidAdmitsTheConnection() {
        BotConnectionGrants(99001).use { gate ->
            gate.awaitAccepted { accepted() }
            refused("denied") { gate.awaitAccepted { accepted(99002) } }
            refused("denied") { gate.awaitAccepted { CompletableFuture.completedFuture(BotConnectionGrants.Receipt(99001, false)) } }
            gate.awaitAccepted { accepted() }
            assertEquals("BotConnectionGrants(REDACTED)", gate.toString())
            assertEquals("BotConnectionGrants.Receipt(REDACTED)", BotConnectionGrants.Receipt(99001, true).toString())
        }
    }

    @Test fun closeWakesAWaitingGrantAndLateReceiptCannotReviveIt() {
        val gate = BotConnectionGrants(99001)
        val worker = Executors.newSingleThreadExecutor()
        val sent = CountDownLatch(1)
        val remote = CompletableFuture<BotConnectionGrants.Receipt>()
        try {
            val waiting = worker.submit { refused("closed") { gate.awaitAccepted { sent.countDown(); remote } } }
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            gate.close()
            waiting.get(2, TimeUnit.SECONDS)
            assertTrue(remote.isCancelled)
            assertFalse(remote.complete(BotConnectionGrants.Receipt(99001, true)))
            refused("closed") { gate.awaitAccepted { fail("stopped_gate_must_not_offer"); accepted() } }
            gate.close()
        } finally {
            gate.close(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun closeDoesNotWaitForAnExternalOfferThatReturnsLate() {
        val gate = BotConnectionGrants(99001)
        val worker = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val remote = CompletableFuture<BotConnectionGrants.Receipt>()
        try {
            val waiting = worker.submit {
                refused("closed") { gate.awaitAccepted {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    remote
                } }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.submit { gate.close() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            waiting.get(2, TimeUnit.SECONDS)
            assertTrue(remote.isCancelled)
        } finally {
            release.countDown(); gate.close(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun capacityRefusesBeforeOfferingAndACompletedGrantReleasesItsSlot() {
        BotConnectionGrants(99001, capacity = 1).use { gate ->
            val worker = Executors.newSingleThreadExecutor()
            val sent = CountDownLatch(1)
            val remote = CompletableFuture<BotConnectionGrants.Receipt>()
            try {
                val waiting = worker.submit { gate.awaitAccepted { sent.countDown(); remote } }
                assertTrue(sent.await(5, TimeUnit.SECONDS))
                refused("busy") { gate.awaitAccepted { fail("busy_gate_must_not_offer"); accepted() } }
                remote.complete(BotConnectionGrants.Receipt(99001, true))
                waiting.get(2, TimeUnit.SECONDS)
                gate.awaitAccepted { accepted() }
            } finally {
                gate.close(); worker.shutdownNow()
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun deadlineCancelsTheReceiptAndDoesNotRetryAnUnknownDelivery() {
        BotConnectionGrants(99001, timeoutMs = 100).use { gate ->
            val remote = CompletableFuture<BotConnectionGrants.Receipt>()
            var offers = 0
            refused("timeout") { gate.awaitAccepted { offers++; remote } }
            assertEquals(1, offers)
            assertTrue(remote.isCancelled)
            gate.awaitAccepted { accepted() }
        }
    }

    @Test fun externalErrorsAndFailedReceiptsDoNotEscapeAsDiagnosticDetails() {
        BotConnectionGrants(99001).use { gate ->
            refused("unavailable") { gate.awaitAccepted { throw IOException("private-offer-detail") } }
            refused("unavailable") { gate.awaitAccepted {
                CompletableFuture<BotConnectionGrants.Receipt>().also { it.completeExceptionally(IllegalStateException("private-receipt-detail")) }
            } }
            refused("unavailable") { gate.awaitAccepted {
                CompletableFuture<BotConnectionGrants.Receipt>().also { it.cancel(false) }
            } }
            gate.awaitAccepted { accepted() }
        }
    }

    @Test fun closingTheOldGateCannotCancelTheNextRunsGrant() {
        val previous = BotConnectionGrants(99001)
        previous.close()
        BotConnectionGrants(99002).use { next ->
            previous.close()
            refused("closed") { previous.awaitAccepted { accepted() } }
            next.awaitAccepted { accepted(99002) }
        }
    }
}
