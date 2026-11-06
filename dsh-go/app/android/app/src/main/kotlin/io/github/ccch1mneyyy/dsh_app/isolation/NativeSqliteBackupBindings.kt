// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ccch1mneyyy.dsh_app.isolation

/** JNI ABI only, not an IPC endpoint. A future trusted one-shot worker must
 * authenticate Main and check its isolated UID BEFORE loading this object.
 * This library is not packaged or called by the product yet. Never load it
 * in Main or a live Agent. Its FD registry survives until process retirement.
 */
internal object NativeSqliteBackupBindings {
    init { System.loadLibrary("mbot_sqlite_backup") }

    /** Exactly eight caller-owned data FDs; no paths, SQL or credentials.
     * Result: [stage code, result code, bytes]. Failure bytes do not mean that
     * nothing was written. One invocation per process, including bad input.
     * UID/PID must come from authenticated Main, not getppid() (Android zygote).
     */
    external fun run(descriptors: IntArray, ownerUid: Int, ownerPid: Int, maxBytes: Long, timeoutMs: Int): LongArray

    /** One-way process-lifetime cancellation; safe on a separate control thread. */
    external fun cancel()
}
