// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.nodeprobe

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataCode
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataFailure
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitClient
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitWire
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimePublicationResult

/** Fixed verification-only matrix, never an Agent-supplied script or path. */
internal fun runFileCommitProbe(data: Bundle): String {
    var stage = "setup"
    fun denied(code: RuntimeDataCode, block: () -> Unit) {
        try { block(); error("file_probe_expected_refusal") }
        catch (error: RuntimeDataFailure) { check(error.code == code && error.cause == null) }
    }
    try {
        val backupHost = data.getBinder("backup_host") ?: return stage
        val backupSession = data.getString("backup_session") ?: return stage
        val stateHost = data.getBinder("state_host") ?: return stage
        val stateSession = data.getString("state_session") ?: return stage
        val closedHost = data.getBinder("closed_host") ?: return stage
        val closedSession = data.getString("closed_session") ?: return stage
        val backup = RuntimeFileCommitClient(backupHost, backupSession)
        val state = RuntimeFileCommitClient(stateHost, stateSession)
        stage = "exclusive"
        check(backup.publishIfAbsent("memory.bak", "original".toByteArray()) == RuntimePublicationResult.PUBLISHED)
        check(backup.publishIfAbsent("memory.bak", "replacement".toByteArray()) == RuntimePublicationResult.ALREADY_EXISTS)
        denied(RuntimeDataCode.DENIED) { backup.replace("memory.bak", byteArrayOf()) }
        stage = "replacement"
        check(state.publishIfAbsent("payload.bin", fileProbeBytes(false)) == RuntimePublicationResult.PUBLISHED)
        check(state.replace("payload.bin", fileProbeBytes(true)) == RuntimePublicationResult.PUBLISHED)
        check(state.publishIfAbsent("empty.bin", byteArrayOf()) == RuntimePublicationResult.PUBLISHED)
        stage = "nonce"
        val forged = (if (stateSession[0] == '0') "1" else "0") + stateSession.substring(1)
        denied(RuntimeDataCode.DENIED) { RuntimeFileCommitClient(stateHost, forged).publishIfAbsent("forged.bin", byteArrayOf()) }
        stage = "path"
        for (name in listOf("../outside", "/private/key", ".native-publish-reserved.tmp", "subdir/file")) {
            denied(RuntimeDataCode.INVALID) { state.publishIfAbsent(name, byteArrayOf()) }
        }
        // Also bypass client validation so the real Main boundary is exercised.
        val empty = ParcelFileDescriptor.createPipe()
        empty[1].close()
        empty[0].use {
            check(rawFileCommit(stateHost, stateSession, "../outside", 0, it) == RuntimeDataCode.INVALID.wire)
        }
        stage = "pipe_shape"
        ParcelFileDescriptor.createSocketPair().let { sockets ->
            try { check(rawFileCommit(stateHost, stateSession, "socket.bin", 0, sockets[0]) == RuntimeDataCode.INVALID.wire) }
            finally { sockets.forEach { it.close() } }
        }
        stage = "truncated"
        ParcelFileDescriptor.createPipe().let { pipe ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(byteArrayOf(1, 2)) }
                check(rawFileCommit(stateHost, stateSession, "short.bin", 3, pipe[0]) == RuntimeDataCode.INVALID.wire)
            } finally { pipe.forEach { it.close() } }
        }
        stage = "overlong"
        ParcelFileDescriptor.createPipe().let { pipe ->
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(byteArrayOf(1, 2, 3, 4)) }
                check(rawFileCommit(stateHost, stateSession, "long.bin", 3, pipe[0]) == RuntimeDataCode.INVALID.wire)
            } finally { pipe.forEach { it.close() } }
        }
        stage = "oversize"
        ParcelFileDescriptor.createPipe().let { pipe ->
            try {
                pipe[1].close()
                check(rawFileCommit(stateHost, stateSession, "large.bin", RuntimeFileCommitWire.MAX_BYTES + 1, pipe[0]) == RuntimeDataCode.INVALID.wire)
            } finally { pipe.forEach { it.close() } }
        }
        stage = "timeout"
        ParcelFileDescriptor.createPipe().let { pipe ->
            try { check(rawFileCommit(stateHost, stateSession, "stalled.bin", 1, pipe[0]) == RuntimeDataCode.UNAVAILABLE.wire) }
            finally { pipe.forEach { it.close() } }
        }
        stage = "closed"
        denied(RuntimeDataCode.CLOSED) { RuntimeFileCommitClient(closedHost, closedSession).publishIfAbsent("late.bin", byteArrayOf()) }
        return "passed"
    } catch (_: Exception) { return stage }
}

internal fun fileProbeBytes(replacement: Boolean) = ByteArray(84 * 1024) { i -> (if (replacement) 255 - i % 251 else i % 251).toByte() }

/** Keep a request incomplete until Main revokes its host; no timing sleeps. */
internal fun runFileCommitDrainProbe(data: Bundle): String {
    val host = data.getBinder("state_host") ?: return "drain"
    val session = data.getString("state_session") ?: return "drain"
    val pipe = ParcelFileDescriptor.createPipe()
    return try {
        if (rawFileCommit(host, session, "drain.bin", 1, pipe[0]) == RuntimeDataCode.CLOSED.wire) "passed" else "drain"
    } catch (_: Exception) { "drain" }
    finally { pipe.forEach { try { it.close() } catch (_: Exception) { } } }
}

private fun rawFileCommit(host: IBinder, session: String, name: String, length: Int, body: ParcelFileDescriptor): String? {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(RuntimeFileCommitWire.DESCRIPTOR)
        data.writeString(session); data.writeString(name); data.writeInt(length)
        body.writeToParcel(data, 0)
        check(host.transact(RuntimeFileCommitWire.PUBLISH, data, reply, 0))
        return reply.readString()
    } finally { data.recycle(); reply.recycle() }
}
