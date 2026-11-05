// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import io.github.ccch1mneyyy.dsh_app.runtime.BotLoopbackBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BotLoopbackBridgeTest {
    private class Peer(private val socket: Socket, buffered: Boolean = false) : BotLoopbackBridge.Connection {
        override val input get() = socket.getInputStream()
        override val output = if (buffered) socket.getOutputStream().buffered() else socket.getOutputStream()
        override fun shutdownOutput() = socket.shutdownOutput()
        override fun close() = socket.close()
    }
    private fun client(port: Int) = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
    private fun server() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3000 }
    private fun worker() = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }

    @Test(timeout = 10000) fun bindingDoesNotGrantConnectionsBeforeActivation() {
        val calls = AtomicInteger()
        BotLoopbackBridge.bind { calls.incrementAndGet(); error("unexpected_grant") }.use { bridge ->
            client(bridge.port).use { assertEquals(-1, it.getInputStream().read()) }
            assertEquals(0, calls.get())
            assertEquals("BotLoopbackBridge(REDACTED)", bridge.toString())
        }
    }

    @Test(timeout = 10000) fun binaryTrafficAndRequestHalfClosePreserveTheResponse() {
        server().use { remote ->
            val executor = worker()
            try {
                val bytes = ByteArray(84 * 1024) { (it % 251).toByte() }
                val served = executor.submit {
                    remote.accept().use { socket ->
                        socket.soTimeout = 3000
                        assertArrayEquals(bytes, socket.getInputStream().readBytes())
                        socket.getOutputStream().write(bytes.reversedArray())
                    }
                }
                BotLoopbackBridge.bind { Peer(client(remote.localPort)) }.use { bridge ->
                    bridge.activate()
                    client(bridge.port).use { socket ->
                        socket.getOutputStream().write(bytes)
                        socket.shutdownOutput()
                        assertArrayEquals(bytes.reversedArray(), socket.getInputStream().readBytes())
                    }
                }
                served.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun responseStreamingDoesNotWaitForClientEof() {
        server().use { remote ->
            val executor = worker()
            try {
                val served = executor.submit {
                    remote.accept().use { socket ->
                        socket.soTimeout = 3000
                        socket.getOutputStream().write("hello".toByteArray())
                        socket.getOutputStream().flush()
                        assertEquals(42, socket.getInputStream().read())
                        socket.getOutputStream().write("world".toByteArray())
                    }
                }
                BotLoopbackBridge.bind { Peer(client(remote.localPort), buffered = true) }.use { bridge ->
                    bridge.activate()
                    client(bridge.port).use { socket ->
                        assertArrayEquals("hello".toByteArray(), socket.getInputStream().readNBytes(5))
                        socket.getOutputStream().write(42)
                        assertArrayEquals("world".toByteArray(), socket.getInputStream().readNBytes(5))
                        socket.shutdownOutput()
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                served.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test(timeout = 10000) fun capacityAndShutdownCloseClientsAndLateGrants() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lateClosed = CountDownLatch(1)
        val calls = AtomicInteger()
        val bridge = BotLoopbackBridge.bind(capacity = 1) {
            calls.incrementAndGet()
            entered.countDown()
            // Model foreign IPC which returns after cancellation/interruption.
            var done = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!done && System.nanoTime() < deadline) {
                try { done = release.await(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
            }
            check(done) { "test_grant_timeout" }
            object : BotLoopbackBridge.Connection {
                override val input = ByteArrayInputStream(byteArrayOf())
                override val output = ByteArrayOutputStream()
                override fun shutdownOutput() { }
                override fun close() { lateClosed.countDown() }
            }
        }
        try {
            bridge.activate()
            client(bridge.port).use { first ->
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                client(bridge.port).use { second -> assertEquals(-1, second.getInputStream().read()) }
                assertEquals(1, calls.get())
                bridge.close()
                assertEquals(-1, first.getInputStream().read())
                release.countDown()
                assertTrue(lateClosed.await(3, TimeUnit.SECONDS))
            }
        } finally { release.countDown(); bridge.close() }
    }

    @Test(timeout = 10000) fun closingTheBridgeInterruptsAnEstablishedStream() {
        server().use { remote ->
            val executor = worker()
            try {
                val served = executor.submit {
                    remote.accept().use { socket ->
                        socket.soTimeout = 3000
                        socket.getOutputStream().write(42)
                        socket.getOutputStream().flush()
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                BotLoopbackBridge.bind { Peer(client(remote.localPort)) }.use { bridge ->
                    bridge.activate()
                    client(bridge.port).use { socket ->
                        assertEquals(42, socket.getInputStream().read())
                        bridge.close()
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                served.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun aRetiredBridgeCannotActivateAgain() {
        val bridge = BotLoopbackBridge.bind { error("unexpected_grant") }
        bridge.close()
        bridge.close()
        try { bridge.activate(); fail("bridge_should_be_closed") } catch (_: IllegalStateException) { }
    }
}
