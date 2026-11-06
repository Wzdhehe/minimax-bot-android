// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

/** Main-side receipt policy only. The adapter must supply real Binder identity
 * and call workerDied ONLY for the anchored control Binder's death. */
internal class SqliteBackupExchange(private val ownerUid: Int, private val ownerPid: Int, private val nonce: String) {
    enum class Disposition { UNKNOWN, NOT_TRANSFERRED, RETIRED }
    class Outcome internal constructor(val disposition: Disposition, val reply: SqliteBackupReply) {
        val safeToRelease: Boolean get() = disposition != Disposition.UNKNOWN
        val candidateReady: Boolean get() = disposition == Disposition.RETIRED && reply.candidateReady
        override fun toString() = "SqliteBackupOutcome($disposition, candidate=$candidateReady)"
    }
    private var sending = false
    private var senderDone = false
    private var notTransferred = false
    private var died = false
    private var cancelled = false
    private var peerUid = -1
    private var peerPid = -1
    private var answered = false
    private var candidate = SqliteBackupReply.UNAVAILABLE

    @Synchronized fun beginSend(): Boolean {
        if (sending || notTransferred || cancelled || died) return false
        sending = true // Before parcel encoding; ambiguous delivery requires retirement.
        return true
    }
    @Synchronized fun senderFinished() { senderDone = true }
    @Synchronized fun failBeforeSend() {
        cancelled = true
        candidate = SqliteBackupReply.UNAVAILABLE
        if (!sending) notTransferred = true
    }
    @Synchronized fun bindingLost() {
        // Normal self-retirement also disconnects the binding. After sending,
        // keep any definitive reply and wait for the actual death recipient.
        if (!sending) failBeforeSend()
    }
    @Synchronized fun peer(uid: Int, pid: Int, session: String): Boolean {
        if (!sending || cancelled || died || peerUid != -1 || session != nonce ||
            uid <= 0 || uid == ownerUid || pid <= 0 || pid == ownerPid) return false
        peerUid = uid; peerPid = pid
        return true
    }
    @Synchronized fun result(uid: Int, pid: Int, session: String, reply: SqliteBackupReply): Boolean {
        if (!sending || cancelled || died || answered || peerUid == -1 || uid != peerUid || pid != peerPid || session != nonce) return false
        answered = true
        candidate = reply
        return true
    }
    @Synchronized fun cancel() { cancelled = true; candidate = SqliteBackupReply.UNAVAILABLE }
    @Synchronized fun isCancelled(): Boolean = cancelled
    @Synchronized fun workerDied() { died = true }
    @Synchronized fun outcome(): Outcome {
        val disposition = when {
            notTransferred && !sending -> Disposition.NOT_TRANSFERRED
            sending && senderDone && died -> Disposition.RETIRED
            else -> Disposition.UNKNOWN
        }
        val reply = if (disposition == Disposition.RETIRED && !cancelled && answered && peerUid != -1) candidate
            else SqliteBackupReply.UNAVAILABLE
        return Outcome(disposition, reply)
    }
    /** Still only a candidate: owner must also hold its active generation and
     * publication lock. This does not authorize a rename or grant by itself. */
    @Synchronized fun candidateStillValid(reply: SqliteBackupReply): Boolean {
        val current = outcome()
        return current.candidateReady && current.reply === reply
    }
    override fun toString() = "SqliteBackupExchange(REDACTED)"
}
