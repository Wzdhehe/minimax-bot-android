#!/usr/bin/env bash
set -euo pipefail

# Point WEBUI_SOURCE to a checkout of the pinned WebUI fork
# (mcode-webui/minimax-code at the commit in upstream-runtime-pin.mjs).
# This is a host-only contract smoke test: it does not run the full runtime.
: "${WEBUI_SOURCE:?Set WEBUI_SOURCE to the pinned WebUI checkout}"
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"

# The pin must agree everywhere: the pin file, docs/UPSTREAM-RUNTIME.md and the
# checkout under test. Fail before bundling when it does not.
node "$script_dir/upstream-runtime-pin-check.mjs" --source "$WEBUI_SOURCE"

# esbuild: an explicit override wins, then the checkout's own install, then PATH.
if [ -n "${ESBUILD_BIN:-}" ]; then
  esbuild_bin="$ESBUILD_BIN"
elif [ -x "$WEBUI_SOURCE/node_modules/.bin/esbuild" ]; then
  esbuild_bin="$WEBUI_SOURCE/node_modules/.bin/esbuild"
else
  esbuild_bin="$(command -v esbuild || true)"
fi
if [ -z "$esbuild_bin" ] || [ ! -x "$esbuild_bin" ]; then
  echo "esbuild not found: set ESBUILD_BIN, or install the checkout's dependencies" >&2
  exit 1
fi

# The upstream contract paths come from the pin, so a pin bump cannot leave the
# bundle pointing at a file that moved.
mapfile -t alias_args < <(node --input-type=module -e "
  const { pathToFileURL } = await import('node:url');
  const mod = await import(pathToFileURL(process.argv[1]).href);
  for (const arg of mod.upstreamRuntimeEsbuildAliases(process.argv[2])) console.log(arg);
" "$script_dir/upstream-runtime-pin.mjs" "$WEBUI_SOURCE")

bundle="$(mktemp "${TMPDIR:-/tmp}/bot-gateway-XXXXXXXX.mjs")"
trap 'rm -f -- "$bundle"' EXIT
"$esbuild_bin" "$script_dir/upstream-gateway-smoke.mjs" \
  --bundle --platform=node --format=esm \
  "${alias_args[@]}" \
  "--outfile=$bundle"
BOT_PLUGIN_ROOT="$script_dir/../vendor/minimax-bot" node "$bundle"
