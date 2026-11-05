#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compiler="${KOTLINC:-$(command -v kotlinc || true)}"
java_bin="${MBOT_JAVA:-java}"
deps_dir="${MBOT_NATIVE_DEPS:?Set MBOT_NATIVE_DEPS to the directory of pinned test jars (see native-model-broker.yml)}"
if [[ ! -x "$compiler" ]]; then echo 'KOTLINC must name an executable Kotlin compiler' >&2; exit 2; fi
compiler_dir="$(cd -- "$(dirname -- "$compiler")/.." && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/mbot-native-model-tests.XXXXXX")"
trap 'rm -rf -- "$test_dir"' EXIT
classpath="$compiler_dir/lib/kotlin-stdlib.jar"
for jar in json-20240303 okhttp-4.12.0 okio-jvm-3.6.0 mockwebserver-4.12.0 junit-4.13.2 hamcrest-core-1.3; do
  if [[ ! -f "$deps_dir/$jar.jar" ]]; then echo "Missing test dependency: $jar.jar" >&2; exit 2; fi
  classpath="$classpath:$deps_dir/$jar.jar"
done
"$compiler" -J-Xmx512m -jvm-target 17 -cp "$classpath" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/model" \
  "$root_dir/app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/model" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/isolation/BrokerProtocol.kt" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/isolation/WorkspacePathPolicy.kt" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/isolation/WorkspaceBroker.kt" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/isolation/IsolationSessionGate.kt" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/isolation/GrantedConnectionQueue.kt" \
  "$root_dir/app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/isolation" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/bot/GuestProviderCatalog.kt" \
  "$root_dir/app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/bot/GuestProviderCatalogTest.kt" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeDescriptorPayload.kt" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/BotRuntimeEndpoint.kt" \
  "$root_dir/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/BotLoopbackBridge.kt" \
  "$root_dir/app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/bot/BotLoopbackBridgeTest.kt" \
  "$root_dir/app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/bot/BotRuntimeEndpointTest.kt" \
  "$root_dir/app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/RuntimeDescriptorPayloadTest.kt" \
  -d "$test_dir/tests.jar"
classpath="$test_dir/tests.jar:$classpath"
"$java_bin" -cp "$classpath" org.junit.runner.JUnitCore \
  io.github.ccch1mneyyy.dsh_app.model.NativeModelBrokerTest \
  io.github.ccch1mneyyy.dsh_app.model.NativeModelPayloadTest \
  io.github.ccch1mneyyy.dsh_app.isolation.WorkspacePathPolicyTest \
  io.github.ccch1mneyyy.dsh_app.isolation.WorkspaceBrokerTest \
  io.github.ccch1mneyyy.dsh_app.isolation.IsolationSessionGateTest \
  io.github.ccch1mneyyy.dsh_app.isolation.GrantedConnectionQueueTest \
  io.github.ccch1mneyyy.dsh_app.bot.GuestProviderCatalogTest \
  io.github.ccch1mneyyy.dsh_app.runtime.RuntimeDescriptorPayloadTest \
  io.github.ccch1mneyyy.dsh_app.bot.BotRuntimeEndpointTest \
  io.github.ccch1mneyyy.dsh_app.bot.BotLoopbackBridgeTest
MBOT_NATIVE_CLASSPATH="$classpath" MBOT_JAVA="$java_bin" node --test --test-reporter=tap \
  "$root_dir/runtime/minimax-bot-host/model-broker.test.mjs" \
  "$root_dir/runtime/minimax-bot-host/model-fd-client.test.mjs" \
  "$root_dir/runtime/minimax-bot-host/model-sdk-fetch.test.mjs" \
  "$root_dir/runtime/minimax-bot-host/model-fetch-scope.test.mjs" \
  "$root_dir/runtime/minimax-bot-host/native-model-route.test.mjs" \
  "$root_dir/runtime/minimax-bot-host/runtime-descriptor.test.mjs" \
  "$root_dir/runtime/minimax-bot-host/native-model-broker.test.mjs"
