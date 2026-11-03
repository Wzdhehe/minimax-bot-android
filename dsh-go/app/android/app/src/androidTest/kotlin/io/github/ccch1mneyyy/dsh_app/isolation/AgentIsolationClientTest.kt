// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only evidence: JVM policy tests cannot prove Binder sendingUid,
 * isolatedProcess behavior, service visibility or real Context lifecycles. */
@RunWith(AndroidJUnit4::class)
class AgentIsolationClientTest {
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
            client.close()
            File(File(context.filesDir, "workspace"), name).delete()
        }
        assertFalse(client.request(BrokerRequest(BrokerOp.READ_FILE, name)).get(1, TimeUnit.SECONDS).ok)
    }
}
