<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Native SQLite backup kernel

`backup.cpp` is a trusted native copy/validation component. It is **not yet wired into Android, JNI/Binder, the runtime backup port, publication or a product APK**. It accepts dedicated SQLite connections, never paths, SQL strings, descriptors from an Agent, provider metadata or credentials. Do not expose it as an Agent tool.

The source connection must be read-only and both connections must be outside any caller transaction. The destination must be a fresh private empty database that an Agent has **never held a writable descriptor to**. The kernel pins a read transaction before checking the source page budget, then uses the real `sqlite3_backup` API. A concurrent WAL writer may commit/grow the source while the chosen snapshot stays consistent.

Limits are 4 KiB–512 MiB for the snapshot budget and 1–30,000 ms for SQLite work/waits. An optional one-way atomic cancellation flag must remain alive until the function returns. Stable `BackupResult` values distinguish refusal, size, busy, timeout, cancellation, copy and integrity failure; no platform error text or database content is returned. SQLite work is interrupted by a joined native watchdog because the existing pinned build omits progress callbacks; its compile definitions are unchanged. These limits cannot bound an uninterruptible kernel I/O operation.

The function releases its read transaction and joins the watchdog before returning, including failure paths. The caller owns connection close. A successful result alone does **not** authorize publication: the Android owner must still verify active generation/cancellation, close the trusted writer, sync data/sidecars, publish durably through its held directory capability, independently validate/reopen read-only and issue only read-only snapshot grants. A publication with an unknown result must be reconciled, never retried automatically. This owner/IPC lifecycle and the upstream snapshot filesystem view remain unimplemented.

An Agent must not write a backup and later promise to discard its writable FDs. Duplicates cannot be revoked by closing an owner copy. The future trusted native owner must select the source and private destination itself; neither model input nor a guest path may choose them.

Host acceptance from the repository root:

```bash
python3 dsh-go/tooling/android-node-probe/test-sqlite-backup.py
```

The runner verifies/extracts the source archive pinned by `android-node-probe/sqlite-runtime-pin.json`, keeps its compile definitions, and builds the real SQLite/VFS/kernel. It checks read-only admission, an actual cross-process WAL commit/growth after snapshot selection, an unchanged old snapshot, size/cancellation/busy/failure cleanup, integrity failure, no overwriting a nonempty destination and independent read-only reopening. In-memory destinations are used only for negative integrity/cancellation cases; the positive path uses eight real precreated file FDs and the writer receives only the four source FDs.

This is Linux host evidence. Android compilation/execution, UID enforcement, crash/power-loss publication recovery, production cancellation, ARM64 and real model requests are **unverified**. The host VFS is still the verification adapter; this component does not turn it into a complete product filesystem.

## Granted-FD worker composition

`fd_backup.cpp::runGrantedSqliteBackup` composes admission, copy, close, file sync and an independently reopened read-only integrity check. It accepts exactly eight regular file FDs: the fixed source family and a fresh private snapshot family. It duplicates before inspecting, rejects wrong ownership, public/special permissions, links, repeated inodes, directories/pipes, unusable access/append flags and nonempty snapshot files. Caller descriptors remain owned by the caller. Only a successful reply's byte count is meaningful; failure does not imply an unwritten destination.

**Run this only in a fresh, one-shot trusted native worker process.** The owner PID must differ from the worker PID. A known existing FD VFS is refused before creating/closing aliases to its live SQLite files. Neither check substitutes for Android caller authentication or Main's source/destination policy. The expected UID/PID must come from trusted Main state, never model input. Do not call this in a long-lived Agent or Main SQLite process; alias closure could release unrelated POSIX file locks there.

Admission through teardown has a nonblocking process-local gate. A second call is refused before duplicating anything, including the interval where the first call has not registered its VFS yet. The host test deterministically pauses the first admission there and checks refusal of the competing call; process retirement is still required after the admitted operation.

The worker's VFS retains duplicate descriptors. After any admitted attempt the whole execution unit must retire, and Main must confirm that retirement before publication or read-only grants. The function does not attest process exit, publish a file, grant a capability, authenticate IPC or revoke already transferred FDs. Main must have synced the precreated file entries and private directory before handoff; the worker cannot sync a directory and never receives its descriptor.

The same host runner additionally executes separate seeding, invalid-grant, copying and read-only-check processes; its parent never opens SQLite. It verifies caller-FD preservation, same-worker reuse refusal, size/cancel refusal, sync failure, EINTR continuation and post-close integrity failure. Failed private output is discarded only after that test worker exits, and a fresh snapshot family is created for a subsequent independent attempt. GNU link-time faults affect only the host acceptance executable, not production code.

This composition is still **not wired into Android services/JNI/Binder or the guest backup/filesystem port**. A successful host worker is not a product runtime boot or proof of isolated Android UID behavior. The Node verification adapter remains a test dependency; a product native-library packaging path is also outstanding.
