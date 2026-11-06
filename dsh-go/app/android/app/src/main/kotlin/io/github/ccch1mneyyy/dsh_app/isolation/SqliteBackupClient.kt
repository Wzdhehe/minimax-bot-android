// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Main-only capability client, not an Agent API or backup publisher. Caller
 * selects the fixed source/private snapshot family and owns publication policy.
 * Owns FD/directory duplicates until no grant was sent OR the anchored worker
 * has died AND the START sender has stopped using them. Unbind/ACK/timeouts are
 * never substituted for death. Unknown attempts retain their capabilities/gate.
 */
internal class SqliteBackupClient private constructor(
    private val app: Context,
    private val directory: RuntimePrivateDirectory,
    private val files: List<ParcelFileDescriptor>,
    private val maxBytes: Long,
    private val timeoutMs: Int,
) : Closeable {
    private val lock = Any()
    private val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val exchange = SqliteBackupExchange(Process.myUid(), Process.myPid(), nonce)
    private val settled = CountDownLatch(1)
    private val component = ComponentName(app, SqliteBackupService::class.java)
    private var binding = false
    private var registered = false
    private var connected = false
    private var started = false
    private var cleaned = false
    private var peer: IBinder? = null
    private var deathLinked = false
    private val cancelOffered = AtomicBoolean(false)
    private val retireOffered = AtomicBoolean(false)
    private val death = IBinder.DeathRecipient { exchange.workerDied(); tryCleanup() }
    private var expiry: ScheduledFuture<*>? = null
    private fun expire() {
        val expired = synchronized(lock) {
            if (cleaned) false else { exchange.failBeforeSend(); true }
        }
        if (expired) { requestControl(SqliteBackupWire.RETIRE); tryCleanup() }
    }

    private val callback = object : Binder() {
        init { attachInterface(null, SqliteBackupWire.CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != SqliteBackupWire.PEER && code != SqliteBackupWire.RESULT) return super.onTransact(code, data, reply, flags)
            val uid = getCallingUid()
            val pid = getCallingPid()
            var accepted = false
            if (flags == 0 && reply != null) try {
                data.enforceInterface(SqliteBackupWire.CALLBACK)
                val session = data.readString() ?: ""
                val result = if (code == SqliteBackupWire.RESULT) {
                    if (data.readInt() != 3) throw IllegalArgumentException()
                    SqliteBackupReply.decode(longArrayOf(data.readLong(), data.readLong(), data.readLong()))
                } else null
                if (data.dataAvail() == 0) accepted = if (result == null) exchange.peer(uid, pid, session)
                    else exchange.result(uid, pid, session, result)
            } catch (_: Exception) { /* Finite refusal, never exception text. */ }
            reply?.writeNoException()
            reply?.writeInt(if (accepted) SqliteBackupWire.ACCEPTED else SqliteBackupWire.REFUSED)
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val send = synchronized(lock) {
                if (cleaned || connected || name != component) false
                else { connected = true; peer = service; true }
            }
            if (!send) return
            if (exchange.isCancelled()) { exchange.failBeforeSend(); tryCleanup(); return }
            try { senders.execute { sendStart(service) } }
            catch (_: Exception) { exchange.failBeforeSend(); tryCleanup() }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    /** Explicit activation AFTER caller has retained this resource owner. A
     * prepare failure can never have transferred data FDs in the background. */
    fun start() {
        RuntimePrivateDirectory.requireOwnerWorker(app)
        synchronized(lock) {
            if (started || cleaned) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            started = true
            binding = true
        }
        try {
            // Do not publish a partly constructed client to the timer thread.
            expiry = timer.schedule({ expire() }, timeoutMs.toLong() + 10_000, TimeUnit.MILLISECONDS)
            val result = app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            synchronized(lock) { registered = result }
            if (!result) exchange.failBeforeSend()
        } catch (_: Exception) { exchange.failBeforeSend() }
        finally { synchronized(lock) { binding = false }; tryCleanup() }
    }

    private fun disconnected() {
        // Framework disconnect is not the anchored Binder's death receipt.
        exchange.bindingLost()
        requestControl(SqliteBackupWire.RETIRE)
        tryCleanup()
    }

    private fun sendStart(service: IBinder) {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        var sending = false
        try {
            service.linkToDeath(death, 0)
            val installed = synchronized(lock) {
                if (cleaned) false else { deathLinked = true; true }
            }
            if (!installed) { service.unlinkToDeath(death, 0); return }
            if (!exchange.beginSend()) { exchange.failBeforeSend(); return }
            sending = true
            request.writeInterfaceToken(SqliteBackupWire.DESCRIPTOR)
            request.writeString(nonce)
            request.writeLong(maxBytes)
            request.writeInt(timeoutMs)
            request.writeStrongBinder(callback)
            files.forEach { request.writeTypedObject(it, 0) }
            if (!service.transact(SqliteBackupWire.START, request, reply, 0)) {
                exchange.cancel(); requestControl(SqliteBackupWire.RETIRE)
            } else {
                reply.readException()
                if (reply.readInt() != SqliteBackupWire.ACCEPTED || reply.dataAvail() != 0) {
                    exchange.cancel(); requestControl(SqliteBackupWire.RETIRE)
                }
            }
        } catch (_: Exception) {
            if (!sending) exchange.failBeforeSend()
            // An ACK may be lost after a genuine result/death. Do not invent an
            // outcome or retry: only those independent receipts decide it.
            else requestControl(SqliteBackupWire.RETIRE)
        } finally {
            request.recycle(); reply.recycle()
            exchange.senderFinished()
            tryCleanup()
        }
    }

    private fun requestControl(code: Int) {
        val service = synchronized(lock) { if (cleaned) null else peer } ?: return
        val offered = if (code == SqliteBackupWire.CANCEL) cancelOffered else retireOffered
        if (!offered.compareAndSet(false, true)) return
        try { controls.execute {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(SqliteBackupWire.DESCRIPTOR)
                request.writeString(nonce)
                // Neither a successful control ACK nor a failed transaction
                // releases resources: only tryCleanup's retirement policy can.
                service.transact(code, request, reply, 0)
            } catch (_: Exception) { /* Outcome remains unknown until real death. */ }
            finally { request.recycle(); reply.recycle() }
        } } catch (_: Exception) { /* Bounded queue failure is not exit evidence. */ }
    }

    fun cancel() {
        exchange.failBeforeSend() // Also invalidates any already received candidate.
        requestControl(SqliteBackupWire.CANCEL)
        tryCleanup()
    }
    override fun close() { cancel(); requestControl(SqliteBackupWire.RETIRE) }

    /** Off Main only. UNKNOWN means owned duplicates/lease are still retained;
     * caller must not publish, reuse or clean this attempt's private output.
     * It may wait again; there is no automatic retransmission/rebinding. */
    fun awaitSettled(waitMs: Long): SqliteBackupExchange.Outcome {
        RuntimePrivateDirectory.requireOwnerWorker(app)
        if (waitMs !in 1..120_000) throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        try { settled.await(waitMs, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); close() }
        return if (settled.count == 0L) exchange.outcome()
            else SqliteBackupExchange.Outcome(SqliteBackupExchange.Disposition.UNKNOWN, SqliteBackupReply.UNAVAILABLE)
    }
    fun candidateStillValid(reply: SqliteBackupReply): Boolean = settled.count == 0L && exchange.candidateStillValid(reply)

    private fun tryCleanup() {
        val cleanup = synchronized(lock) {
            if (cleaned || binding || !exchange.outcome().safeToRelease) null
            else { cleaned = true; Triple(registered, peer, deathLinked).also { registered = false; deathLinked = false } }
        } ?: return
        try {
            expiry?.cancel(false)
            files.forEach { try { it.close() } catch (_: Exception) { exchange.cancel() } }
            try { directory.close() } catch (_: Exception) { exchange.cancel() }
            if (cleanup.third) try { cleanup.second?.unlinkToDeath(death, 0) }
            catch (_: NoSuchElementException) { /* Death subscription already removed. */ }
            catch (_: Exception) { exchange.cancel() }
            if (cleanup.first) try { app.unbindService(connection) }
            catch (_: IllegalArgumentException) { /* Framework already removed this registration. */ }
            catch (_: Exception) { exchange.cancel() }
        } finally { gate.release(); settled.countDown() }
    }
    override fun toString() = "SqliteBackupClient(REDACTED)"

    companion object {
        private val gate = Semaphore(1)
        private val senders = pool("mbot-backup-start", 1)
        private val controls = pool("mbot-backup-control", 2)
        private val timer = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "mbot-backup-timeout").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        private fun pool(name: String, queue: Int) = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue<Runnable>(queue), { task -> Thread(task, name).apply { isDaemon = true } })
            .apply { allowCoreThreadTimeOut(true) }

        fun prepare(context: Context, directory: RuntimePrivateDirectory, descriptors: List<ParcelFileDescriptor>,
                   maxBytes: Long, timeoutMs: Int): SqliteBackupClient {
            RuntimePrivateDirectory.requireOwnerWorker(context)
            directory.requireWorker()
            if (descriptors.size != 8 || maxBytes !in 4096L..512L * 1024 * 1024 || timeoutMs !in 1..30_000) {
                throw RuntimeDataFailure(RuntimeDataCode.INVALID)
            }
            if (!gate.tryAcquire()) throw RuntimeDataFailure(RuntimeDataCode.BUSY)
            var retained: RuntimePrivateDirectory? = null
            val copies = mutableListOf<ParcelFileDescriptor>()
            var transferred = false
            try {
                val guard = directory.duplicate()
                retained = guard
                // Same open-file-description as caller's directory capability;
                // its advisory lease survives a caller closing its own copy.
                val leased = guard.withDescriptor { NativeRuntimePublication.tryDirectoryLease(it) }
                if (leased != 0) throw RuntimeDataFailure(if (leased == android.system.OsConstants.EAGAIN)
                    RuntimeDataCode.BUSY else RuntimeDataCode.UNAVAILABLE)
                descriptors.forEach { copies.add(it.dup()) }
                val client = SqliteBackupClient(context.applicationContext, guard, copies.toList(), maxBytes, timeoutMs)
                transferred = true
                return client
            } catch (error: RuntimeDataFailure) { throw error }
            catch (_: Exception) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
            catch (_: LinkageError) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
            finally {
                if (!transferred) {
                    copies.forEach { try { it.close() } catch (_: Exception) { } }
                    try { retained?.close() } catch (_: Exception) { }
                    gate.release()
                }
            }
        }
    }
}
