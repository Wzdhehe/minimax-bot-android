// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import org.junit.Assert.*
import org.junit.Test

class SqliteBackupReplyTest {
    @Test fun aValidCandidateIsNotAPublicationResult() {
        val wire = longArrayOf(0, 0, 32768)
        val result = SqliteBackupReply.decode(wire)
        wire.fill(99)
        assertTrue(result.candidateReady)
        assertEquals(32768L, result.bytes)
    }
    @Test fun everyKnownFailureStaysAFailureWithNoMeaningfulByteCount() {
        for (stage in 1L..8L) for (result in 1L..9L) {
            val reply = SqliteBackupReply.decode(longArrayOf(stage, result, 0))
            assertNotEquals(SqliteBackupReply.UNAVAILABLE, reply)
            assertFalse(reply.candidateReady)
        }
    }
    @Test fun malformedOrNewWireValuesFailClosed() {
        for (wire in listOf(null, longArrayOf(), longArrayOf(0, 0), longArrayOf(0, 0, 1, 0),
                longArrayOf(-1, 1, 0), longArrayOf(9, 1, 0), longArrayOf(1, -1, 0),
                longArrayOf(1, 10, 0), longArrayOf(Long.MAX_VALUE, 1, 0))) {
            assertEquals(SqliteBackupReply.UNAVAILABLE, SqliteBackupReply.decode(wire))
        }
    }
    @Test fun impossibleSuccessAndFailureCombinationsAreRejected() {
        for (wire in listOf(longArrayOf(0, 1, 4096), longArrayOf(0, 0, 0),
                longArrayOf(0, 0, -1), longArrayOf(0, 0, 512L * 1024 * 1024 + 1),
                longArrayOf(5, 0, 0), longArrayOf(5, 7, 1))) {
            assertEquals(SqliteBackupReply.UNAVAILABLE, SqliteBackupReply.decode(wire))
        }
    }
}
