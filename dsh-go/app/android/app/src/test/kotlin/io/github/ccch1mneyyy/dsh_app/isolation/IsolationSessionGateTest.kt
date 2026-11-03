// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import org.junit.Assert.*
import org.junit.Test

class IsolationSessionGateTest {
    private val nonce = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test fun onlyOwningUidCanRegisterAnIndependentAgentIdentity() {
        val gate = IsolationSessionGate(10000)
        assertFalse(gate.permits(99001, nonce))
        assertEquals(BrokerResponse.UNAUTHORIZED, gate.register(99001, 99001, nonce))
        assertEquals(BrokerResponse.UNAUTHORIZED, gate.register(10000, 10000, nonce))
        assertEquals(BrokerResponse.UNAUTHORIZED, gate.register(10000, -1, nonce))
        assertEquals(BrokerResponse.UNAUTHORIZED, gate.register(10000, 99001, "invalid"))
        assertEquals(BrokerResponse.OK, gate.register(10000, 99001, nonce))
        assertTrue(gate.permits(99001, nonce))
        assertFalse(gate.permits(10000, nonce))
        assertFalse(gate.permits(99002, nonce))
        assertFalse(gate.permits(99001, other))
        assertFalse(gate.permits(99001, null))
    }

    @Test fun aSecondSessionCannotReplaceTheLiveLeaseOrRevokeAnotherOwner() {
        val gate = IsolationSessionGate(10000)
        assertEquals(BrokerResponse.OK, gate.register(10000, 99001, nonce))
        assertEquals(BrokerResponse.OK, gate.register(10000, 99001, nonce))
        assertEquals(BrokerResponse.BUSY, gate.register(10000, 99002, other))
        assertFalse(gate.revoke(99001, nonce))
        assertFalse(gate.revoke(10000, other))
        assertTrue(gate.permits(99001, nonce))
        assertTrue(gate.revoke(10000, nonce))
        assertFalse(gate.permits(99001, nonce))
        assertEquals(BrokerResponse.OK, gate.register(10000, 99002, other))
        assertFalse(gate.revoke(10000, nonce))
        assertTrue(gate.permits(99002, other))
    }

    @Test fun requestIdsAreBoundedOpaqueCorrelationTokens() {
        assertTrue(validIsolationRequestId("1"))
        assertTrue(validIsolationRequestId("b88452e8-7cef-4c6e-a052-1b7c14d1024f"))
        for (id in listOf(null, "", "a".repeat(129), "with space", "x\n", "../file")) {
            assertFalse(validIsolationRequestId(id))
        }
    }

    @Test fun binderResponsesRejectLargeTextOrDirectoryPayloadsWithoutPartialSuccess() {
        val small = BrokerResponse.text("small")
        assertSame(small, boundedIsolationResponse(small))
        assertEquals(BrokerResponse.TOO_LARGE, boundedIsolationResponse(BrokerResponse.text("x".repeat(256 * 1024 + 1))).code)
        assertEquals(BrokerResponse.TOO_LARGE, boundedIsolationResponse(BrokerResponse.text("中".repeat(100000))).code)
        assertEquals(BrokerResponse.TOO_LARGE, boundedIsolationResponse(BrokerResponse.listing(List(1001) { "x" })).code)
        val result = boundedIsolationResponse(BrokerResponse.listing(List(100) { "x".repeat(2000) }))
        assertFalse(result.ok)
        assertNull(result.text)
        assertNull(result.entries)
    }
}
