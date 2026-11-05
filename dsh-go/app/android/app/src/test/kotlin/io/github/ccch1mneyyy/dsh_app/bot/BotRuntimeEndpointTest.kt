// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeEndpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The UI may only receive the endpoint of a running service, and it must not
 * keep a credential of a service that stopped. */
class BotRuntimeEndpointTest {
    @After
    fun forget() {
        BotRuntimeEndpoint.clear()
    }

    @Test
    fun aPublishedServiceIsReportedWithItsToken() {
        BotRuntimeEndpoint.publish("http://127.0.0.1:8788", "a".repeat(64))
        assertEquals(
            mapOf("baseUrl" to "http://127.0.0.1:8788", "token" to "a".repeat(64)),
            BotRuntimeEndpoint.descriptor(),
        )
    }

    @Test
    fun noServiceMeansNoDescriptor() {
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test
    fun aStoppedServiceLeavesNoStaleCredential() {
        BotRuntimeEndpoint.publish("http://127.0.0.1:8788", "a".repeat(64))
        BotRuntimeEndpoint.clear()
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test
    fun anEndpointThatIsNotALoopbackServiceIsRefused() {
        for (url in listOf("http://10.0.0.1:8788", "http://127.0.0.1:0", "https://127.0.0.1:8788", "127.0.0.1:8788")) {
            try {
                BotRuntimeEndpoint.publish(url, "a".repeat(64))
                fail("bot_endpoint_expected_refusal")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test
    fun aTokenThatIsNotASessionTokenIsRefused() {
        for (token in listOf("", "short", "z".repeat(64))) {
            try {
                BotRuntimeEndpoint.publish("http://127.0.0.1:8788", token)
                fail("bot_endpoint_expected_refusal")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertNull(BotRuntimeEndpoint.descriptor())
    }
}
