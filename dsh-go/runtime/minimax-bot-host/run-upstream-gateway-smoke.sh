#!/usr/bin/env bash
set -euo pipefail

# Point WEBUI_SOURCE to a dependency-installed checkout of mcode-webui/minimax-code.
# This is a host-only contract smoke test: it does not run the full runtime.
: "${WEBUI_SOURCE:?Set WEBUI_SOURCE to the pinned WebUI checkout}"
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
bundle="$(mktemp "${TMPDIR:-/tmp}/bot-gateway-XXXXXXXX.mjs")"
trap 'rm -f -- "$bundle"' EXIT
"$WEBUI_SOURCE/node_modules/.bin/esbuild" "$script_dir/upstream-gateway-smoke.mjs" \
  --bundle --platform=node --format=esm \
  "--alias:@mcode-test/agent-gateway=$WEBUI_SOURCE/packages/local-runtime-v2/src/service/miniapp/agent/gateway.ts" \
  "--alias:@mcode-test/process-local=$WEBUI_SOURCE/packages/local-runtime-v2/src/application/session/process-local-miniapp-agent-application.ts" \
  "--alias:@mavis/shared/miniapp-surface=$WEBUI_SOURCE/packages/shared/src/miniapp-surface.ts" \
  "--outfile=$bundle"
BOT_PLUGIN_ROOT="$script_dir/../vendor/minimax-bot" node "$bundle"
