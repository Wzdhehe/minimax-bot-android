// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.ccch1mneyyy.dsh_app.isolation.ModelRequestClient
import io.github.ccch1mneyyy.dsh_app.isolation.GrantedConnectionQueue
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeFileCommitClient
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeSnapshotClient
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDeletionResult
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimePublicationResult
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataFailure
import io.github.ccch1mneyyy.dsh_app.isolation.RuntimeDataCode
import java.util.concurrent.atomic.AtomicBoolean

/** Product JNI dispatch. Only the private isolated service configures it.
 * Model credentials remain in Main; this owns revocable Binder capabilities.
 * Data FD registry lives until this execution unit actually retires. */
internal object EmbeddedBotNode {
    @Volatile private var modelClient: ModelRequestClient? = null
    @Volatile private var fileClient: RuntimeFileCommitClient? = null
    @Volatile private var snapshotClient: RuntimeSnapshotClient? = null
    private val configurationClaimed = AtomicBoolean()
    private val accepting = AtomicBoolean(true)
    private val stopSignal = java.util.concurrent.CountDownLatch(1)
    private var assetReader: ((String) -> ByteArray)? = null
    private var readySignal: (() -> Boolean)? = null
    private var failureSignal: ((String) -> Unit)? = null
    private val startupProgress = BotRuntimeStartupProgress { android.util.Log.i("MiniMaxBotRuntime", it) }
    fun configure(context: android.content.Context, modelHost: IBinder, modelSession: String,
                  fileHost: IBinder, fileSession: String, snapshotHost: IBinder, snapshotSession: String,
                  readAsset: (String) -> ByteArray, ready: () -> Boolean, failed: (String) -> Unit) {
        val uid = android.os.Process.myUid()
        check(uid != context.applicationInfo.uid && uid % 100000 in 99000..99999) { "bot_runtime_isolation_required" }
        check(configurationClaimed.compareAndSet(false, true)) { "bot_runtime_already_started" }
        configureModelBroker(modelHost, modelSession)
        configureFileBroker(fileHost, fileSession)
        configureSnapshotBroker(snapshotHost, snapshotSession)
        assetReader = readAsset; readySignal = ready; failureSignal = failed
    }
    fun readStaticAsset(name: String): ByteArray = checkNotNull(assetReader)(name)
    fun markReady(): Boolean = accepting.get() && readySignal?.invoke() == true
    fun reportFailure(phase: String) { failureSignal?.invoke(phase) }
    fun reportStartupPhase(phase: String): Boolean = startupProgress.report(phase)
    fun waitForStop(): Int { try { stopSignal.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }; return 0 }
    fun stop() { accepting.set(false); startupProgress.close(); botConnections.close(); stopSignal.countDown() }
    private val botConnections = GrantedConnectionQueue<ParcelFileDescriptor>()
    fun offerBotConnection(fd: ParcelFileDescriptor) = botConnections.offer(fd)
    fun takeBotConnection(): Int {
        var next: ParcelFileDescriptor? = null
        while (accepting.get() && next == null) next = botConnections.take(30_000)
        val fd = next ?: return -1
        return try { fd.detachFd() } catch (_: Exception) { try { fd.close() } catch (_: Exception) { }; -1 }
    }
    fun closeBotConnections() { accepting.set(false); botConnections.close() }
    fun configureModelBroker(host: IBinder, session: String) { modelClient = ModelRequestClient(host, session) }
    fun configureFileBroker(host: IBinder, session: String) { fileClient = RuntimeFileCommitClient(host, session) }
    fun configureSnapshotBroker(host: IBinder, session: String) { snapshotClient = RuntimeSnapshotClient(host, session) }
    fun closeSnapshotBroker() { snapshotClient = null }
    fun copyRuntimeSnapshot(name: String): Int = try {
        when (snapshotClient?.copy(name)) {
            "published" -> 0; "already_exists" -> 1; "not_published" -> 2; else -> 3
        }
    } catch (_: Exception) { 3 }
    fun catalogRuntimeSnapshots(): String? = try { snapshotClient?.catalogJson() } catch (_: Exception) { null }
    fun openRuntimeSnapshot(name: String, identity: String): Int {
        val client = snapshotClient ?: return -1
        return try { client.openReadOnly(name, identity).use { it.detachFd() } } catch (_: Exception) { -1 }
    }
    fun removeRuntimeSnapshot(name: String, identity: String): Int = try {
        if (snapshotClient?.remove(name, identity) == "removed") 0 else 1
    } catch (_: Exception) { 1 }
    private fun fileError(code: RuntimeDataCode): Int = when (code) {
        RuntimeDataCode.INVALID -> 3; RuntimeDataCode.DENIED -> 4; RuntimeDataCode.CLOSED -> 5
        RuntimeDataCode.NOT_FOUND -> 7; RuntimeDataCode.TOO_LARGE -> 8; else -> 6
    }
    fun readRuntimeFile(name: String): ByteArray {
        val client = fileClient ?: return byteArrayOf(6)
        return try {
            val bytes = client.read(name)
            ByteArray(bytes.size + 1).also { bytes.copyInto(it, 1) }
        } catch (error: RuntimeDataFailure) { byteArrayOf(fileError(error.code).toByte()) }
        catch (_: Exception) { byteArrayOf(6) }
    }
    fun sizeRuntimeFile(name: String): Long {
        val client = fileClient ?: return -6L
        return try { client.size(name) }
        catch (error: RuntimeDataFailure) { -fileError(error.code).toLong() }
        catch (_: Exception) { -6L }
    }
    fun commitRuntimeFile(name: String, bytes: ByteArray, replace: Boolean): Int {
        val client = fileClient ?: return 6
        return try {
            when (if (replace) client.replace(name, bytes) else client.publishIfAbsent(name, bytes)) {
                RuntimePublicationResult.PUBLISHED -> 0
                RuntimePublicationResult.ALREADY_EXISTS -> 1
                RuntimePublicationResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) {
            fileError(error.code)
        } catch (_: Exception) { 2 }
    }
    fun appendRuntimeFile(name: String, bytes: ByteArray): Int {
        val client = fileClient ?: return 6
        return try {
            when (client.append(name, bytes)) {
                RuntimePublicationResult.PUBLISHED -> 0
                RuntimePublicationResult.ALREADY_EXISTS -> 2 // Append cannot report an exclusive-create conflict.
                RuntimePublicationResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) { fileError(error.code) }
        catch (_: Exception) { 2 }
    }
    fun moveRuntimeFile(source: String, target: String, replace: Boolean): Int {
        val client = fileClient ?: return 6
        return try {
            when (client.move(source, target, replace)) {
                RuntimePublicationResult.PUBLISHED -> 0
                RuntimePublicationResult.ALREADY_EXISTS -> 1
                RuntimePublicationResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) { fileError(error.code) }
        catch (_: Exception) { 2 }
    }
    fun removeRuntimeFile(name: String): Int {
        val client = fileClient ?: return 6
        return try {
            when (client.remove(name)) {
                RuntimeDeletionResult.REMOVED -> 0
                RuntimeDeletionResult.OUTCOME_UNKNOWN -> 2
            }
        } catch (error: RuntimeDataFailure) { fileError(error.code) }
        catch (_: Exception) { 2 }
    }
    fun openModelFd(id: String, body: String): Int = try { modelClient?.open(id, body)?.detachFd() ?: -1 } catch (_: Exception) { -1 }
    fun cancelModelFd(id: String) { try { modelClient?.cancel(id) } catch (_: Exception) { } }
    fun load() { check(configurationClaimed.get()); System.loadLibrary("mbot_node_runtime") }
    external fun start(script: String, descriptorFd: Int, ownerUid: Int, nativeLibraries: String, dataFds: IntArray): Int
    override fun toString() = "EmbeddedBotNode(REDACTED)"
}
