// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.runtime

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.security.SecureRandom

/** The local Bot service session the guest will publish, plus the native model
 * broker session it uses for model requests. Both are loopback credentials:
 * the provider key never appears here and never leaves the native side. */
internal class RuntimeDescriptorSession(
    val entryPath: String,
    val pinCommit: String,
    val port: Int,
    val token: String,
    val brokerBaseUrl: String,
    val brokerToken: String,
) {
    companion object {
        fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }
}

/** Trusted hand-off between the Android host and the guest runtime.
 *
 * The descriptor carries the guest entry path, the pinned upstream commit, the
 * loopback port and the session token of the local Bot service, plus the
 * native model broker session the guest uses for model requests. The host
 * writes it into its own private directory - the guest's HOME - with mode
 * 0600, and the guest reads it back.
 *
 * It never travels through argv or the environment: a process list, an
 * inherited variable or a world-readable file would hand a live session to
 * the Agent. A descriptor that is not an owner-only regular file owned by
 * this app is refused on both sides, and a pre-existing symlink is replaced
 * rather than followed. */
internal object RuntimeDescriptor {
    const val FILE_NAME = "runtime-descriptor.json"
    private const val FILE_MODE = 384 /* 0600 */

    /** Write the descriptor into [directory] and return its path. */
    fun write(directory: File, session: RuntimeDescriptorSession): File {
        require(session.entryPath.startsWith("/") && session.entryPath.none { it.code == 0 }) {
            "entry path must be absolute"
        }
        require(session.pinCommit.matches(Regex("[0-9a-f]{40}"))) { "pinned commit required" }
        require(session.port in 1..65535) { "loopback port required" }
        require(session.token.matches(Regex("[0-9a-f]{32,64}"))) { "session token required" }
        require(session.brokerToken.matches(Regex("[0-9a-f]{32,64}"))) { "broker token required" }
        val brokerPort = session.brokerBaseUrl.removePrefix("http://127.0.0.1:").toIntOrNull()
        require(brokerPort != null && brokerPort in 1..65535 &&
            session.brokerBaseUrl == "http://127.0.0.1:$brokerPort") { "loopback broker required" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("descriptor_directory_missing")
        val target = File(directory, FILE_NAME)
        val staging = File(directory, ".$FILE_NAME.${System.nanoTime()}.tmp")
        var handle: FileDescriptor? = null
        try {
            // O_EXCL plus O_NOFOLLOW: a planted file or symlink is never
            // written through, and the mode is applied at creation.
            handle = Os.open(staging.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, FILE_MODE)
            val bytes = encode(session).toByteArray(Charsets.UTF_8)
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

    private fun encode(session: RuntimeDescriptorSession): String {
        val broker = """{"baseUrl":${quote(session.brokerBaseUrl)},"token":${quote(session.brokerToken)}}"""
        return """{"entryPath":${quote(session.entryPath)},"pinCommit":${quote(session.pinCommit)},""" +
            """"port":${session.port},"token":${quote(session.token)},"broker":$broker}"""
    }

    private fun quote(value: String): String {
        val out = StringBuilder()
        for (character in value) {
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character < ' ' -> out.append("\\u%04x".format(character.code))
                else -> out.append(character)
            }
        }
        return "\"$out\""
    }
}
