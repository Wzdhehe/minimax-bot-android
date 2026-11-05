// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.github.ccch1mneyyy.dsh_app.runtime.BotRuntimeEndpoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Test tokens stay out of assertion messages and object descriptions. */
class BotRuntimeEndpointTest {
    private val leases = mutableListOf<BotRuntimeEndpoint.Lease>()
    private val firstUrl = "http://127.0.0.1:8788"
    private val secondUrl = "http://127.0.0.1:8789"
    private val firstToken = "a".repeat(64)
    private val secondToken = "b".repeat(64)

    private fun publish(url: String = firstUrl, token: String = firstToken) =
        BotRuntimeEndpoint.publish(url, token).also { leases.add(it) }

    private fun expectLive(url: String, token: String) {
        val descriptor = BotRuntimeEndpoint.descriptor()
        assertTrue("endpoint_descriptor_shape", descriptor?.keys == setOf("baseUrl", "token"))
        assertTrue("endpoint_url_mismatch", descriptor?.get("baseUrl") == url)
        assertTrue("endpoint_token_mismatch", descriptor?.get("token") == token)
    }

    @After fun forget() { leases.asReversed().forEach { it.close() } }

    @Test fun aPublishedServiceIsReportedWithItsToken() {
        publish()
        expectLive(firstUrl, firstToken)
    }

    @Test fun noServiceMeansNoDescriptor() { assertNull(BotRuntimeEndpoint.descriptor()) }

    @Test fun aStoppedServiceLeavesNoStaleCredential() {
        val lease = publish()
        lease.close()
        lease.close()
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test fun anOlderRunCannotClearItsReplacement() {
        val first = publish()
        val second = publish(secondUrl, secondToken)
        first.close()
        first.close()
        expectLive(secondUrl, secondToken)
        second.close()
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test fun identicalEndpointValuesStillBelongToDifferentRuns() {
        val first = publish()
        val second = publish()
        first.close()
        expectLive(firstUrl, firstToken)
        second.close()
        assertNull(BotRuntimeEndpoint.descriptor())
    }

    @Test fun aNewRunCanPublishAfterThePreviousRunRetired() {
        publish().close()
        publish(secondUrl, secondToken)
        expectLive(secondUrl, secondToken)
    }

    @Test fun closingAnOldRunConcurrentlyWithPublicationCannotClearTheNewRun() {
        val first = publish()
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val close = executor.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS)) { "endpoint_test_start_timeout" }
                first.close()
            }
            val replacement = executor.submit<BotRuntimeEndpoint.Lease> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS)) { "endpoint_test_start_timeout" }
                BotRuntimeEndpoint.publish(secondUrl, secondToken)
            }
            assertTrue("endpoint_test_ready_timeout", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            leases.add(replacement.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            expectLive(secondUrl, secondToken)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue("endpoint_test_worker_timeout", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun anEndpointThatIsNotALoopbackServiceIsRefusedWithoutReplacingTheLiveRun() {
        publish()
        for (url in listOf("http://10.0.0.1:8788", "http://127.0.0.1:0", "https://127.0.0.1:8788", "127.0.0.1:8788")) {
            try {
                publish(url)
                fail("bot_endpoint_expected_refusal")
            } catch (_: IllegalArgumentException) { }
            expectLive(firstUrl, firstToken)
        }
    }

    @Test fun anInvalidTokenIsRefusedWithoutReplacingTheLiveRun() {
        publish()
        for (token in listOf("", "short", "z".repeat(64))) {
            try {
                publish(token = token)
                fail("bot_endpoint_expected_refusal")
            } catch (_: IllegalArgumentException) { }
            expectLive(firstUrl, firstToken)
        }
    }

    @Test fun theOwnershipHandleDoesNotDescribeItsCredential() {
        val lease = publish()
        assertEquals("BotRuntimeEndpoint.Lease(REDACTED)", lease.toString())
        val snapshot = BotRuntimeEndpoint.descriptor()
        lease.close()
        assertNull(BotRuntimeEndpoint.descriptor())
        // Closing cannot revoke copies previously obtained by Dart or callers.
        assertTrue("endpoint_snapshot_changed", snapshot?.get("token") == firstToken)
    }
}
