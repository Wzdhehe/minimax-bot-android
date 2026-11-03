// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.Process
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ExecutionException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only evidence: JVM policy tests cannot prove Binder sendingUid,
 * isolatedProcess behavior, service visibility or real Context lifecycles. */
@RunWith(AndroidJUnit4::class)
class AgentIsolationClientTest {
    @Test fun cancellingConnectUnbindsAnAlreadyCreatedConnectionAndSkipsFurtherBinds() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val unbound = CountDownLatch(1)
        val binds = AtomicInteger()
        val tracked = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                val result = base.bindService(intent, connection, flags)
                if (binds.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return result
            }
            override fun unbindService(connection: ServiceConnection) {
                base.unbindService(connection)
                unbound.countDown()
            }
        }
        val future = AgentIsolationClient.connect(tracked)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(future.cancel(true))
        } finally { release.countDown() }
        assertTrue(unbound.await(5, TimeUnit.SECONDS))
        assertEquals(1, binds.get())
    }

    @Test fun concurrentTasksRemainCorrelatedAndACompetingLeaseCannotStealTheSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "isolation-concurrent-${UUID.randomUUID()}"
        val client = AgentIsolationClient.connect(context).get(15, TimeUnit.SECONDS)
        try {
            try {
                val unexpected = AgentIsolationClient.connect(context).get(15, TimeUnit.SECONDS)
                unexpected.closeAsync().get(5, TimeUnit.SECONDS)
                fail("competing session took an active lease")
            } catch (error: ExecutionException) { assertEquals(BrokerResponse.BUSY, error.cause?.message) }
            val writes = (0..7).map { index ->
                client.request(BrokerRequest(BrokerOp.WRITE_FILE, "$prefix-$index.txt", "value-$index"))
            }
            writes.forEach { assertTrue(it.get(15, TimeUnit.SECONDS).ok) }
            val reads = (7 downTo 0).map { index ->
                index to client.request(BrokerRequest(BrokerOp.READ_FILE, "$prefix-$index.txt"))
            }
            reads.forEach { (index, future) -> assertEquals("value-$index", future.get(15, TimeUnit.SECONDS).text) }
        } finally {
            client.closeAsync().get(5, TimeUnit.SECONDS)
            (0..7).forEach { File(File(context.filesDir, "workspace"), "$prefix-$it.txt").delete() }
        }
    }

    @Test fun privateHandshakeCorrelatesFileResultsAndRejectsOutsideWorkspace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "isolation-probe-${UUID.randomUUID()}.txt"
        val client = AgentIsolationClient.connect(context).get(15, TimeUnit.SECONDS)
        try {
            assertTrue(client.isolatedUid >= 0)
            assertNotEquals(Process.myUid(), client.isolatedUid)
            val write = client.request(BrokerRequest(BrokerOp.WRITE_FILE, name, "probe")).get(15, TimeUnit.SECONDS)
            assertTrue(write.ok)
            val read = client.request(BrokerRequest(BrokerOp.READ_FILE, name)).get(15, TimeUnit.SECONDS)
            assertTrue(read.ok)
            assertEquals("probe", read.text)
            val outside = client.request(BrokerRequest(BrokerOp.READ_FILE,
                "../shared_prefs/minimax_bot_provider.xml")).get(15, TimeUnit.SECONDS)
            assertFalse(outside.ok)
            assertNull(outside.text)
        } finally {
            client.closeAsync().get(5, TimeUnit.SECONDS)
            File(File(context.filesDir, "workspace"), name).delete()
        }
        assertFalse(client.request(BrokerRequest(BrokerOp.READ_FILE, name)).get(1, TimeUnit.SECONDS).ok)
    }
}
