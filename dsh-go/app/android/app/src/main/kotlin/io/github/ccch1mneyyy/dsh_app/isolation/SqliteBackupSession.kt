// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

/** Trusted worker policy. UID/PID inputs must be actual Binder/Process values,
 * never parcel fields. This does not authenticate a caller by itself and an OK
 * candidate is not an attestation of process retirement or publication.
 */
internal class SqliteBackupSession(private val ownerUid: Int, private val workerUid: Int, private val workerPid: Int) {
    private enum class Phase { NEW, ADMITTED, RUNNING, FINISHED, RETIRED }
    private var phase = Phase.NEW
    private var ownerPid = -1
    private var nonce: String? = null
    private var cancelled = false

    @Synchronized fun admit(callerUid: Int, callerPid: Int, session: String): Boolean {
        if (phase != Phase.NEW || ownerUid < 0 || workerUid < 0 || ownerUid == workerUid || workerPid <= 0 ||
            callerUid != ownerUid || callerPid <= 0 || callerPid == workerPid || !session.matches(Regex("[0-9a-f]{64}"))) return false
        ownerPid = callerPid
        nonce = session
        phase = Phase.ADMITTED
        return true
    }
    @Synchronized fun matches(callerUid: Int, callerPid: Int, session: String): Boolean =
        phase != Phase.NEW && phase != Phase.RETIRED && callerUid == ownerUid && callerPid == ownerPid && nonce == session

    @Synchronized fun begin(): Boolean {
        if (phase != Phase.ADMITTED || cancelled) return false
        phase = Phase.RUNNING
        return true
    }
    @Synchronized fun cancel(callerUid: Int, callerPid: Int, session: String): Boolean {
        if (!matches(callerUid, callerPid, session)) return false
        cancelled = true
        return true
    }
    @Synchronized fun isCancelled(): Boolean = cancelled

    @Synchronized fun finish(reply: SqliteBackupReply): SqliteBackupReply {
        if (phase != Phase.ADMITTED && phase != Phase.RUNNING) return SqliteBackupReply.UNAVAILABLE
        val wasRunning = phase == Phase.RUNNING
        phase = Phase.FINISHED
        if (cancelled) return SqliteBackupReply.decode(longArrayOf(5, 7, 0))
        return if (wasRunning) reply else SqliteBackupReply.UNAVAILABLE
    }
    @Synchronized fun retire() { cancelled = true; nonce = null; phase = Phase.RETIRED }
    override fun toString() = "SqliteBackupSession(REDACTED)"
}

/** Private Binder ABI, not an Agent tool. No caller PID/UID fields are carried.
 * START: interface, nonce, maxBytes(long), timeoutMs(int), Main callback Binder,
 * eight typed ParcelFileDescriptors. CANCEL/RETIRE: interface, nonce only.
 * Reply: writeNoException + ACK code (0 accepted, 1 refused).
 * Callback PEER: callback interface, nonce; RESULT additionally has long[3].
 * PEER is identity admission, NOT native readiness or runtime ready.
 * Callback reply: writeNoException + ACK. Every admitted worker self-retires.
 */
internal object SqliteBackupWire {
    const val DESCRIPTOR = "io.github.ccch1mneyyy.dsh_app.sqlite_backup.v1"
    const val CALLBACK = "io.github.ccch1mneyyy.dsh_app.sqlite_backup_callback.v1"
    const val START = 1
    const val CANCEL = 2
    const val RETIRE = 3
    const val PEER = 1
    const val RESULT = 2
    const val ACCEPTED = 0
    const val REFUSED = 1
}
