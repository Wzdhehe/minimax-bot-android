// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Main-only, one runtime scope. A trusted launcher supplies isolatedUid from
 * a correlated private handshake's REAL sendingUid/Binder identity, never a
 * payload. The native backup worker has a DIFFERENT UID and cannot use this
 * guest capability. Only basenames/opaque identities cross; never RW/dir FDs.
 */
internal class RuntimeSnapshotHost private constructor(
    private val ownerUid: Int,
    private val isolatedUid: Int,
    private val session: String,
    private val gate: IsolationSessionGate,
    private val owner: RuntimeSnapshotOwner,
) : Binder(), Closeable {
    private val closed = AtomicBoolean(false)
    private val slot = Semaphore(1)
    init { attachInterface(null, RuntimeSnapshotWire.DESCRIPTOR) }
    private fun permitted(uid: Int, nonce: String?) = !closed.get() && gate.permits(uid, nonce)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in RuntimeSnapshotWire.COPY..RuntimeSnapshotWire.REMOVE) return super.onTransact(code, data, reply, flags)
        val uid = getCallingUid() // Read identity before inspecting untrusted fields.
        if (reply == null) return true
        if (uid != isolatedUid || Process.myUid() != ownerUid || !SqliteBackupWire.synchronous(flags, true) ||
            data.dataSize() > RuntimeSnapshotWire.MAX_REQUEST || data.hasFileDescriptors()) {
            respond(reply, "denied"); return true
        }
        var acquired = false
        var callingIdentity: Long? = null
        try {
            data.enforceInterface(RuntimeSnapshotWire.DESCRIPTOR)
            val nonce = data.readString()
            if (!permitted(uid, nonce)) { respond(reply, "closed"); return true }
            val name = if (code == RuntimeSnapshotWire.CATALOG) null else data.readString()
            val identity = if (code == RuntimeSnapshotWire.OPEN || code == RuntimeSnapshotWire.REMOVE) data.readString() else null
            if (data.dataAvail() != 0 || (code != RuntimeSnapshotWire.CATALOG && (name == null || !RuntimeSnapshotOwner.validName(name))) ||
                ((code == RuntimeSnapshotWire.OPEN || code == RuntimeSnapshotWire.REMOVE) &&
                    (identity == null || !identity.matches(Regex("[0-9a-f]{32}"))))) {
                respond(reply, "invalid"); return true
            }
            acquired = slot.tryAcquire()
            if (!acquired) { respond(reply, "busy"); return true }
            callingIdentity = clearCallingIdentity() // Authentication above; trusted Main I/O below.
            when (code) {
                RuntimeSnapshotWire.COPY -> {
                    val outcome = owner.create(name!!)
                    respond(reply, if (!permitted(uid, nonce)) "closed" else when (outcome) {
                        RuntimeSnapshotOwner.Status.PUBLISHED -> "published"
                        RuntimeSnapshotOwner.Status.ALREADY_EXISTS -> "already_exists"
                        RuntimeSnapshotOwner.Status.NOT_PUBLISHED -> "not_published"
                        RuntimeSnapshotOwner.Status.OUTCOME_UNKNOWN -> "outcome_unknown"
                    })
                }
                RuntimeSnapshotWire.CATALOG -> {
                    val entries = JSONArray()
                    owner.catalog().forEach { info -> entries.put(JSONObject().put("fileName", info.fileName)
                        .put("bytes", info.bytes).put("createdAtMs", info.createdAtMs).put("identity", info.identity)) }
                    val text = entries.toString()
                    if (!permitted(uid, nonce)) respond(reply, "closed")
                    else if (text.toByteArray(Charsets.UTF_8).size > 4096) respond(reply, "too_large")
                    else respond(reply, "ok") { writeString(text) }
                }
                RuntimeSnapshotWire.OPEN -> owner.openReadOnly(name!!, identity!!).use { file ->
                    if (!permitted(uid, nonce)) respond(reply, "closed")
                    else respond(reply, "ok") { file.writeToParcel(this, Parcelable.PARCELABLE_WRITE_RETURN_VALUE) }
                }
                RuntimeSnapshotWire.REMOVE -> {
                    val result = owner.removeCreated(name!!, identity!!)
                    respond(reply, if (!permitted(uid, nonce)) "closed" else if (result == RuntimeDeletionResult.REMOVED) "removed" else "outcome_unknown")
                }
            }
        } catch (failure: RuntimeDataFailure) {
            respond(reply, when (failure.code) {
                RuntimeDataCode.INVALID -> "invalid"; RuntimeDataCode.CLOSED -> "closed"
                RuntimeDataCode.BUSY -> "busy"; RuntimeDataCode.TOO_LARGE -> "too_large"
                RuntimeDataCode.NOT_FOUND -> "not_found"; RuntimeDataCode.DENIED -> "denied"
                else -> "unavailable"
            })
        } catch (_: Exception) { respond(reply, "outcome_unknown") }
        catch (_: LinkageError) { respond(reply, "unavailable") }
        finally { callingIdentity?.let { restoreCallingIdentity(it) }; if (acquired) slot.release() }
        return true
    }
    override fun close() {
        closed.set(true)
        gate.revoke(ownerUid, session)
        owner.close() // Nonblocking cancellation; never waits on the RPC slot.
    }
    override fun toString() = "RuntimeSnapshotHost(REDACTED)"
    private fun respond(reply: Parcel, code: String, body: Parcel.() -> Unit = {}) {
        reply.setDataPosition(0); reply.setDataSize(0)
        reply.writeNoException(); reply.writeString(code); reply.body()
    }
    companion object {
        fun open(context: Context, source: RuntimeDatabaseFiles, isolatedUid: Int, session: String): RuntimeSnapshotHost {
            RuntimePrivateDirectory.requireOwnerWorker(context)
            val uid = Process.myUid()
            val gate = IsolationSessionGate(uid)
            if (isolatedUid <= 0 || gate.register(uid, isolatedUid, session) != BrokerResponse.OK) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            val owner = RuntimeSnapshotOwner.open(context, source) { gate.permits(isolatedUid, session) }
            var transferred = false
            try {
                val host = RuntimeSnapshotHost(uid, isolatedUid, session, gate, owner)
                transferred = true
                return host
            } finally { if (!transferred) owner.close() }
        }
    }
}
