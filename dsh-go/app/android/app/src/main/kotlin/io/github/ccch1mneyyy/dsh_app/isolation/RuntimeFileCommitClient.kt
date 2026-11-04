// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object RuntimeFileCommitWire {
    const val DESCRIPTOR = "io.github.ccch1mneyyy.dsh_app.RuntimeFileCommit.v1"
    const val PUBLISH = IBinder.FIRST_CALL_TRANSACTION
    const val REPLACE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val READ = IBinder.FIRST_CALL_TRANSACTION + 2
    const val SIZE = IBinder.FIRST_CALL_TRANSACTION + 3
    const val MAX_BYTES = 1024 * 1024
    const val MAX_WIRE_BYTES = 8 * 1024
    fun validTarget(name: String) = RuntimePrivateDirectory.validName(name) && !name.startsWith(".native-publish-")
}

/** Worker-only isolated client. A trusted native launcher supplies the bound
 * capability; there is no root/provider/key selector in the request. Binary
 * bodies always use a pipe, never a large Binder byte array. This is not node:fs.
 */
internal class RuntimeFileCommitClient(private val host: IBinder, private val session: String) {
    companion object {
        private val slots = Semaphore(4)
        private val writers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { task -> Thread(task, "mbot-file-body").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
    fun publishIfAbsent(name: String, bytes: ByteArray) = commit(RuntimeFileCommitWire.PUBLISH, name, bytes)
    fun replace(name: String, bytes: ByteArray) = commit(RuntimeFileCommitWire.REPLACE, name, bytes)
    fun read(name: String): ByteArray = query(RuntimeFileCommitWire.READ, name) { reply ->
        if (reply.dataAvail() < 4) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        val length = reply.readInt()
        if (length !in 0..RuntimeFileCommitWire.MAX_BYTES) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        ParcelFileDescriptor.CREATOR.createFromParcel(reply).use { input ->
            if (reply.dataAvail() != 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            readBoundedRequestPipe(input, length, RuntimeFileCommitWire.MAX_BYTES, 5_000) { false }
        }
    }
    fun size(name: String): Long = query(RuntimeFileCommitWire.SIZE, name) { reply ->
        if (reply.dataAvail() != 8) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        reply.readLong().also { if (it < 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
    }

    private fun validate(name: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) throw RuntimeDataFailure(RuntimeDataCode.WORKER_REQUIRED)
        if (!session.matches(Regex("[0-9a-f]{64}")) || !RuntimeFileCommitWire.validTarget(name)) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
    }
    private fun <T> query(op: Int, name: String, decode: (Parcel) -> T): T {
        validate(name)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(RuntimeFileCommitWire.DESCRIPTOR)
            data.writeString(session); data.writeString(name)
            if (!host.transact(op, data, reply, 0) || reply.dataSize() > RuntimeFileCommitWire.MAX_WIRE_BYTES) {
                throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
            val code = reply.readString()
            if (code != "ok") throw RuntimeDataFailure(RuntimeDataCode.entries.firstOrNull { it.wire == code } ?: RuntimeDataCode.UNAVAILABLE)
            return decode(reply)
        } catch (error: Exception) {
            throw if (error is RuntimeDataFailure) error else RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } finally { data.recycle(); reply.recycle() }
    }

    private fun commit(op: Int, name: String, bytes: ByteArray): RuntimePublicationResult {
        validate(name)
        if (bytes.size > RuntimeFileCommitWire.MAX_BYTES) {
            throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        }
        if (!slots.tryAcquire()) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        var scheduled = false
        var pipe: Array<ParcelFileDescriptor>? = null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var attempted = false
        try {
            val snapshot = bytes.copyOf()
            val created = ParcelFileDescriptor.createPipe()
            pipe = created
            writers.execute {
                try { ParcelFileDescriptor.AutoCloseOutputStream(created[1]).use { it.write(snapshot) } }
                catch (_: Exception) { /* Host reports truncated/closed input, never platform details. */ }
                finally { slots.release() }
            }
            scheduled = true
            data.writeInterfaceToken(RuntimeFileCommitWire.DESCRIPTOR)
            data.writeString(session); data.writeString(name); data.writeInt(snapshot.size)
            created[0].writeToParcel(data, 0)
            attempted = true
            if (!host.transact(op, data, reply, 0)) return RuntimePublicationResult.OUTCOME_UNKNOWN
            val code = reply.readString()
            if (reply.dataAvail() != 0) return RuntimePublicationResult.OUTCOME_UNKNOWN
            return when (code) {
                "published" -> RuntimePublicationResult.PUBLISHED
                "already_exists" -> if (op == RuntimeFileCommitWire.PUBLISH) RuntimePublicationResult.ALREADY_EXISTS else RuntimePublicationResult.OUTCOME_UNKNOWN
                "outcome_unknown" -> RuntimePublicationResult.OUTCOME_UNKNOWN
                else -> throw RuntimeDataFailure(RuntimeDataCode.entries.firstOrNull { it.wire == code }
                    ?: return RuntimePublicationResult.OUTCOME_UNKNOWN)
            }
        } catch (error: Exception) {
            if (error is RuntimeDataFailure) throw error
            if (attempted) return RuntimePublicationResult.OUTCOME_UNKNOWN
            throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        } finally {
            pipe?.forEach { try { it.close() } catch (_: Exception) { } }
            if (!scheduled) slots.release()
            data.recycle(); reply.recycle()
        }
    }
    override fun toString() = "RuntimeFileCommitClient(REDACTED)"
}
