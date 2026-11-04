<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Isolated Android Node verification

The current persistence candidate hands off four precreated regular data FDs.
The owner syncs the files and directory before handoff; no directory FD crosses
Binder. Sidecar deletion syncs its truncated file, while the physical directory
entries remain immutable. The earlier five-FD candidate failed at Main's IPC
send on API26/35 before SQLite ran; the four-FD candidate passed API26/35 emulator validation in run41703671145
(including two fresh isolated processes). Physical devices/ARM64 remain unverified. See [T-Persistence](../../../docs/handoff/T-Persistence.md) for evidence,
preconditions and the limits of this verification target.

This is a separate instrumentation target with no launcher, not a product runtime.
It embeds a pinned Node24 candidate in a private `isolatedProcess` service, runs
one fixed APK script, and consumes production native model channels. Only fake
provider keys generated in the owner process are used. No real account/API call,
main-UID Agent/shell, product launcher switch, APK or library publication occurs.

The current fixed Node script also uses an asynchronous linked file-commit
binding backed by the production Main `RuntimeFileCommitHost`. Main selects a
private scope and replacement rights; no directory, real root or session is
exposed to JS. It checks an 84KiB offset-view snapshot, exclusive publication,
replacement, empty files and rejection of shared/resizable/detached buffers.
The owner verifies exact files across both Node PIDs and extends Key assertions
to these files and the file capability. This is still not a complete `node:fs`
adapter or the official Bot backend. Device results for this revision must be
checked separately from the earlier 25-test Kotlin-only run41769936256.

The authoritative candidate source/version/archive/binary digests are in
`node-runtime-pin.json`. The archive's complete upstream notices, mobile project
license and NDK notice are packaged as assets. No binaries belong in Git.

```bash
python3 dsh-go/tooling/android-node-probe/prepare-probe.py \
  --ndk /actual/android-ndk-r27d --archive /actual/nodejs-mobile-v24.22.2-android.zip
gradle --no-daemon -p dsh-go/tooling/android-node-probe assembleDebug assembleDebugAndroidTest
# With an authorized x86_64 Android device/emulator only:
bash dsh-go/tooling/android-node-probe/run-device-tests.sh
```

JDK21/Gradle9.3.1/platform36/BuildTools36 and NDK27.3.13750724 are required.
SDK CMake3.22.1 is also required: both this target and the App build the same
production `mbot_runtime_files` library from the App's CMakeLists. No separate
publication-probe implementation remains. Its x86_64/arm64-v8a compilation,
JNI export and ELF16KiB alignment can be checked without running a device:

```bash
python3 dsh-go/tooling/android-node-probe/check-native-files.py \
  --ndk /actual/android-ndk-r27d --cmake /actual/sdk/cmake/3.22.1/bin/cmake
```

This is ARM64 **compilation** evidence only, not Node/Bot/device execution.
Omit `--archive` to download the exact pinned HTTPS archive and verify its digest.
`--bundle-only` builds just the fixed JS asset for host checks; Gradle refuses to
build a target without the prepared native libraries and notices.

The release candidate has **no Intl**, which the official Bot i18n module needs.
This probe therefore cannot establish full Bot/mcode compatibility. With the
SQLite preparation below it does exercise the restricted four-FD VFS, WAL and
two fresh isolated processes; API26/35 passed in run41703671145. It does not
establish full runtime FS, Node on ARM64, hardware Keystore, physical-device or
real 16KiB page behavior. ELF16KiB alignment is a static property only. See root
`docs/handoff/T-AndroidNode.md` and `T-RuntimeFS.md` for actual results.

The separate `android-node-source.yml` workflow rebuilds the pinned mobile source
with full ICU, preserving the upstream checkout. Its temporary private ZIP is
verified by the build job's SHA256 before JNI preparation and device checks.
`--source-build-sha` is for that trusted CI input, never an Agent/runtime setting.
The full-ICU path additionally requires Chinese Intl and Asia/Shanghai time-zone
checks. A passing default-candidate test does not imply this source build passed.

## SQLite addon verification

The candidate workflow additionally builds pinned better-sqlite3 12.11.1 from
its npm source archive using NDK27d and the verified Node24 headers. Run
`prepare-probe.py` first, then `prepare-sqlite.py --ndk /path/to/ndk27d
--esbuild /path/to/esbuild`. The latter checks input hashes/ABI, bundles the
unchanged JS wrapper and includes upstream notices. No install scripts or
Linux addon binaries run. Generated libraries/assets remain ignored.

The fixed isolated Node script loads the APK native addon using its official
`nativeBinding` object option and tests in-memory transactions, rollback,
Unicode, JSON, serialization and restoration. The four-FD VFS additionally tests
restricted WAL/reopen/two-PID reads; it does **not** solve the complete runtime
FS, descriptor revocation, online backup or complete mcode startup. Only the
API26/35 instrumentation result can establish Android execution; host tests
and successful native compilation cannot. Full ICU + SQLite is a separate
combination to verify. See [T-SQLite](../../../docs/handoff/T-SQLite.md).

## Dynamic model request check

The verification JNI now registers a fixed linked binding whose open returns a
Promise. At most four libuv workers call the isolated Kotlin Binder client;
1 MiB bodies and 128-character request IDs are bounded before copying.
The owner binds the actual UID, selected provider/model, session and peer
lifetime. Node only receives provider metadata and an owned result FD; the
native session and Key stay outside JS. The fixed script checks a dynamic
request and cancel-before-open alongside the existing prefetched FD tests.
JNI/platform exceptions become a stable error without diagnostics.

The host script test skips JNI because it runs normal desktop Node. Only the
API26/35 device job can validate this branch. Production launch/model SDK
wiring, persistent storage are still missing. Requests above 64 KiB now use a bounded
pipe (exact length/EOF/UTF8, read deadline and cancellation), keeping large
strings out of Binder. The fixed script additionally sends an 84 KiB request.
