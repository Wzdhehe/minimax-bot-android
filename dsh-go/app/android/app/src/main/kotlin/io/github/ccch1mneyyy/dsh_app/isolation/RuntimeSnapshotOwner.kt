// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Main owns source selection, private staging and publication. No Agent path,
 * directory or writable snapshot FD is accepted/returned. The launcher owns
 * the source RuntimeDatabaseFiles and supplies its real generation predicate.
 * This owner never sweeps unknown files or retries an uncertain publication.
 */
internal class RuntimeSnapshotOwner private constructor(
    private val context: Context,
    private val source: RuntimeDatabaseFiles,
    private val directory: RuntimePrivateDirectory,
    private val active: () -> Boolean,
) : Closeable {
    enum class Status { PUBLISHED, ALREADY_EXISTS, NOT_PUBLISHED, OUTCOME_UNKNOWN }
    data class Info(val fileName: String, val bytes: Long, val createdAtMs: Long, val identity: String)
    private class Record(val info: Info, val file: ParcelFileDescriptor, val device: Long, val inode: Long, var grants: Int = 0)
    private val records = mutableMapOf<String, Record>()
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val uncertain = AtomicBoolean(false)
    @Volatile private var pending: SqliteBackupClient? = null
    @Volatile private var diagnostic = "idle"
    /** Finite phases/codes only; no names, paths, identities, payload or Key. */
    fun diagnosticCode(): String = diagnostic
    private fun live(): Boolean = !closed.get() && !uncertain.get() && try { active() } catch (_: Exception) { false }

    /** Off Main. Copy result/ACK is never publication permission. This function
     * waits for real retirement, closes all owner RW copies, reopens RO and
     * checks inode identity before the exclusive durable publication primitive.
     */
    fun create(fileName: String, maxBytes: Long = 64L * 1024 * 1024, timeoutMs: Int = 30_000): Status {
        RuntimePrivateDirectory.requireOwnerWorker(context)
        if (!validName(fileName) || maxBytes !in 4096L..512L * 1024 * 1024 || timeoutMs !in 1..30_000) {
            throw RuntimeDataFailure(RuntimeDataCode.INVALID)
        }
        if (!live()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
        val token = randomToken()
        val writable = mutableListOf<ParcelFileDescriptor>()
        val readonly = MutableList<ParcelFileDescriptor?>(4) { null }
        var sourceCopies = emptyList<ParcelFileDescriptor>()
        var attempt: SqliteBackupClient? = null
        var safeToRelease = false
        var preserve = false
        var publicationStarted = false
        if (!busy.compareAndSet(false, true)) throw RuntimeDataFailure(RuntimeDataCode.BUSY)
        diagnostic = "admission"
        try {
            synchronized(records) {
                if (records.containsKey(fileName)) return Status.ALREADY_EXISTS
                if (records.size >= 8) throw RuntimeDataFailure(RuntimeDataCode.TOO_LARGE)
            }
            if (!live()) return Status.NOT_PUBLISHED
            for (slot in 0..3) {
                diagnostic = "stage_create_$slot"
                val fd = directory.withDescriptor { NativeRuntimePublication.createSnapshotStage(it, token, slot) }
                if (fd < 0) { diagnostic = "stage_errno_${-fd}"; throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
                writable.add(ParcelFileDescriptor.adoptFd(fd))
            }
            diagnostic = "source_grant"
            sourceCopies = source.duplicateForTransfer()
            if (!live()) return Status.NOT_PUBLISHED
            val client = SqliteBackupClient.prepare(context, directory, sourceCopies + writable, maxBytes, timeoutMs)
            attempt = client; pending = client
            diagnostic = "worker_start"
            client.start()
            val deadline = System.nanoTime() + 60_000_000_000L
            var receipt: SqliteBackupExchange.Outcome
            do {
                if (!live()) client.close()
                receipt = client.awaitSettled(250)
            } while (!receipt.safeToRelease && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted)
            safeToRelease = receipt.safeToRelease
            if (!safeToRelease) { diagnostic = "worker_not_retired"; preserve = true; uncertain.set(true); return Status.OUTCOME_UNKNOWN }
            diagnostic = "worker_${receipt.reply.stage.name}_${receipt.reply.result.name}"
            if (!receipt.candidateReady || !live()) return Status.NOT_PUBLISHED
            diagnostic = "readonly_reopen"
            val identities = writable.map { Os.fstat(it.fileDescriptor) }
            if (identities[0].st_size != receipt.reply.bytes || identities.drop(1).any { it.st_size != 0L }) {
                throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
            }
            // The client already closed its copies after worker death. Close
            // the owner's final writable copies before reopening any RO grants.
            writable.forEach { it.close() }
            for (slot in 0..3) {
                val raw = directory.withDescriptor { NativeRuntimePublication.openSnapshotStageRead(it, token, slot) }
                if (raw < 0) throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                val file = ParcelFileDescriptor.adoptFd(raw)
                readonly[slot] = file
                val now = Os.fstat(file.fileDescriptor)
                val before = identities[slot]
                if (now.st_dev != before.st_dev || now.st_ino != before.st_ino || now.st_size != before.st_size ||
                    now.st_uid != Process.myUid() || now.st_nlink != 1L || (now.st_mode and 4095) != 384 ||
                    (Os.fcntlInt(file.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) {
                    throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                }
            }
            if (!live() || !client.candidateStillValid(receipt.reply)) return Status.NOT_PUBLISHED
            publicationStarted = true
            diagnostic = "publication"
            val result = directory.withDescriptor {
                NativeRuntimePublication.publishSnapshot(it, token, fileName, readonly[0]!!.fd, receipt.reply.bytes)
            }
            when (result) {
                0 -> {
                    if (!live()) { preserve = true; uncertain.set(true); return Status.OUTCOME_UNKNOWN }
                    val info = Info(fileName, receipt.reply.bytes, System.currentTimeMillis(), randomToken())
                    synchronized(records) { records[fileName] = Record(info, readonly[0]!!, identities[0].st_dev, identities[0].st_ino) }
                    readonly[0] = null // Ownership moved to this owner's immutable record.
                    diagnostic = "published"
                    return Status.PUBLISHED
                }
                1 -> return Status.ALREADY_EXISTS
                3 -> return Status.NOT_PUBLISHED
                else -> { preserve = true; uncertain.set(true); return Status.OUTCOME_UNKNOWN }
            }
        } catch (_: Exception) {
            if (!safeToRelease) safeToRelease = settledWithoutPublication(attempt)
            if (publicationStarted || !safeToRelease) {
                preserve = true; uncertain.set(true); return Status.OUTCOME_UNKNOWN
            }
            return Status.NOT_PUBLISHED
        } catch (_: LinkageError) {
            if (!safeToRelease) safeToRelease = settledWithoutPublication(attempt)
            if (publicationStarted || !safeToRelease) {
                preserve = true; uncertain.set(true); return Status.OUTCOME_UNKNOWN
            }
            return Status.NOT_PUBLISHED
        } finally {
            attempt?.close()
            // Only this attempt's entries whose inode matches a held FD. Never
            // remove a public target, an unknown create, or a possibly live writer.
            if (!preserve && (attempt == null || safeToRelease)) for (slot in 0..3) {
                val file = readonly[slot] ?: writable.getOrNull(slot)
                if (file != null) try {
                    directory.withDescriptor { NativeRuntimePublication.removeSnapshotStage(it, token, slot, file.fd) }
                } catch (_: Exception) { /* Preserve unknown private remnants. */ }
                catch (_: LinkageError) { }
            }
            (sourceCopies + writable + readonly.filterNotNull()).forEach { try { it.close() } catch (_: Exception) { } }
            pending = null
            busy.set(false)
            if (closed.get()) release()
        }
    }

    /** Metadata and reads cover ONLY confirmed publications by this owner.
     * Old/unknown on-disk files are not silently admitted or pruned. Identity is
     * opaque and scoped to this run; it is not a physical path or inode. */
    fun catalog(): List<Info> {
        RuntimePrivateDirectory.requireOwnerWorker(context)
        if (!live()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
        return synchronized(records) {
            if (closed.get() || uncertain.get()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            records.values.map { it.info }.sortedBy { it.createdAtMs }
        }
    }
    fun openReadOnly(fileName: String, identity: String): ParcelFileDescriptor {
        RuntimePrivateDirectory.requireOwnerWorker(context)
        if (!live()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
        return synchronized(records) {
            // Never invoke a launcher callback under the record lock: runtime
            // shutdown can hold its own lock while closing this owner.
            if (closed.get() || uncertain.get()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            val record = records[fileName] ?: throw RuntimeDataFailure(RuntimeDataCode.NOT_FOUND)
            if (record.info.identity != identity) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
            if (record.grants >= 8) throw RuntimeDataFailure(RuntimeDataCode.TOO_LARGE)
            try {
                val info = Os.fstat(record.file.fileDescriptor)
                if (info.st_dev != record.device || info.st_ino != record.inode || info.st_size != record.info.bytes ||
                    info.st_uid != Process.myUid() || info.st_nlink != 1L || (info.st_mode and 4095) != 384 ||
                    (Os.fcntlInt(record.file.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) {
                    throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE)
                }
                record.file.dup().also { record.grants++ }
            } catch (failure: RuntimeDataFailure) { throw failure }
            catch (_: Exception) { throw RuntimeDataFailure(RuntimeDataCode.UNAVAILABLE) }
        }
    }
    fun removeCreated(fileName: String, identity: String): RuntimeDeletionResult {
        RuntimePrivateDirectory.requireOwnerWorker(context)
        if (!live()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
        if (!busy.compareAndSet(false, true)) throw RuntimeDataFailure(RuntimeDataCode.BUSY)
        try {
            val record = synchronized(records) {
                val value = records[fileName] ?: throw RuntimeDataFailure(RuntimeDataCode.NOT_FOUND)
                if (value.info.identity != identity) throw RuntimeDataFailure(RuntimeDataCode.DENIED)
                value
            }
            if (!live()) throw RuntimeDataFailure(RuntimeDataCode.CLOSED)
            val code = try {
                directory.withDescriptor { NativeRuntimePublication.removePublishedSnapshot(it, fileName, record.file.fd) }
            } catch (_: Exception) { -1 } catch (_: LinkageError) { -1 }
            if (code != 0 || !live()) { uncertain.set(true); return RuntimeDeletionResult.OUTCOME_UNKNOWN }
            try { synchronized(records) { records.remove(fileName); record.file.close() } }
            catch (_: Exception) { uncertain.set(true); return RuntimeDeletionResult.OUTCOME_UNKNOWN }
            return RuntimeDeletionResult.REMOVED
        } finally { busy.set(false); if (closed.get()) release() }
    }
    override fun close() {
        closed.set(true)
        pending?.close()
        if (!busy.get()) release()
    }
    private fun release() {
        synchronized(records) {
            records.values.forEach { try { it.file.close() } catch (_: Exception) { } }
            records.clear()
            try { directory.close() } catch (_: Exception) { }
        }
    }
    override fun toString() = "RuntimeSnapshotOwner(REDACTED)"
    companion object {
        private fun settledWithoutPublication(attempt: SqliteBackupClient?): Boolean = attempt == null || try {
            attempt.awaitSettled(1).safeToRelease
        } catch (_: Exception) { false } catch (_: LinkageError) { false }
        private val pattern = Regex("runtime-state-before-v2-migration-([1-9][0-9]{0,15})-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.sqlite")
        fun validName(value: String): Boolean {
            val match = pattern.matchEntire(value) ?: return false
            return match.groupValues[1].toLongOrNull()?.let { it <= 9007199254740991L } == true
        }
        private fun randomToken() = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun open(context: Context, source: RuntimeDatabaseFiles, active: () -> Boolean): RuntimeSnapshotOwner {
            RuntimePrivateDirectory.requireOwnerWorker(context)
            val directory = RuntimePrivateDirectory.open(context, listOf("v2", "sqlite")).use { it.openChild("backups", true) }
            var transferred = false
            try {
                val owner = RuntimeSnapshotOwner(context.applicationContext, source, directory, active)
                transferred = true
                return owner
            } finally { if (!transferred) try { directory.close() } catch (_: Exception) { } }
        }
    }
}
