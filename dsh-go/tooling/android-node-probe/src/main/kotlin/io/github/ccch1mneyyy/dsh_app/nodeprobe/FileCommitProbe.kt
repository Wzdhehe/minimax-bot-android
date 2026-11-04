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
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDeletionResult

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
        val moveOnly = RuntimeFileCommitClient(data.getBinder("move_host") ?: return stage, data.getString("move_session") ?: return stage)
        val replaceOnly = RuntimeFileCommitClient(data.getBinder("replace_host") ?: return stage, data.getString("replace_session") ?: return stage)
        stage = "exclusive"
        check(backup.publishIfAbsent("memory.bak", "original".toByteArray()) == RuntimePublicationResult.PUBLISHED)
        check(backup.publishIfAbsent("memory.bak", "replacement".toByteArray()) == RuntimePublicationResult.ALREADY_EXISTS)
        denied(RuntimeDataCode.DENIED) { backup.replace("memory.bak", byteArrayOf()) }
        denied(RuntimeDataCode.DENIED) { backup.move("memory.bak", "moved.bak", false) }
        denied(RuntimeDataCode.DENIED) { backup.remove("memory.bak") }
        denied(RuntimeDataCode.DENIED) { backup.append("memory.bak", byteArrayOf(1)) }
        stage = "replacement"
        check(state.publishIfAbsent("payload.bin", fileProbeBytes(false)) == RuntimePublicationResult.PUBLISHED)
        check(state.replace("payload.bin", fileProbeBytes(true)) == RuntimePublicationResult.PUBLISHED)
        check(state.publishIfAbsent("empty.bin", byteArrayOf()) == RuntimePublicationResult.PUBLISHED)
        stage = "read"
        denied(RuntimeDataCode.DENIED) { backup.read("memory.bak") }
        denied(RuntimeDataCode.DENIED) { backup.size("memory.bak") }
        check(state.read("payload.bin").contentEquals(fileProbeBytes(true)))
        check(state.size("payload.bin") == (84 * 1024).toLong())
        check(state.read("empty.bin").isEmpty())
        check(state.size("empty.bin") == 0L)
        denied(RuntimeDataCode.NOT_FOUND) { state.read("missing.bin") }
        denied(RuntimeDataCode.INVALID) { state.read("../outside") }
        stage = "mutation"
        state.publishIfAbsent("source.tmp", "source".toByteArray())
        check(state.move("source.tmp", "moved.json", false) == RuntimePublicationResult.PUBLISHED)
        state.publishIfAbsent("conflict.tmp", "replacement".toByteArray())
        check(state.move("conflict.tmp", "moved.json", false) == RuntimePublicationResult.ALREADY_EXISTS)
        check(state.read("conflict.tmp").toString(Charsets.UTF_8) == "replacement")
        denied(RuntimeDataCode.DENIED) { moveOnly.move("conflict.tmp", "moved.json", true) }
        denied(RuntimeDataCode.DENIED) { moveOnly.remove("conflict.tmp") }
        denied(RuntimeDataCode.DENIED) { replaceOnly.move("conflict.tmp", "moved.json", false) }
        denied(RuntimeDataCode.DENIED) { replaceOnly.move("conflict.tmp", "moved.json", true) }
        denied(RuntimeDataCode.DENIED) { replaceOnly.remove("conflict.tmp") }
        check(moveOnly.move("conflict.tmp", "moved.json", false) == RuntimePublicationResult.ALREADY_EXISTS)
        check(moveOnly.move("conflict.tmp", "spare.tmp", false) == RuntimePublicationResult.PUBLISHED)
        check(moveOnly.move("spare.tmp", "conflict.tmp", false) == RuntimePublicationResult.PUBLISHED)
        check(state.move("conflict.tmp", "moved.json", true) == RuntimePublicationResult.PUBLISHED)
        check(state.read("moved.json").toString(Charsets.UTF_8) == "replacement")
        check(state.remove("moved.json") == RuntimeDeletionResult.REMOVED)
        denied(RuntimeDataCode.NOT_FOUND) { state.remove("moved.json") }
        denied(RuntimeDataCode.INVALID) { state.move("../outside", "target.json", true) }
        // Bypass client name checks to exercise Main's new control-only wire.
        check(rawFileMutation(stateHost, stateSession, RuntimeFileCommitWire.MOVE, "../outside", "target.json") == RuntimeDataCode.INVALID.wire)
        check(rawFileMutation(stateHost, stateSession, RuntimeFileCommitWire.MOVE, "payload.bin", "../outside") == RuntimeDataCode.INVALID.wire)
        check(rawFileMutation(stateHost, stateSession, RuntimeFileCommitWire.REMOVE, "../outside") == RuntimeDataCode.INVALID.wire)
        check(rawFileMutation(stateHost, stateSession, RuntimeFileCommitWire.REMOVE, "payload.bin", "unexpected") == RuntimeDataCode.INVALID.wire)
        check(state.append("append.bin", byteArrayOf(0, -1)) == RuntimePublicationResult.PUBLISHED)
        check(state.append("append.bin", byteArrayOf(17)) == RuntimePublicationResult.PUBLISHED)
        check(state.read("append.bin").contentEquals(byteArrayOf(0, -1, 17)))
        denied(RuntimeDataCode.DENIED) { replaceOnly.append("append.bin", byteArrayOf(18)) }
        check(state.read("append.bin").contentEquals(byteArrayOf(0, -1, 17)))
        check(state.remove("append.bin") == RuntimeDeletionResult.REMOVED)
        stage = "nonce"
        val forged = (if (stateSession[0] == '0') "1" else "0") + stateSession.substring(1)
        denied(RuntimeDataCode.DENIED) { RuntimeFileCommitClient(stateHost, forged).publishIfAbsent("forged.bin", byteArrayOf()) }
        val forgedClient = RuntimeFileCommitClient(stateHost, forged)
        denied(RuntimeDataCode.DENIED) { forgedClient.move("payload.bin", "forged.bin", true) }
        denied(RuntimeDataCode.DENIED) { forgedClient.remove("payload.bin") }
        denied(RuntimeDataCode.DENIED) { forgedClient.append("payload.bin", byteArrayOf()) }
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
        val closed = RuntimeFileCommitClient(closedHost, closedSession)
        denied(RuntimeDataCode.CLOSED) { closed.move("source.tmp", "target.json", false) }
        denied(RuntimeDataCode.CLOSED) { closed.remove("source.tmp") }
        denied(RuntimeDataCode.CLOSED) { closed.append("source.tmp", byteArrayOf()) }
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

/** Retained only in this verification process to simulate an abandoned reader. */
internal object UnconsumedSnapshotProbe {
    @Volatile private var reader: ParcelFileDescriptor? = null
    fun open(data: Bundle): String {
        val host = data.getBinder("state_host") ?: return "output"
        val session = data.getString("state_session") ?: return "output"
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(RuntimeFileCommitWire.DESCRIPTOR)
            request.writeString(session); request.writeString("large.bin")
            check(host.transact(RuntimeFileCommitWire.READ, request, reply, 0))
            check(reply.readString() == "ok" && reply.readInt() == RuntimeFileCommitWire.MAX_BYTES)
            reader = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            "passed"
        } catch (_: Exception) { "output" }
        finally { request.recycle(); reply.recycle() }
    }
    fun close() { try { reader?.close() } catch (_: Exception) { }; reader = null }
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

private fun rawFileMutation(host: IBinder, session: String, op: Int, source: String, target: String? = null): String? {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(RuntimeFileCommitWire.DESCRIPTOR)
        data.writeString(session); data.writeString(source)
        if (target != null) data.writeString(target)
        check(host.transact(op, data, reply, 0))
        return reply.readString()
    } finally { data.recycle(); reply.recycle() }
}
