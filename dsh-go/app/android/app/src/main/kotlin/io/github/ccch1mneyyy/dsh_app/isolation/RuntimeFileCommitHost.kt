// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Main-only bounded file-commit grant. The trusted launcher chooses scope and
 * replacement rights after observing the peer UID. Agent input cannot choose a
 * root, subdirectory, key/config path or SQLite file. No file/directory FD goes
 * to the Agent. close revokes new admissions and drains existing work; it cannot
 * roll back an in-flight commit or revoke unrelated SQLite/model capabilities.
 */
internal class RuntimeFileCommitHost private constructor(
    private val publisher: RuntimeAtomicPublisher,
    private val isolatedUid: Int,
    private val peer: IBinder,
    private val allowReplace: Boolean,
    private val readTimeoutMs: Long,
) : Binder(), Closeable {
    val session = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val lock = Any()
    private var closed = false
    private var active = 0
    private val retired = AtomicBoolean()
    private val drained = CompletableFuture<Unit>()
    private val death = IBinder.DeathRecipient { close() }
    init { peer.linkToDeath(death, 0) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != RuntimeFileCommitWire.PUBLISH && code != RuntimeFileCommitWire.REPLACE) return super.onTransact(code, data, reply, flags)
        if (reply == null) return true
        var admitted = false
        var enteredCommit = false
        try {
            // All checks precede body allocation and file creation.
            if (getCallingUid() != isolatedUid || data.dataSize() > RuntimeFileCommitWire.MAX_WIRE_BYTES) {
                throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            }
            data.enforceInterface(RuntimeFileCommitWire.DESCRIPTOR)
            val nonce = data.readString()
            if (nonce == null || nonce.length != 64 || !MessageDigest.isEqual(session.toByteArray(Charsets.US_ASCII), nonce.toByteArray(Charsets.UTF_8))) {
                throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            }
            if (code == RuntimeFileCommitWire.REPLACE && !allowReplace) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            val name = data.readString() ?: throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            val length = data.readInt()
            if (!RuntimeFileCommitWire.validTarget(name) || length !in 0..RuntimeFileCommitWire.MAX_BYTES) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            synchronized(lock) {
                if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
                if (active >= 4) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                active++; admitted = true
            }
            val bytes = ParcelFileDescriptor.CREATOR.createFromParcel(data).use { input ->
                if (data.dataAvail() != 0) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
                readBoundedRequestPipe(input, length, RuntimeFileCommitWire.MAX_BYTES, readTimeoutMs, ::isClosed)
            }
            synchronized(lock) {
                if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
                enteredCommit = true
            }
            val result = if (code == RuntimeFileCommitWire.REPLACE) publisher.replace(name, bytes) else publisher.publishIfAbsent(name, bytes)
            reply.writeString(when (result) {
                RuntimePublicationResult.PUBLISHED -> "published"
                RuntimePublicationResult.ALREADY_EXISTS -> "already_exists"
                RuntimePublicationResult.OUTCOME_UNKNOWN -> "outcome_unknown"
            })
        } catch (error: Exception) {
            reply.setDataSize(0); reply.setDataPosition(0)
            reply.writeString(if (error is RuntimeDataFailure) error.code.wire else if (enteredCommit) "outcome_unknown" else RuntimeDataCode.UNAVAILABLE.wire)
        } finally {
            if (admitted) {
                val shouldRetire = synchronized(lock) { active--; closed && active == 0 }
                if (shouldRetire) retire()
            }
        }
        return true
    }
    private fun isClosed() = synchronized(lock) { closed }
    /** Trusted Main shutdown accounting, never part of the Agent protocol. */
    val inFlightOperations: Int get() = synchronized(lock) { active }
    override fun close() {
        val shouldRetire = synchronized(lock) {
            if (closed) return
            closed = true
            active == 0
        }
        try { peer.unlinkToDeath(death, 0) } catch (_: Exception) { }
        if (shouldRetire) retire()
    }
    fun whenRetired(): CompletableFuture<Unit> = drained.thenApply { Unit }
    fun closeAsync(): CompletableFuture<Unit> { close(); return whenRetired() }
    private fun retire() {
        if (!retired.compareAndSet(false, true)) return
        try { publisher.close(); drained.complete(Unit) }
        catch (_: Exception) { drained.completeExceptionally(RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)) }
    }
    override fun toString() = "RuntimeFileCommitHost(REDACTED)"
    companion object {
        fun open(context: Context, scopeId: String, isolatedUid: Int, peer: IBinder,
                 allowReplace: Boolean = false, readTimeoutMs: Long = 5_000): RuntimeFileCommitHost {
            RuntimePrivateDirectory.requireOwnerWorker(context)
            if (!RuntimePrivateDirectory.validName(scopeId) || isolatedUid <= 0 || isolatedUid == Process.myUid() || readTimeoutMs !in 100..10_000) {
                throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            }
            // This fixed subtree cannot overlap the v2/sqlite files or native
            // credentials, even when Main accidentally chooses scopeId="v2".
            val publisher = RuntimeAtomicPublisher.open(context, listOf("files", scopeId))
            try { return RuntimeFileCommitHost(publisher, isolatedUid, peer, allowReplace, readTimeoutMs) }
            catch (_: Exception) {
                try { publisher.close() } catch (_: Exception) { }
                throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
        }
    }
}
