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

Google is intentionally absent: pinned pi-ai `google.ts` does not use
`options.fetch`. Its wire-format SSE conversion is tested by the core host
suite, but injecting this adapter into the real Google provider requires a
separate integration. Anthropic beta/affinity headers are also not forwarded;
advanced header-dependent provider behavior still needs native policy.
