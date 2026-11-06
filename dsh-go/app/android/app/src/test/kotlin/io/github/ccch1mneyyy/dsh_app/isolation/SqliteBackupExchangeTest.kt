// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import org.junit.Assert.*
import org.junit.Test

class SqliteBackupExchangeTest {
    private val nonce = "c".repeat(64)
    private fun exchange() = SqliteBackupExchange(10001, 10, nonce)
    private fun candidate() = SqliteBackupReply.decode(longArrayOf(0, 0, 8192))
    private fun admit(exchange: SqliteBackupExchange) {
        assertTrue(exchange.beginSend())
        assertTrue(exchange.peer(99001, 20, nonce))
    }
    @Test fun peerAndResultAreNotRetirementEvenAfterTheSenderReturns() {
        val exchange = exchange()
        admit(exchange)
        val result = candidate()
        assertTrue(exchange.result(99001, 20, nonce, result))
        exchange.senderFinished()
        assertFalse(exchange.outcome().safeToRelease)
        assertFalse(exchange.outcome().candidateReady)
        exchange.workerDied()
        assertTrue(exchange.outcome().candidateReady)
        assertSame(result, exchange.outcome().reply)
    }
    @Test fun deathDoesNotReleaseDescriptorsWhileTheSenderStillUsesThem() {
        val exchange = exchange()
        admit(exchange)
        assertTrue(exchange.result(99001, 20, nonce, candidate()))
        exchange.workerDied()
        assertFalse(exchange.outcome().safeToRelease)
        exchange.senderFinished()
        assertTrue(exchange.outcome().safeToRelease)
        assertTrue(exchange.outcome().candidateReady)
    }
    @Test fun aResultMayArriveBeforeTheStartTransactionReturns() {
        val exchange = exchange()
        admit(exchange)
        assertTrue(exchange.result(99001, 20, nonce, candidate()))
        exchange.workerDied()
        exchange.senderFinished() // No acknowledgement is fabricated or required.
        assertTrue(exchange.outcome().candidateReady)
    }
    @Test fun cancellationAndFailedBindingBeforeSendingAllowNoFutureGrant() {
        for (cancel in listOf(true, false)) {
            val exchange = exchange()
            if (cancel) exchange.cancel()
            exchange.failBeforeSend()
            assertEquals(SqliteBackupExchange.Disposition.NOT_TRANSFERRED, exchange.outcome().disposition)
            assertTrue(exchange.outcome().safeToRelease)
            assertFalse(exchange.beginSend())
            assertFalse(exchange.outcome().candidateReady)
        }
    }
    @Test fun failureAfterSendingCannotClaimThatNothingWasTransferred() {
        val exchange = exchange()
        assertTrue(exchange.beginSend())
        exchange.failBeforeSend()
        exchange.senderFinished()
        assertFalse(exchange.outcome().safeToRelease)
        exchange.workerDied()
        assertTrue(exchange.outcome().safeToRelease)
        assertFalse(exchange.outcome().candidateReady)
    }
    @Test fun onlyTheActualAdmittedPeerCanReplyAndItCannotBeReplaced() {
        val exchange = exchange()
        assertFalse(exchange.peer(99001, 20, nonce))
        assertTrue(exchange.beginSend())
        assertFalse(exchange.peer(10001, 20, nonce))
        assertFalse(exchange.peer(99001, 10, nonce))
        assertFalse(exchange.peer(99001, 20, "d".repeat(64)))
        assertFalse(exchange.result(99001, 20, nonce, candidate()))
        assertTrue(exchange.peer(99001, 20, nonce))
        assertFalse(exchange.peer(99002, 21, nonce))
        assertFalse(exchange.result(99002, 20, nonce, candidate()))
        assertFalse(exchange.result(99001, 21, nonce, candidate()))
        assertFalse(exchange.result(99001, 20, "d".repeat(64), candidate()))
        assertTrue(exchange.result(99001, 20, nonce, candidate()))
        assertFalse(exchange.result(99001, 20, nonce, candidate()))
    }
    @Test fun deathBeforeAResultRefusesItsLateSuccess() {
        val exchange = exchange()
        admit(exchange)
        exchange.workerDied()
        assertFalse(exchange.result(99001, 20, nonce, candidate()))
        exchange.senderFinished()
        assertTrue(exchange.outcome().safeToRelease)
        assertFalse(exchange.outcome().candidateReady)
    }
    @Test fun cancellationInvalidatesBothLateAndAlreadyReceivedCandidates() {
        for (resultFirst in listOf(true, false)) {
            val exchange = exchange()
            admit(exchange)
            val result = candidate()
            if (resultFirst) assertTrue(exchange.result(99001, 20, nonce, result))
            exchange.cancel()
            assertFalse(exchange.result(99001, 20, nonce, result))
            exchange.senderFinished(); exchange.workerDied()
            assertTrue(exchange.outcome().safeToRelease)
            assertFalse(exchange.outcome().candidateReady)
            assertFalse(exchange.candidateStillValid(result))
        }
    }
    @Test fun aPreviouslyReturnedCandidateMustBeRecheckedBeforePublication() {
        val exchange = exchange()
        admit(exchange)
        assertTrue(exchange.result(99001, 20, nonce, candidate()))
        exchange.senderFinished(); exchange.workerDied()
        val receipt = exchange.outcome()
        assertTrue(exchange.candidateStillValid(receipt.reply))
        assertFalse(exchange.candidateStillValid(candidate()))
        exchange.cancel()
        assertFalse(exchange.candidateStillValid(receipt.reply))
        assertFalse(exchange.toString().contains(nonce))
    }
    @Test fun aNormalFrameworkDisconnectDoesNotEraseAResultOrProveDeath() {
        val exchange = exchange()
        admit(exchange)
        assertTrue(exchange.result(99001, 20, nonce, candidate()))
        exchange.senderFinished()
        exchange.bindingLost()
        assertFalse(exchange.outcome().safeToRelease)
        exchange.workerDied()
        assertTrue(exchange.outcome().candidateReady)
        exchange.bindingLost()
        assertTrue(exchange.outcome().candidateReady)
    }
}
