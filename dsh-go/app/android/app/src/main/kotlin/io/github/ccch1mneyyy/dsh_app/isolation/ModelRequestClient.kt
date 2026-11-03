// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerCode
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure

internal object ModelRequestWire {
    const val DESCRIPTOR = "io.github.ccch1mneyyy.dsh_app.ModelRequest.v1"
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION
    const val CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1
    const val OPEN_FD = IBinder.FIRST_CALL_TRANSACTION + 2
    const val MAX_INLINE_BODY_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 1024 * 1024
    const val MAX_WIRE_BYTES = 144 * 1024
}

/** Isolated-side capability received from the verified native owner, not from
 * Agent arguments. Call from a worker. Each returned FD has exactly one owner.
 * Production JNI async/cancel wiring is not implemented by this client. */
internal class ModelRequestClient(private val host: IBinder, private val session: String) {
    companion object {
        private val writerSlots = Semaphore(4)
        private val writers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { task -> Thread(task, "mbot-request-body").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
    fun open(id: String, body: String): ParcelFileDescriptor {
        if (body.length !in 1..ModelRequestWire.MAX_BODY_BYTES) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..ModelRequestWire.MAX_BODY_BYTES) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            if (bytes.size > ModelRequestWire.MAX_INLINE_BODY_BYTES) {
                if (!writerSlots.tryAcquire()) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
                try {
                    val created = ParcelFileDescriptor.createPipe()
                    pipe = created
                    val output = created[1]
                    writers.execute {
                        try { ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(bytes) } }
                        catch (_: Exception) { /* The host reports truncated input with a stable code. */ }
                        finally { writerSlots.release() }
                    }
                } catch (_: Exception) { writerSlots.release(); throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE) }
            }
            val requestPipe = pipe
            return transact(if (requestPipe == null) ModelRequestWire.OPEN else ModelRequestWire.OPEN_FD, id, { data ->
                if (requestPipe == null) data.writeString(body) else { data.writeInt(bytes.size); requestPipe[0].writeToParcel(data, 0) }
            }) { reply ->
                val code = reply.readString()
                if (code != "ok") throw failure(code)
                ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            }
        } finally { pipe?.forEach { try { it.close() } catch (_: Exception) { } } }
    }
    fun cancel(id: String) {
        transact(ModelRequestWire.CANCEL, id, {}) { reply ->
            if (reply.readString() != ModelBrokerCode.CANCELLED.wire) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
        }
    }
    private fun <T> transact(code: Int, id: String, encode: (Parcel) -> Unit, decode: (Parcel) -> T): T {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !session.matches(Regex("[0-9a-f]{64}"))) {
            throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ModelRequestWire.DESCRIPTOR)
            data.writeString(session); data.writeString(id)
            encode(data)
            if (!host.transact(code, data, reply, 0)) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
            return decode(reply)
        } catch (error: Exception) {
            throw if (error is ModelBrokerFailure) error else ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
        } finally { data.recycle(); reply.recycle() }
    }
    private fun failure(code: String?) = ModelBrokerFailure(ModelBrokerCode.entries.firstOrNull { it.wire == code } ?: ModelBrokerCode.UNAVAILABLE)
    override fun toString() = "ModelRequestClient(REDACTED)"
}
