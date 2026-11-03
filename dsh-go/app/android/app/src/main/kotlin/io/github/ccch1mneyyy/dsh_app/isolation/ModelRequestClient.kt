// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerCode
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure

internal object ModelRequestWire {
    const val DESCRIPTOR = "io.github.ccch1mneyyy.dsh_app.ModelRequest.v1"
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION
    const val CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_WIRE_BYTES = 144 * 1024
}

/** Isolated-side capability received from the verified native owner, not from
 * Agent arguments. Call from a worker. Each returned FD has exactly one owner.
 * Production JNI async/cancel wiring is not implemented by this client. */
internal class ModelRequestClient(private val host: IBinder, private val session: String) {
    fun open(id: String, body: String): ParcelFileDescriptor {
        if (body.toByteArray(Charsets.UTF_8).size !in 1..ModelRequestWire.MAX_BODY_BYTES) {
            throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
        }
        return transact(ModelRequestWire.OPEN, id, body) { reply ->
            val code = reply.readString()
            if (code != "ok") throw failure(code)
            ParcelFileDescriptor.CREATOR.createFromParcel(reply)
        }
    }
    fun cancel(id: String) {
        transact(ModelRequestWire.CANCEL, id, null) { reply ->
            if (reply.readString() != ModelBrokerCode.CANCELLED.wire) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
        }
    }
    private fun <T> transact(code: Int, id: String, body: String?, decode: (Parcel) -> T): T {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) || !session.matches(Regex("[0-9a-f]{64}"))) {
            throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ModelRequestWire.DESCRIPTOR)
            data.writeString(session); data.writeString(id)
            if (body != null) data.writeString(body)
            if (!host.transact(code, data, reply, 0)) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
            return decode(reply)
        } catch (error: Exception) {
            throw if (error is ModelBrokerFailure) error else ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
        } finally { data.recycle(); reply.recycle() }
    }
    private fun failure(code: String?) = ModelBrokerFailure(ModelBrokerCode.entries.firstOrNull { it.wire == code } ?: ModelBrokerCode.UNAVAILABLE)
    override fun toString() = "ModelRequestClient(REDACTED)"
}
