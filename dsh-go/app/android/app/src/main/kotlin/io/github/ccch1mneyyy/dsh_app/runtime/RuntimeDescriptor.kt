// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.os.Process
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/** Trusted hand-off between the Android host and the guest runtime.
 *
 * The descriptor carries the guest entry path, the pinned upstream commit, the
 * loopback port and the session token of the local Bot service, plus an
 * optional native model broker session. The host
 * writes it into an owner-only private directory with mode 0600. An isolated
 * guest cannot traverse that directory: [writeForTransfer] provides the
 * read-only file capability that a trusted launcher may explicitly transfer.
 *
 * It never travels through argv or the environment: a process list, an
 * inherited variable or a world-readable file would hand a live session to
 * the Agent. A descriptor that is not an owner-only regular file owned by
 * this app is refused on both sides, and a pre-existing symlink is replaced
 * rather than followed. This utility is not itself a runtime launcher. */
internal object RuntimeDescriptor {
    const val FILE_NAME = "runtime-descriptor.json"
    private const val MAX_DESCRIPTOR_BYTES = 1024 * 1024
    private const val FILE_MODE = 384 /* 0600 */

    /** Write the descriptor into [directory] and return its path. */
    @Synchronized fun write(directory: File, session: RuntimeDescriptorSession): File {
        require(session.entryPath.startsWith("/") && session.entryPath.none { it.code == 0 }) {
            "entry path must be absolute"
        }
        require(session.pinCommit.matches(Regex("[0-9a-f]{40}"))) { "pinned commit required" }
        require(session.port in 1..65535) { "loopback port required" }
        require(session.token.matches(Regex("[0-9a-f]{32,64}"))) { "session token required" }
        val brokerUrl = session.brokerBaseUrl
        val brokerToken = session.brokerToken
        require((brokerUrl == null) == (brokerToken == null)) { "broker session incomplete" }
        if (brokerUrl != null && brokerToken != null) {
            require(brokerToken.matches(Regex("[0-9a-f]{32,64}"))) { "broker token required" }
            val brokerPort = brokerUrl.removePrefix("http://127.0.0.1:").toIntOrNull()
            require(brokerPort != null && brokerPort in 1..65535 &&
                brokerUrl == "http://127.0.0.1:$brokerPort") { "loopback broker required" }
        }
        val bytes = RuntimeDescriptorPayload.encode(session).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_DESCRIPTOR_BYTES) { "descriptor size invalid" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("descriptor_directory_missing")
        val target = File(directory, FILE_NAME)
        val staging = File(directory, ".$FILE_NAME.${System.nanoTime()}.tmp")
        var handle: FileDescriptor? = null
        try {
            // O_EXCL plus O_NOFOLLOW: a planted file or symlink is never
            // written through, and the mode is applied at creation.
            handle = Os.open(staging.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, FILE_MODE)
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.write(handle, bytes, offset, bytes.size - offset)
                if (count <= 0) throw IllegalStateException("descriptor_write_failed")
                offset += count
            }
            Os.fsync(handle)
            Os.close(handle)
            handle = null
            // rename replaces the target itself, so an existing symlink at
            // that name is removed instead of being followed.
            Os.rename(staging.path, target.path)
            syncDirectory(directory)
            check(descriptorIsOwnerOnly(target)) { "descriptor_permissions" }
            return target
        } catch (error: ErrnoException) {
            throw IllegalStateException("descriptor_write_failed", error)
        } finally {
            try { handle?.let { Os.close(it) } } catch (_: Exception) { }
            if (staging.exists()) try { staging.delete() } catch (_: Exception) { }
        }
    }

    /**
     * Publish and open the same generation while holding the writer lock.
     * Only this read-only regular-file capability is transferred across UID;
     * the isolated process never needs access to the private directory.
     * The caller owns the returned descriptor and must close/transfer it.
     * Parent directories remain trusted owner-controlled directories.
     */
    @Synchronized fun writeForTransfer(directory: File, session: RuntimeDescriptorSession): ParcelFileDescriptor {
        val target = write(directory, session)
        var handle: FileDescriptor? = null
        try {
            handle = Os.open(target.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            val info = Os.fstat(handle)
            check((info.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG &&
                info.st_uid == Process.myUid() && (info.st_mode and 63) == 0 &&
                info.st_nlink == 1L && info.st_size in 1..MAX_DESCRIPTOR_BYTES.toLong())
            return ParcelFileDescriptor.dup(handle)
        } catch (_: Exception) {
            throw IllegalStateException("descriptor_transfer_failed")
        } finally {
            try { handle?.let { Os.close(it) } } catch (_: Exception) { }
        }
    }

    /** The descriptor must be an owner-only regular file owned by this app. */
    fun descriptorIsOwnerOnly(target: File): Boolean = try {
        val info = Os.lstat(target.path)
        (info.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG && info.st_uid == Process.myUid() &&
            (info.st_mode and 63) == 0
    } catch (_: Exception) {
        false
    }

    /** Flush the directory entry so a reboot cannot lose the descriptor. */
    private fun syncDirectory(directory: File) {
        var handle: FileDescriptor? = null
        try {
            handle = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            Os.fsync(handle)
        } catch (_: Exception) {
            // A directory that cannot be flushed still has a durable file.
        } finally {
            try { handle?.let { Os.close(it) } } catch (_: Exception) { }
        }
    }

}
