<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Provider SDK / native FD component probe

This is a host-only test target, not an Android app or runtime dependency.
Dependencies match `mcode-webui/minimax-code@97818b2890b4acae1c6bd1200a226ff506680628`
`third_party/pi-mono/packages/ai/package.json`: OpenAI 6.26.0 and Anthropic 0.91.1.
The lockfile pins transitive inputs. Their upstream licenses remain in the
installed packages; this probe does not bundle them into the product.

```bash
npm ci --ignore-scripts --no-audit --no-fund
npm test
```

The parent passes a real connected Unix FD to a child Node process. The child
uses the production `createNativeModelFetch` adapter and the actual SDK stream
parser. Tests check tool events, stable bridge errors without SDK retry, and
early iterator exit closing the FD. Only placeholder credentials are supplied
to the SDK; they are discarded along with all SDK HTTP headers. There is no
HTTP fetch fallback. These tests do not start the complete pi-ai/mcode runtime,
exercise Android/JNI, use a real account or prove tool execution.

`requestUrl` is the exact URL that the SDK would construct, used only for a
routing check. For Anthropic the SDK appends `/v1/messages` to an unversioned
base; OpenAI appends `/chat/completions` or `/responses` to its versioned base.
The native process independently selects its configured provider endpoint and
credentials from IDs; no URL or headers are part of the FD-open payload.

The adapter's `openChannel(payload, {signal})` callback must recheck native
model authorization and return one owned preauthenticated model FD. Late FDs
are closed after cancellation, not leaked. The production Binder/JNI dynamic
request bridge is **not yet implemented**. Streams have a 64 KiB byte queue
plus at most one bounded native event, request bodies are limited to 1 MiB,
and cancellation/deadline closes the native FD even while consumers stall.
Errors before response creation return deterministic HTTP 400 so SDKs cannot
retry an unknown bridge outcome. Errors after streaming starts fail the body.

The unchanged pinned pi-ai providers can also be exercised:

```bash
node prepare-pi.mjs /path/to/clean/minimax-code-checkout
npm run test:pi
```

`prepare-pi.mjs` verifies the authoritative commit/tree/clean checkout and pin
contract hashes, checks SDK/esbuild versions against upstream manifests, then
bundles the four provider entry points without rewriting their source. Bundles
and input SHA256 records stay in ignored `.build/`. No upstream source or
third-party binary is added to Git. `request-contracts.json` contains only the
deterministic fake requests captured by `node capture-requests.mjs`; the pi-ai
tests compare real outgoing bodies to these, and the JVM fixture accepts them
through the actual native body policy with authentication remaining native.

Pinned Google ignores `options.fetch`. `createModelFetchScope()` supplies an
AsyncLocalStorage dispatcher for a trusted isolated launcher to install as the
process global fetch. Provider creation runs in a model-bound scope. Concurrent
providers inherit separate bindings; unbound calls get a fixed 400, with no
network fallback. Import/construction does not mutate globals. Only the test
child currently installs it; the production launcher is still missing. This is
routing, not authorization: the native bridge must recheck IDs and lifecycle.
All four real pi-ai providers translate tool calls and cancel a quiet FD in the
probe. That is host evidence, not an Android/full mcode runtime acceptance.

Anthropic beta/affinity headers are not forwarded; advanced header-dependent
provider behavior still needs native policy. Physical devices, real provider
APIs and complete runtime persistence remain unverified.

All SDK/pi-ai stream probes now use `createNativeModelRoute`: virtual SDK
URLs and a fixed nonsecret placeholder, IDs/JSON to a trusted binding, and
request-ID cancellation. The Android fixed-script target also exercises this
route over its real JNI binding; its device result must be checked separately.
The native upstream URL and session never belong in these SDK options.
