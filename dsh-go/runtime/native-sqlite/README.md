<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Native SQLite backup kernel

`backup.cpp` is a trusted native copy/validation component. A JNI candidate now exists, but it is **not yet wired into an Android worker/Binder, the runtime backup port, publication or a product APK**. It accepts dedicated SQLite connections, never paths, SQL strings, descriptors from an Agent, provider metadata or credentials. Do not expose it as an Agent tool.

The source connection must be read-only and both connections must be outside any caller transaction. The destination must be a fresh private empty database that an Agent has **never held a writable descriptor to**. The kernel pins a read transaction before checking the source page budget, then uses the real `sqlite3_backup` API. A concurrent WAL writer may commit/grow the source while the chosen snapshot stays consistent.

Limits are 4 KiB–512 MiB for the snapshot budget and 1–30,000 ms for SQLite work/waits. An optional one-way atomic cancellation flag must remain alive until the function returns. Stable `BackupResult` values distinguish refusal, size, busy, timeout, cancellation, copy and integrity failure; no platform error text or database content is returned. SQLite work is interrupted by a joined native watchdog because the existing pinned build omits progress callbacks; its compile definitions are unchanged. These limits cannot bound an uninterruptible kernel I/O operation.

The function releases its read transaction and joins the watchdog before returning, including failure paths. The caller owns connection close. A successful result alone does **not** authorize publication: the Android owner must still verify active generation/cancellation, close the trusted writer, sync data/sidecars, publish durably through its held directory capability, independently validate/reopen read-only and issue only read-only snapshot grants. A publication with an unknown result must be reconciled, never retried automatically. This owner/IPC lifecycle and the upstream snapshot filesystem view remain unimplemented.

An Agent must not write a backup and later promise to discard its writable FDs. Duplicates cannot be revoked by closing an owner copy. The future trusted native owner must select the source and private destination itself; neither model input nor a guest path may choose them.

Host acceptance from the repository root:

```bash
python3 dsh-go/tooling/android-node-probe/test-sqlite-backup.py
```

The runner requires a complete Linux JDK (compiler plus JNI headers), discovered from `JAVA_HOME`/`javac`, or selected with `--java-home /path/to/jdk`. It verifies/extracts the source archive pinned by `android-node-probe/sqlite-runtime-pin.json`, keeps its compile definitions, and builds the real SQLite/VFS/kernel. It checks read-only admission, an actual cross-process WAL commit/growth after snapshot selection, an unchanged old snapshot, size/cancellation/busy/failure cleanup, integrity failure, no overwriting a nonempty destination and independent read-only reopening. In-memory destinations are used only for negative integrity/cancellation cases; the positive path uses eight real precreated file FDs and the writer receives only the four source FDs.

This is Linux host evidence. Android compilation/execution, UID enforcement, crash/power-loss publication recovery, production cancellation, ARM64 and real model requests are **unverified**. The host VFS is still the verification adapter; this component does not turn it into a complete product filesystem.

## Granted-FD worker composition

`fd_backup.cpp::runGrantedSqliteBackup` composes admission, copy, close, file sync and an independently reopened read-only integrity check. It accepts exactly eight regular file FDs: the fixed source family and a fresh private snapshot family. It duplicates before inspecting, rejects wrong ownership, public/special permissions, links, repeated inodes, directories/pipes, unusable access/append flags and nonempty snapshot files. Caller descriptors remain owned by the caller. Only a successful reply's byte count is meaningful; failure does not imply an unwritten destination.

**Run this only in a fresh, one-shot trusted native worker process.** The owner PID must differ from the worker PID. A known existing FD VFS is refused before creating/closing aliases to its live SQLite files. Neither check substitutes for Android caller authentication or Main's source/destination policy. The expected UID/PID must come from trusted Main state, never model input. Do not call this in a long-lived Agent or Main SQLite process; alias closure could release unrelated POSIX file locks there.

Admission through teardown has a nonblocking process-local gate. A second call is refused before duplicating anything, including the interval where the first call has not registered its VFS yet. The host test deterministically pauses the first admission there and checks refusal of the competing call; process retirement is still required after the admitted operation.

The worker's VFS retains duplicate descriptors. After any admitted attempt the whole execution unit must retire, and Main must confirm that retirement before publication or read-only grants. The function does not attest process exit, publish a file, grant a capability, authenticate IPC or revoke already transferred FDs. Main must have synced the precreated file entries and private directory before handoff; the worker cannot sync a directory and never receives its descriptor.

The same host runner additionally executes separate seeding, invalid-grant, copying and read-only-check processes; its parent never opens SQLite. It verifies caller-FD preservation, same-worker reuse refusal, size/cancel refusal, sync failure, EINTR continuation and post-close integrity failure. Failed private output is discarded only after that test worker exits, and a fresh snapshot family is created for a subsequent independent attempt. GNU link-time faults affect only the host acceptance executable, not production code.

This composition is still **not wired into Android services/Binder or the guest backup/filesystem port**. A successful host worker is not a product runtime boot or proof of isolated Android UID behavior. The Node verification adapter remains a test dependency; a product native-library packaging path is also outstanding.

## JNI candidate (host verified, Android not wired)

`jni_backup.cpp` and `NativeSqliteBackupBindings.kt` define two fixed JNI methods: `run(int[8], ownerUid, ownerPid, maxBytes, timeoutMs)` and one-way `cancel()`. Neither accepts a path, SQL, a directory FD or a credential. An atomic process-lifetime claim permits only one invocation, even after bad input, cancellation or failure. The cancellation flag is never reset. A fixed three-long reply encodes explicit stage/result codes and candidate bytes; no platform exception/error text or database contents crosses this boundary. JNI allocation failure may throw rather than return a result; that never authorizes publication.

`SqliteBackupReply.decode` rejects wrong shape, unknown codes, impossible success/failure combinations and invalid byte counts. `candidateReady` means only that copying/sync/checks returned OK. It does **not** prove worker exit, active generation, durable publication or a read-only grant; a failure with zero bytes does **not** prove an unwritten destination.

The library must be loaded only by a fresh trusted native worker after authenticating Main and checking that the worker has a distinct isolated UID. Those Android checks and the actual worker are **not implemented by this JNI candidate**. Do not load it from Main, Agent Node, an arbitrary shell or an Agent-callable tool. Owner UID/PID parameters are trusted policy inputs, not caller authentication. Android workers originate from zygote, so `getppid()` is not a way to discover Main. Preserve input FD ownership and confirm full worker retirement before publication, including JNI exceptions and cancellation.

The host runner now loads the real shared library in separate JVM processes using a Java fixture with the same binary class/native signatures as Kotlin. It tests eight inherited FDs, null/wrong-length arrays, invalid UID, owner-self PID refusal, pre-cancellation, repeated calls and an actual copied database independently reopened after worker exit. The exact Kotlin declaration and decoder also compile in the native boundary suite; four decoder cases cover finite failure codes, mutable-wire isolation, malformed replies and impossible success. JNI symbols must retain their binary names (App R8 keep rule added). This is **Linux JVM evidence, not an Android service, Binder caller check or device execution**. The separate NDK compilation task predates this JNI file and does not cover its target linkage.
