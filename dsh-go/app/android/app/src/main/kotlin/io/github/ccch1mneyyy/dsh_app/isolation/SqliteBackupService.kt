// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Private one-shot native worker candidate. No Node, shell, model or network
 * endpoint. Native packaging and the Main grant/publication client are pending.
 * Main must never give this control Binder to Agent code or send more data FDs
 * after admission: closing aliases in a live SQLite process affects its locks.
 */
class SqliteBackupService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var session: SqliteBackupSession
    private val startClaimed = AtomicBoolean(false)
    @Volatile private var nativeLoaded = false
    private var owner: IBinder? = null
    private val death = IBinder.DeathRecipient { retire() }
    private val deadline = Runnable { retire() }
    private fun isolated() = Process.myUid() != applicationInfo.uid

    private val control = object : Binder() {
        init { attachInterface(null, SqliteBackupWire.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in SqliteBackupWire.START..SqliteBackupWire.RETIRE) return super.onTransact(code, data, reply, flags)
            // Read Binder identity BEFORE decoding anything supplied by a caller.
            val uid = getCallingUid()
            val pid = getCallingPid()
            var accepted = false
            if (isolated() && uid == applicationInfo.uid && SqliteBackupWire.synchronous(flags, reply != null)) {
                try {
                    data.enforceInterface(SqliteBackupWire.DESCRIPTOR)
                    when (code) {
                        SqliteBackupWire.START -> if (startClaimed.compareAndSet(false, true)) {
                            accepted = start(data, uid, pid)
                            if (!accepted) main.post { retire() }
                        }
                        else -> {
                            val nonce = data.readString() ?: ""
                            if (data.dataAvail() == 0 && session.matches(uid, pid, nonce)) {
                                accepted = true
                                if (code == SqliteBackupWire.RETIRE) {
                                    // Reply is not retirement evidence. Main must
                                    // observe death of this process-anchored Binder.
                                    main.post { retire() }
                                } else {
                                    session.cancel(uid, pid, nonce)
                                    cancelNative()
                                }
                            }
                        }
                    }
                } catch (_: Exception) { /* Only finite refusal leaves this boundary. */ }
            }
            reply?.writeNoException()
            reply?.writeInt(if (accepted) SqliteBackupWire.ACCEPTED else SqliteBackupWire.REFUSED)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        session = SqliteBackupSession(applicationInfo.uid, Process.myUid(), Process.myPid())
        // Hold the Binder for the ENTIRE process lifetime, including onDestroy.
        // Otherwise a collected Binder alone could look like process retirement.
        if (created.compareAndSet(false, true)) processAnchor = control
        else { retire(); return }
        if (isolated()) main.postDelayed(deadline, 45_000)
    }
    override fun onBind(intent: Intent?): IBinder = control
    override fun onDestroy() { retire(); super.onDestroy() }

    private fun start(data: Parcel, uid: Int, pid: Int): Boolean {
        val fds = mutableListOf<ParcelFileDescriptor>()
        var transferred = false
        var admitted = false
        try {
            val nonce = data.readString() ?: return false
            val maxBytes = data.readLong()
            val timeout = data.readInt()
            val callback = data.readStrongBinder() ?: return false
            if (!nonce.matches(Regex("[0-9a-f]{64}")) || maxBytes !in 4096L..512L * 1024 * 1024 || timeout !in 1..30_000) return false
            repeat(8) { fds.add(data.readTypedObject(ParcelFileDescriptor.CREATOR) ?: return false) }
            if (data.dataAvail() != 0 || !session.admit(uid, pid, nonce)) return false
            admitted = true
            owner = callback
            callback.linkToDeath(death, 0)
            main.removeCallbacks(deadline)
            main.postDelayed(deadline, timeout.toLong() + 5_000)
            executor.execute { copy(callback, nonce, uid, pid, fds, maxBytes, timeout) }
            transferred = true
            return true
        } finally {
            if (!transferred) {
                fds.forEach { try { it.close() } catch (_: Exception) { } }
                if (admitted) main.post { retire() }
            }
        }
    }

    private fun copy(callback: IBinder, nonce: String, uid: Int, pid: Int,
                     fds: List<ParcelFileDescriptor>, maxBytes: Long, timeout: Int) {
        var result = SqliteBackupReply.UNAVAILABLE
        try {
            // Main must inspect actual Binder UID/PID in this callback and
            // acknowledge that this is its expected isolated execution unit.
            if (notify(callback, SqliteBackupWire.PEER, nonce) && session.begin()) {
                val bindings = NativeSqliteBackupBindings // First load, after role/owner/peer admission.
                nativeLoaded = true
                if (session.isCancelled()) bindings.cancel()
                result = SqliteBackupReply.decode(bindings.run(fds.map { it.fd }.toIntArray(), uid, pid, maxBytes, timeout))
            }
        } catch (_: LinkageError) { result = SqliteBackupReply.UNAVAILABLE }
        catch (_: Exception) { result = SqliteBackupReply.UNAVAILABLE }
        finally {
            var closed = true
            fds.forEach { try { it.close() } catch (_: Exception) { closed = false } }
            if (!closed) result = SqliteBackupReply.UNAVAILABLE
            result = session.finish(result)
            try { notify(callback, SqliteBackupWire.RESULT, nonce, result) }
            finally { retire() }
        }
    }

    private fun notify(callback: IBinder, code: Int, nonce: String, result: SqliteBackupReply? = null): Boolean {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(SqliteBackupWire.CALLBACK)
            request.writeString(nonce)
            if (result != null) request.writeLongArray(longArrayOf(result.stage.wire, result.result.wire, result.bytes))
            if (!callback.transact(code, request, reply, 0)) false
            else { reply.readException(); reply.readInt() == SqliteBackupWire.ACCEPTED && reply.dataAvail() == 0 }
        } catch (_: Exception) { false }
        finally { request.recycle(); reply.recycle() }
    }

    private fun cancelNative() {
        if (nativeLoaded) try { NativeSqliteBackupBindings.cancel() }
        catch (_: LinkageError) { } catch (_: Exception) { }
    }
    private fun retire() {
        if (::session.isInitialized) session.retire()
        cancelNative()
        // Never kill Main if a manifest/configuration error hosted us there.
        if (isolated()) Process.killProcess(Process.myPid())
        else stopSelf()
    }
    private companion object {
        val created = AtomicBoolean(false)
        @Volatile var processAnchor: IBinder? = null
    }
}
