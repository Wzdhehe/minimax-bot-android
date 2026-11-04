// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
    private val readDirectory: RuntimePrivateDirectory?,
    private val allowMove: Boolean,
    private val allowRemove: Boolean,
) : Binder(), Closeable {
    val session = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val lock = Any()
    private var closed = false
    private var active = 0
    private var outgoing = 0
    private val retired = AtomicBoolean()
    private val drained = CompletableFuture<Unit>()
    private val death = IBinder.DeathRecipient { close() }
    init { peer.linkToDeath(death, 0) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code !in RuntimeFileCommitWire.PUBLISH..RuntimeFileCommitWire.REMOVE) return super.onTransact(code, data, reply, flags)
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
            if ((code == RuntimeFileCommitWire.REPLACE || code == RuntimeFileCommitWire.MOVE_REPLACE) && !allowReplace) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            val query = code == RuntimeFileCommitWire.READ || code == RuntimeFileCommitWire.SIZE
            val move = code == RuntimeFileCommitWire.MOVE || code == RuntimeFileCommitWire.MOVE_REPLACE
            val remove = code == RuntimeFileCommitWire.REMOVE
            if ((move && !allowMove) || (remove && !allowRemove)) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            if (query && readDirectory == null) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            val name = data.readString() ?: throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            val target = if (move) data.readString() ?: throw RuntimeDataFailure(RuntimeDataCode.INVALID) else null
            val controlOnly = query || move || remove
            val length = if (controlOnly) 0 else data.readInt()
            if (!RuntimeFileCommitWire.validTarget(name) || length !in 0..RuntimeFileCommitWire.MAX_BYTES) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            if (move && (!RuntimeFileCommitWire.validTarget(target!!) || name == target)) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            if (controlOnly && data.dataAvail() != 0) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            synchronized(lock) {
                if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
                if (active + outgoing >= 4) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                active++; admitted = true
            }
            if (query) {
                if (code == RuntimeFileCommitWire.READ) sendSnapshot(name, reply)
                else {
                    val size = runtimeFileSize(readDirectory!!, name)
                    if (isClosed()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
                    reply.writeString("ok"); reply.writeLong(size)
                }
            } else if (move || remove) {
                synchronized(lock) {
                    if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
                    enteredCommit = true
                }
                if (remove) reply.writeString(when (publisher.remove(name)) {
                    RuntimeDeletionResult.REMOVED -> "removed"
                    RuntimeDeletionResult.OUTCOME_UNKNOWN -> "outcome_unknown"
                })
                else reply.writeString(publicationCode(publisher.move(name, target!!, code == RuntimeFileCommitWire.MOVE_REPLACE)))
            } else {
                val bytes = ParcelFileDescriptor.CREATOR.createFromParcel(data).use { input ->
                    if (data.dataAvail() != 0) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
                    readBoundedRequestPipe(input, length, RuntimeFileCommitWire.MAX_BYTES, readTimeoutMs, ::isClosed)
                }
                synchronized(lock) {
                    if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
                    enteredCommit = true
                }
                val result = if (code == RuntimeFileCommitWire.REPLACE) publisher.replace(name, bytes) else publisher.publishIfAbsent(name, bytes)
                reply.writeString(publicationCode(result))
            }
        } catch (error: Exception) {
            reply.setDataSize(0); reply.setDataPosition(0)
            reply.writeString(if (error is RuntimeDataFailure) error.code.wire else if (enteredCommit) "outcome_unknown" else RuntimeDataCode.UNAVAILABLE.wire)
        } finally {
            if (admitted) {
                val shouldRetire = synchronized(lock) { active--; closed && active == 0 && outgoing == 0 }
                if (shouldRetire) retire()
            }
        }
        return true
    }
    private fun isClosed() = synchronized(lock) { closed }
    private fun publicationCode(result: RuntimePublicationResult): String = when (result) {
        RuntimePublicationResult.PUBLISHED -> "published"
        RuntimePublicationResult.ALREADY_EXISTS -> "already_exists"
        RuntimePublicationResult.OUTCOME_UNKNOWN -> "outcome_unknown"
    }
    /** Trusted Main shutdown accounting, never part of the Agent protocol. */
    val inFlightOperations: Int get() = synchronized(lock) { active + outgoing }

    private fun sendSnapshot(name: String, reply: Parcel) {
        if (!outputSlots.tryAcquire()) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
        var pipe: Array<ParcelFileDescriptor>? = null
        var registered = false
        var scheduled = false
        try {
            val bytes = readRuntimeFileSnapshot(readDirectory!!, name, RuntimeFileCommitWire.MAX_BYTES, ::isClosed)
            val created = ParcelFileDescriptor.createPipe()
            pipe = created
            // Receiver only needs to read; prepare nonblocking mode while Main
            // owns the pipe, rather than requiring isolated-side option changes.
            val flags = Os.fcntlInt(created[0].fileDescriptor, OsConstants.F_GETFL, 0)
            Os.fcntlInt(created[0].fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            synchronized(lock) {
                if (closed) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
                outgoing++; registered = true
            }
            reply.writeString("ok"); reply.writeInt(bytes.size)
            created[0].writeToParcel(reply, 0)
            producers.execute {
                try { writeBoundedResponsePipe(created[1], bytes, readTimeoutMs, ::isClosed) }
                catch (_: Exception) { /* A partial response is refused by the exact-length client. */ }
                finally {
                    try { created[1].close() } catch (_: Exception) { }
                    outputSlots.release(); finishOutput()
                }
            }
            scheduled = true
        } finally {
            try { pipe?.get(0)?.close() } catch (_: Exception) { }
            if (!scheduled) {
                try { pipe?.get(1)?.close() } catch (_: Exception) { }
                outputSlots.release()
                if (registered) finishOutput()
            }
        }
    }
    private fun finishOutput() {
        val shouldRetire = synchronized(lock) { outgoing--; closed && active == 0 && outgoing == 0 }
        if (shouldRetire) retire()
    }
    override fun close() {
        val shouldRetire = synchronized(lock) {
            if (closed) return
            closed = true
            active == 0 && outgoing == 0
        }
        try { peer.unlinkToDeath(death, 0) } catch (_: Exception) { }
        if (shouldRetire) retire()
    }
    fun whenRetired(): CompletableFuture<Unit> = drained.thenApply { Unit }
    fun closeAsync(): CompletableFuture<Unit> { close(); return whenRetired() }
    private fun retire() {
        if (!retired.compareAndSet(false, true)) return
        var failed = false
        try { publisher.close() } catch (_: Exception) { failed = true }
        try { readDirectory?.close() } catch (_: Exception) { failed = true }
        if (failed) drained.completeExceptionally(RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)) else drained.complete(Unit)
    }
    override fun toString() = "RuntimeFileCommitHost(REDACTED)"
    companion object {
        private val outputSlots = Semaphore(4)
        private val producers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { task -> Thread(task, "mbot-file-snapshot").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
        fun open(context: Context, scopeId: String, isolatedUid: Int, peer: IBinder,
                 allowReplace: Boolean = false, readTimeoutMs: Long = 5_000, allowRead: Boolean = false,
                 allowMove: Boolean = false, allowRemove: Boolean = false): RuntimeFileCommitHost {
            RuntimePrivateDirectory.requireOwnerWorker(context)
            if (!RuntimePrivateDirectory.validName(scopeId) || isolatedUid <= 0 || isolatedUid == Process.myUid() || readTimeoutMs !in 100..10_000) {
                throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            }
            // This fixed subtree cannot overlap the v2/sqlite files or native
            // credentials, even when Main accidentally chooses scopeId="v2".
            val publisher = RuntimeAtomicPublisher.open(context, listOf("files", scopeId))
            var reader: RuntimePrivateDirectory? = null
            try {
                if (allowRead) reader = RuntimePrivateDirectory.open(context, listOf("files", scopeId))
                return RuntimeFileCommitHost(publisher, isolatedUid, peer, allowReplace, readTimeoutMs, reader, allowMove, allowRemove)
            }
            catch (_: Exception) {
                try { publisher.close() } catch (_: Exception) { }
                try { reader?.close() } catch (_: Exception) { }
                throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
        }
    }
}
