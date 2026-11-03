// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.bot

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.ErrnoException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import io.github.ccch1mneyyy.dsh_app.isolation.ModelRequestWire
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerCode
import io.github.ccch1mneyyy.dsh_app.model.ModelBrokerFailure
import io.github.ccch1mneyyy.dsh_app.model.requireNativeJsonBudget
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Native-only per-runtime model capability. Bound to an observed isolated UID,
 * one trusted provider/model and a peer Binder lifetime. No Key/URL/header API.
 * Synchronous Binder work runs off the UI thread; at most four opens in flight.
 * Inline bodies are limited to 64KiB; larger requests use a bounded 1MiB
 * pipe with exact length, EOF and an absolute read deadline.
 */
internal class NativeModelRequestHost(
    private val isolatedUid: Int,
    private val providerId: String,
    private val modelId: String,
    private val peer: IBinder,
    private val openChannel: (String) -> ParcelFileDescriptor,
    private val closeChannels: () -> Unit,
    private val readTimeoutMs: Long = 5_000,
) : Binder(), Closeable {
    val session = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val closed = AtomicBoolean()
    private val lock = Any()
    private val pending = mutableMapOf<String, AtomicBoolean>()
    private val cancelled = mutableMapOf<String, Long>()
    private val death = IBinder.DeathRecipient { close() }
    init {
        require(readTimeoutMs in 100..10_000)
        require(isolatedUid > 0 && isolatedUid != Process.myUid())
        require(providerId.isNotBlank() && providerId.length <= 200 && modelId.isNotBlank() && modelId.length <= 256)
        peer.linkToDeath(death, 0)
    }
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in setOf(ModelRequestWire.OPEN, ModelRequestWire.OPEN_FD, ModelRequestWire.CANCEL)) return super.onTransact(code, data, reply, flags)
        if (reply == null) return true
        try {
            if (closed.get() || getCallingUid() != isolatedUid || data.dataSize() > ModelRequestWire.MAX_WIRE_BYTES) {
                throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
            }
            data.enforceInterface(ModelRequestWire.DESCRIPTOR)
            if (data.readString() != session) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
            val id = data.readString() ?: throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
            if (code == ModelRequestWire.CANCEL) {
                if (data.dataAvail() != 0) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                synchronized(lock) {
                    prune()
                    pending[id]?.set(true) ?: run {
                        if (cancelled.size >= 32) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
                        cancelled[id] = System.nanoTime()
                    }
                }
                reply.writeString(ModelBrokerCode.CANCELLED.wire)
            } else {
                val revoked = AtomicBoolean()
                synchronized(lock) {
                    prune()
                    if (closed.get()) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
                    if (cancelled.remove(id) != null) throw ModelBrokerFailure(ModelBrokerCode.CANCELLED)
                    if (pending.size >= 4 || pending.containsKey(id)) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
                    pending[id] = revoked
                }
                try {
                    val body = if (code == ModelRequestWire.OPEN_FD) {
                        val length = data.readInt()
                        if (length !in 1..ModelRequestWire.MAX_BODY_BYTES) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                        ParcelFileDescriptor.CREATOR.createFromParcel(data).use { fd ->
                            if (data.dataAvail() != 0) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                            readBody(fd, length, revoked)
                        }
                    } else {
                        val text = data.readString() ?: throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                        if (data.dataAvail() != 0 || text.toByteArray(Charsets.UTF_8).size !in 1..ModelRequestWire.MAX_INLINE_BODY_BYTES) {
                            throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                        }
                        text
                    }
                    requireNativeJsonBudget(body, ModelBrokerCode.INVALID_REQUEST)
                    val request = JSONObject(body)
                    if (request.get("providerId") != providerId || request.get("modelId") != modelId) {
                        throw ModelBrokerFailure(ModelBrokerCode.INVALID_PROVIDER)
                    }
                    if (revoked.get() || closed.get()) throw ModelBrokerFailure(ModelBrokerCode.CANCELLED)
                    openChannel(body).use { fd ->
                        if (closed.get() || revoked.get()) throw ModelBrokerFailure(ModelBrokerCode.CANCELLED)
                        reply.writeString("ok")
                        fd.writeToParcel(reply, 0) // Parcel owns a dup before the sender closes.
                    }
                } finally { synchronized(lock) { pending.remove(id) } }
            }
        } catch (error: Exception) {
            // No exception, request text, native capability or platform text crosses.
            reply.setDataSize(0); reply.setDataPosition(0)
            reply.writeString(if (error is ModelBrokerFailure) error.code.wire else ModelBrokerCode.UNAVAILABLE.wire)
        }
        return true
    }
    private fun readBody(channel: ParcelFileDescriptor, length: Int, revoked: AtomicBoolean): String {
        val fd = channel.fileDescriptor
        if ((Os.fstat(fd).st_mode and OsConstants.S_IFMT) != OsConstants.S_IFIFO) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
        Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(readTimeoutMs)
        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        val bytes = ByteArray(length)
        val extra = ByteArray(1)
        var offset = 0
        while (true) {
            if (revoked.get() || closed.get()) throw ModelBrokerFailure(ModelBrokerCode.CANCELLED)
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
            try {
                poll.revents = 0
                Os.poll(arrayOf(poll), minOf(100L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)).toInt())
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                if (poll.revents.toInt() and (OsConstants.POLLIN or OsConstants.POLLHUP) == 0) continue
                val count = if (offset == length) Os.read(fd, extra, 0, 1) else Os.read(fd, bytes, offset, length - offset)
                if (count == 0) {
                    if (offset != length) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                }
                if (offset == length) throw ModelBrokerFailure(ModelBrokerCode.INVALID_REQUEST)
                offset += count
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EINTR && error.errno != OsConstants.EAGAIN) throw ModelBrokerFailure(ModelBrokerCode.UNAVAILABLE)
            }
        }
    }
    private fun prune() {
        val cutoff = System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        cancelled.entries.removeAll { it.value < cutoff }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { pending.values.forEach { it.set(true) }; cancelled.clear() }
        try { peer.unlinkToDeath(death, 0) } catch (_: Exception) { }
        try { closeChannels() } catch (_: Exception) { }
    }
    override fun toString() = "NativeModelRequestHost(REDACTED)"
}
