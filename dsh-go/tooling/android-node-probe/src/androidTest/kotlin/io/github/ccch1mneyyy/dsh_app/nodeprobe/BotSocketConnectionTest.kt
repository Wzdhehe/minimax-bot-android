// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ccch1mneyyy.dsh_app.runtime.BotLoopbackBridge
import io.github.ccch1mneyyy.dsh_app.runtime.BotSocketConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BotSocketConnectionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun duplicate(fd: ParcelFileDescriptor) = fd.dup().also {
        Os.setsockoptTimeval(it.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
            StructTimeval.fromMillis(5000))
        Os.setsockoptTimeval(it.fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
            StructTimeval.fromMillis(5000))
    }

    @Test(timeout = 20_000) fun socketpairPreservesBinaryRequestHalfCloseAndResponse() {
        val worker = Executors.newSingleThreadExecutor()
        val peer = AtomicReference<ParcelFileDescriptor?>()
        val offered = AtomicReference<ParcelFileDescriptor?>()
        val request = ByteArray(84 * 1024) { (it % 251).toByte() }
        val response = request.reversedArray()
        val result = AtomicReference<java.util.concurrent.Future<*>?>()
        try {
            BotLoopbackBridge.bind {
                BotSocketConnection.open(context) { fd ->
                    offered.set(fd)
                    val owned = duplicate(fd).also { peer.set(it) }
                    result.set(worker.submit {
                        val bytes = ByteArrayOutputStream()
                        val chunk = ByteArray(4096)
                        while (true) {
                            val count = Os.read(owned.fileDescriptor, chunk, 0, chunk.size)
                            if (count == 0) break
                            check(bytes.size() + count <= request.size)
                            bytes.write(chunk, 0, count)
                        }
                        assertArrayEquals(request, bytes.toByteArray())
                        var offset = 0
                        while (offset < response.size) {
                            val count = Os.write(owned.fileDescriptor, response, offset, response.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        Os.shutdown(owned.fileDescriptor, OsConstants.SHUT_WR)
                    })
                }
            }.use { bridge ->
                bridge.activate()
                Socket(InetAddress.getByName("127.0.0.1"), bridge.port).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write(request)
                    client.shutdownOutput()
                    val bytes = ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) {
                        val count = client.getInputStream().read(chunk)
                        if (count < 0) break
                        check(bytes.size() + count <= response.size)
                        bytes.write(chunk, 0, count)
                    }
                    assertArrayEquals(response, bytes.toByteArray())
                }
                result.get()!!.get(5, TimeUnit.SECONDS)
                assertFalse(offered.get()!!.fileDescriptor.valid())
            }
        } finally {
            peer.get()?.close()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 15_000) fun streamingDoesNotWaitForRequestEofAndCloseWakesBothPeers() {
        val worker = Executors.newSingleThreadExecutor()
        val peer = AtomicReference<ParcelFileDescriptor?>()
        val result = AtomicReference<java.util.concurrent.Future<*>?>()
        try {
            BotLoopbackBridge.bind {
                BotSocketConnection.open(context) { fd ->
                    val owned = duplicate(fd).also { peer.set(it) }
                    result.set(worker.submit {
                        assertEquals(1, Os.read(owned.fileDescriptor, ByteArray(1), 0, 1))
                        val event = "data: ready\n\n".toByteArray()
                        var offset = 0
                        while (offset < event.size) {
                            val count = Os.write(owned.fileDescriptor, event, offset, event.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        // The client has not sent EOF. Main bridge.close must
                        // wake this blocked read as well as its own FD read.
                        assertEquals(0, Os.read(owned.fileDescriptor, ByteArray(1), 0, 1))
                    })
                }
            }.use { bridge ->
                bridge.activate()
                Socket(InetAddress.getByName("127.0.0.1"), bridge.port).use { client ->
                    client.soTimeout = 5000
                    client.getOutputStream().write(1)
                    val event = ByteArray("data: ready\n\n".length)
                    java.io.DataInputStream(client.getInputStream()).readFully(event)
                    assertEquals("data: ready\n\n", event.toString(Charsets.UTF_8))
                    bridge.close()
                    assertEquals(-1, client.getInputStream().read())
                    result.get()!!.get(5, TimeUnit.SECONDS)
                    bridge.close()
                }
            }
        } finally {
            peer.get()?.close()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 10_000) fun failedGrantClosesBothLocalCopiesAndReturnsOnlyStableError() {
        var offered: ParcelFileDescriptor? = null
        var peer: ParcelFileDescriptor? = null
        try {
            try {
                BotSocketConnection.open(context) { fd ->
                    offered = fd
                    peer = duplicate(fd)
                    throw IllegalStateException("internal-grant-detail-must-not-escape")
                }
                fail("grant_failure_expected")
            } catch (error: IOException) {
                assertEquals("bot_connection_unavailable", error.message)
                assertNull(error.cause)
            }
            assertFalse(offered!!.fileDescriptor.valid())
            assertEquals(0, Os.read(peer!!.fileDescriptor, ByteArray(1), 0, 1))
        } finally { peer?.close() }
    }
}
