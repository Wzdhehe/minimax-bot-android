// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor

internal object RuntimeSnapshotWire {
    const val DESCRIPTOR = "io.github.ccch1mneyyy.dsh_app.RuntimeSnapshot.v1"
    const val COPY = 1
    const val CATALOG = 2
    const val OPEN = 3
    const val REMOVE = 4
    const val MAX_REQUEST = 4096
    const val MAX_REPLY = 8192
}

/** Isolated-side capability supplied by the private launcher, never a tool
 * argument. No root/source/UID/PID/budget selector and no automatic retry.
 * Call from a worker; Main checks the real Binder caller and current session.
 */
internal class RuntimeSnapshotClient(private val host: IBinder, private val session: String) {
    fun copy(fileName: String): String = query(RuntimeSnapshotWire.COPY, fileName) { reply ->
        reply.readString().takeIf { it in setOf("published", "already_exists", "not_published", "outcome_unknown") }
            ?: throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
    }
    fun catalogJson(): String = query(RuntimeSnapshotWire.CATALOG) { reply ->
        requireOk(reply)
        reply.readString() ?: throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
    }
    fun openReadOnly(fileName: String, identity: String): ParcelFileDescriptor = query(RuntimeSnapshotWire.OPEN, fileName, identity) { reply ->
        requireOk(reply)
        ParcelFileDescriptor.CREATOR.createFromParcel(reply)
    }
    fun remove(fileName: String, identity: String): String = query(RuntimeSnapshotWire.REMOVE, fileName, identity) { reply ->
        reply.readString().takeIf { it == "removed" || it == "outcome_unknown" }
            ?: throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
    }
    private fun requireOk(reply: Parcel) {
        if (reply.readString() != "ok") throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
    }
    private fun <T> query(op: Int, fileName: String? = null, identity: String? = null, decode: (Parcel) -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) throw RuntimeDataFailure(RuntimeDataCode.WORKER_REQUIRED)
        if (!session.matches(Regex("[0-9a-f]{64}")) ||
            (fileName != null && !RuntimeSnapshotOwner.validName(fileName)) ||
            (identity != null && !identity.matches(Regex("[0-9a-f]{32}")))) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        var result: T? = null
        try {
            request.writeInterfaceToken(RuntimeSnapshotWire.DESCRIPTOR)
            request.writeString(session)
            if (fileName != null) request.writeString(fileName)
            if (identity != null) request.writeString(identity)
            if (!host.transact(op, request, reply, 0) || reply.dataSize() > RuntimeSnapshotWire.MAX_REPLY) {
                throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
            reply.readException()
            result = decode(reply)
            if (reply.dataAvail() != 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            return result
        } catch (failure: Exception) {
            // A malformed reply must not leak an already decoded descriptor.
            if (result is ParcelFileDescriptor) try { result.close() } catch (_: Exception) { }
            throw if (failure is RuntimeDataFailure) failure else RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } finally { request.recycle(); reply.recycle() }
    }
    override fun toString() = "RuntimeSnapshotClient(REDACTED)"
}
