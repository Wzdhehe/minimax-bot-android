#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
report_denials() {
  local test_status=$?
  trap - EXIT
  # Preserve the test result. Filter on the runner, before anything is printed:
  # no response bodies, file names, tokens, full logcat or provider diagnostics.
  adb logcat -b all -d 2>/dev/null | python3 "$probe_dir/report-denials.py" || true
  exit "$test_status"
}
trap report_denials EXIT
gradle --no-daemon -p "$probe_dir" connectedDebugAndroidTest
