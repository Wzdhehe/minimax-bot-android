<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Isolated Android Node verification

This is a separate instrumentation target with no launcher, not a product runtime.
It embeds a pinned Node24 candidate in a private `isolatedProcess` service, runs
one fixed APK script, and consumes production native model channels. Only fake
provider keys generated in the owner process are used. No real account/API call,
main-UID Agent/shell, product launcher switch, APK or library publication occurs.

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
Omit `--archive` to download the exact pinned HTTPS archive and verify its digest.
`--bundle-only` builds just the fixed JS asset for host checks; Gradle refuses to
build a target without the prepared native libraries and notices.

The release candidate has **no Intl**, which the official Bot i18n module needs.
This probe therefore cannot establish full Bot/mcode compatibility. It also does
not load better-sqlite3, test SQLite/WAL/data persistence, phone ARM64, hardware
Keystore, physical devices or real 16KiB page behavior. ELF16KiB alignment is a
static property only. See root `docs/handoff/T-AndroidNode.md` for actual results.

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
Unicode, JSON, serialization and restoration. This does **not** solve isolated
UID storage, WAL, restart durability or the complete mcode runtime. Only the
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
