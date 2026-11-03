#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
report_denials() {
  local test_status=$?
  trap - EXIT
  adb logcat -b all -d 2>/dev/null | python3 "$probe_dir/../android-broker-probe/report-denials.py" || true
  exit "$test_status"
}
trap report_denials EXIT
gradle --no-daemon -p "$probe_dir" connectedDebugAndroidTest
