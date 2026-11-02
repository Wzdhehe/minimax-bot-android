#!/usr/bin/env bash
set -euo pipefail

# Point WEBUI_SOURCE to a checkout of the pinned WebUI fork
# (mcode-webui/minimax-code at the commit in upstream-runtime-pin.mjs).
# This is a host-only contract smoke test: it does not run the full runtime.
: "${WEBUI_SOURCE:?Set WEBUI_SOURCE to the pinned WebUI checkout}"
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"

# Absolute paths only: the checker resolves the bundle metafile against the
# current directory, and a relative WEBUI_SOURCE would be read differently by
# the checker and by esbuild.
WEBUI_SOURCE="$(cd -- "$WEBUI_SOURCE" 2>/dev/null && pwd || true)"
if [ -z "$WEBUI_SOURCE" ] || [ ! -d "$WEBUI_SOURCE" ]; then
  echo "WEBUI_SOURCE is not a directory: ${WEBUI_SOURCE:-<unset>}" >&2
  exit 1
fi

# The pin must agree everywhere: the pin file, docs/UPSTREAM-RUNTIME.md, the
# checkout under test and the bytes that enter the bundle. Fail before
# bundling when it does not.
node "$script_dir/upstream-runtime-pin-check.mjs" --source "$WEBUI_SOURCE"

# esbuild: an explicit override wins, then the checkout's own install, then PATH.
# A checkout that has an esbuild which cannot run is an error, not a reason to
# silently bundle with a different esbuild.
checkout_esbuild="$WEBUI_SOURCE/node_modules/.bin/esbuild"
if [ -n "${ESBUILD_BIN:-}" ]; then
  esbuild_bin="$ESBUILD_BIN"
elif [ -e "$checkout_esbuild" ]; then
  if [ ! -x "$checkout_esbuild" ]; then
    echo "the checkout's esbuild is not executable: $checkout_esbuild" >&2
    exit 1
  fi
  esbuild_bin="$checkout_esbuild"
else
  esbuild_bin="$(command -v esbuild || true)"
fi
if [ -z "$esbuild_bin" ] || [ ! -x "$esbuild_bin" ] || [ -d "$esbuild_bin" ]; then
  echo "esbuild not found or not executable: ${esbuild_bin:-<none>}" >&2
  echo "set ESBUILD_BIN, or install the checkout's dependencies" >&2
  exit 1
fi

# The esbuild that bundles the pinned upstream must be the version the pinned
# upstream itself asks for, so a stray binary cannot change what is built.
esbuild_version="$("$esbuild_bin" --version)"
if [ -f "$WEBUI_SOURCE/package.json" ]; then
  pinned_esbuild_version="$(node -p "require('$WEBUI_SOURCE/package.json').devDependencies?.esbuild ?? ''")"
  if [ -n "$pinned_esbuild_version" ] && [ "$pinned_esbuild_version" != "$esbuild_version" ]; then
    echo "esbuild version mismatch: using $esbuild_version, the pinned upstream requires $pinned_esbuild_version" >&2
    exit 1
  fi
fi

# The upstream contract paths come from the pin, so a pin bump cannot leave the
# bundle pointing at a file that moved.
alias_file="$(mktemp "${TMPDIR:-/tmp}/bot-gateway-aliases-XXXXXXXX.txt")"
bundle="$(mktemp "${TMPDIR:-/tmp}/bot-gateway-XXXXXXXX.mjs")"
metafile="$(mktemp "${TMPDIR:-/tmp}/bot-gateway-meta-XXXXXXXX.json")"
trap 'rm -f -- "$alias_file" "$bundle" "$metafile"' EXIT
node --input-type=module -e "
  const { pathToFileURL } = await import('node:url');
  const mod = await import(pathToFileURL(process.argv[1]).href);
  for (const arg of mod.upstreamRuntimeEsbuildAliases(process.argv[2])) console.log(arg);
" "$script_dir/upstream-runtime-pin.mjs" "$WEBUI_SOURCE" > "$alias_file"
alias_args=()
while IFS= read -r alias_arg; do
  [ -n "$alias_arg" ] && alias_args+=("$alias_arg")
done < "$alias_file"
if [ "${#alias_args[@]}" -eq 0 ]; then
  echo "the pinned contract aliases are empty; refusing to bundle without the upstream contract" >&2
  exit 1
fi

# Build from the script directory so the metafile paths resolve the same way
# for esbuild and for the checker.
cd "$script_dir"
"$esbuild_bin" "$script_dir/upstream-gateway-smoke.mjs" \
  --bundle --platform=node --format=esm \
  "${alias_args[@]}" \
  --metafile="$metafile" \
  "--outfile=$bundle"

# The bytes that entered the bundle must be the pinned bytes.
node "$script_dir/upstream-runtime-pin-check.mjs" --source "$WEBUI_SOURCE" --bundle-metafile "$metafile"

BOT_PLUGIN_ROOT="$script_dir/../vendor/minimax-bot" node "$bundle"
