// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

/** A copied candidate, never an attestation of worker exit or publication. */
internal class SqliteBackupReply private constructor(
    val stage: Stage, val result: Result, val bytes: Long,
) {
    enum class Stage(val wire: Long) {
        OK(0), INVALID_WORKER(1), INVALID_GRANT(2), REGISTRY_UNAVAILABLE(3),
        OPEN_FAILED(4), COPY_FAILED(5), CLOSE_FAILED(6), SYNC_FAILED(7), VERIFICATION_FAILED(8),
        UNAVAILABLE(-1),
    }
    enum class Result(val wire: Long) {
        OK(0), INVALID(1), SOURCE_NOT_READONLY(2), DESTINATION_NOT_EMPTY(3), TOO_LARGE(4),
        BUSY(5), TIMED_OUT(6), CANCELLED(7), COPY_FAILED(8), INTEGRITY_FAILED(9),
    }
    val candidateReady: Boolean get() = stage == Stage.OK

    companion object {
        val UNAVAILABLE = SqliteBackupReply(Stage.UNAVAILABLE, Result.INVALID, 0)
        fun decode(wire: LongArray?): SqliteBackupReply {
            if (wire == null || wire.size != 3) return UNAVAILABLE
            // Copy scalar values before checking, never retain a mutable wire.
            val stageCode = wire[0]
            val resultCode = wire[1]
            val bytes = wire[2]
            val stage = Stage.entries.firstOrNull { it.wire == stageCode && it != Stage.UNAVAILABLE }
                ?: return UNAVAILABLE
            val result = Result.entries.firstOrNull { it.wire == resultCode } ?: return UNAVAILABLE
            if (stage == Stage.OK) {
                if (result != Result.OK || bytes <= 0 || bytes > 512L * 1024 * 1024) return UNAVAILABLE
            } else if (result == Result.OK || bytes != 0L) return UNAVAILABLE
            return SqliteBackupReply(stage, result, bytes)
        }
    }
}
