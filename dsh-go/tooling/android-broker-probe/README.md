<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Native broker Android verification

This is an **instrumentation target**, not another product app. It has no
launcher UI and does not start Node, Flutter, shell commands or an Agent.
Gradle copies the selected production Kotlin files and tests into `build/` at
build time. Never maintain a fork of their implementation here.

It verifies Android compilation and packages the device tests without the
product's unrelated Flutter/NDK/legacy engine build dependencies. Passing this
build does not mean the product APK builds, the runtime starts, or UID/Keystore
checks have run on a device.

Use JDK 21, Gradle 9.3.1, SDK platform 36 and Build Tools 36. Set `ANDROID_HOME`
or an untracked `local.properties` with `sdk.dir` to your actual SDK directory.

```bash
gradle --no-daemon -p dsh-go/tooling/android-broker-probe \
  testDebugUnitTest assembleDebug assembleDebugAndroidTest
# Only with a real, authorized Android device/emulator connected:
gradle --no-daemon -p dsh-go/tooling/android-broker-probe connectedDebugAndroidTest
```

The separate application ID ends with `.brokerprobe`, so these tests cannot
overwrite the product's saved provider configuration. Provider tests also use
unique preference names and randomly generated test-only keys. No keys,
signing files, local SDK paths, APKs or build caches belong in Git.

Validated on the development host: AGP 9.1.0 / Gradle 9.3.1 / Temurin 21.0.13,
platform 36 / Build Tools 36.0.0. `testDebugUnitTest` ran 27 tests with zero
failures/errors/skips; both debug and instrumentation APKs assembled. The
compiled manifest retains private services and `isolatedProcess=true`.
The separate `android-broker-device.yml` workflow runs ten instrumentation
tests on Android 26 and 35 emulators. The additional private isolated probe
service exists only in this verification APK, not in the product manifest. This adds device execution to the compilation
workflow; configuration alone does not prove a pass. Android 35 baseline run `41600336727` passed its three original tests.
The first six-test direct TCP FD matrix passed on API35 but failed two FD
transfers on API26. The initial eight-test Unix relay matrix found receiver-side `setopt` refusal.
Socket options now stay in the owner; the receiver only reads/closes the FD.
The ten-test matrix also checks cancelled startup and concurrent lease/task
behavior. Its results are pending. Physical-device
checks remain unverified. See the latest root
`docs/handoff/T-RuntimeBroker.md` for the recorded limits.
