// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import org.junit.Assert.*
import org.junit.Test

class SqliteBackupSessionTest {
    private val nonce = "a".repeat(64)
    private fun worker() = SqliteBackupSession(10001, 99001, 42)
    private fun candidate() = SqliteBackupReply.decode(longArrayOf(0, 0, 4096))

    @Test fun onlyTheActualOwnerCanAdmitAnIndependentProcess() {
        assertFalse(SqliteBackupSession(10001, 10001, 42).admit(10001, 7, nonce))
        val session = worker()
        assertFalse(session.admit(99002, 7, nonce))
        assertFalse(session.admit(10001, 42, nonce))
        assertFalse(session.admit(10001, 0, nonce))
        assertFalse(session.admit(10001, 7, "../private"))
        assertTrue(session.admit(10001, 7, nonce))
        assertFalse(session.admit(10001, 7, nonce))
        assertFalse(session.admit(10001, 8, "b".repeat(64)))
    }
    @Test fun controlsRequireTheSameActualPidUidAndNonce() {
        val session = worker()
        assertFalse(session.cancel(10001, 7, nonce))
        assertTrue(session.admit(10001, 7, nonce))
        assertFalse(session.cancel(10001, 8, nonce))
        assertFalse(session.cancel(99002, 7, nonce))
        assertFalse(session.cancel(10001, 7, "b".repeat(64)))
        assertFalse(session.isCancelled())
        assertTrue(session.begin())
        assertTrue(session.finish(candidate()).candidateReady)
    }
    @Test fun cancellationBeforeLoadingCannotBeResetByStarting() {
        val session = worker()
        assertTrue(session.admit(10001, 7, nonce))
        assertTrue(session.cancel(10001, 7, nonce))
        assertFalse(session.begin())
        assertEquals(SqliteBackupReply.Result.CANCELLED, session.finish(candidate()).result)
        assertFalse(session.begin())
        assertFalse(session.admit(10001, 7, nonce))
    }
    @Test fun cancellationWhileNativeWorkRunsSuppressesItsLateSuccess() {
        val session = worker()
        assertTrue(session.admit(10001, 7, nonce))
        assertTrue(session.begin())
        assertTrue(session.cancel(10001, 7, nonce))
        val reply = session.finish(candidate())
        assertFalse(reply.candidateReady)
        assertEquals(SqliteBackupReply.Result.CANCELLED, reply.result)
        assertEquals(0L, reply.bytes)
    }
    @Test fun retirementCannotBeUndoneByALateNativeResultOrAdmission() {
        val session = worker()
        assertTrue(session.admit(10001, 7, nonce))
        assertTrue(session.begin())
        session.retire()
        assertSame(SqliteBackupReply.UNAVAILABLE, session.finish(candidate()))
        assertFalse(session.matches(10001, 7, nonce))
        assertFalse(session.admit(10001, 7, nonce))
        assertFalse(session.begin())
        assertFalse(session.toString().contains(nonce))
    }
    @Test fun failureAndSuccessfulCompletionBothConsumeTheWorker() {
        for (reply in listOf(candidate(), SqliteBackupReply.UNAVAILABLE)) {
            val session = worker()
            assertTrue(session.admit(10001, 7, nonce))
            assertTrue(session.begin())
            assertSame(reply, session.finish(reply))
            assertFalse(session.begin())
            assertSame(SqliteBackupReply.UNAVAILABLE, session.finish(candidate()))
            assertFalse(session.admit(10001, 8, "b".repeat(64)))
        }
    }
    @Test fun anUnstartedOperationCannotSupplyACandidate() {
        val session = worker()
        assertTrue(session.admit(10001, 7, nonce))
        assertSame(SqliteBackupReply.UNAVAILABLE, session.finish(candidate()))
        assertFalse(session.begin())
        assertFalse(session.admit(10001, 7, nonce))
    }
}
