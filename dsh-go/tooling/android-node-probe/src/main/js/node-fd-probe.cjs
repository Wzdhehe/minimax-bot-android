// SPDX-License-Identifier: AGPL-3.0-only
// The build embeds the exact production FD/SSE modules using data URLs.
// This script is packaged in the verification APK, never supplied by an Agent.
const modelModuleUrl = "__MBOT_FD_MODULE_URL__";
const fs = require('node:fs');

(async () => {
  const [normal, stalled, echo, result, privatePath] = process.argv.slice(1);
  const { streamNativeModelFd } = await import(modelModuleUrl);
  async function consume(fd, cancel) {
    const controller = new AbortController();
    const events = [];
    const timer = cancel ? setTimeout(() => controller.abort(), 100) : null;
    try {
      await streamNativeModelFd({ fd: Number(fd), signal: controller.signal, timeoutMs: 5000,
        onEvent(event) { events.push(event); },
      });
      return { code: 'done', events };
    } catch (error) {
      const allowed = ['broker_cancelled', 'provider_sensitive_response', 'broker_stream_failed',
        'broker_unavailable', 'broker_invalid_response', 'broker_rejected'];
      return { code: allowed.includes(error.code) ? error.code : 'probe_failed', events };
    } finally { clearTimeout(timer); }
  }
  let privateDenied = true;
  try { fs.readFileSync(privatePath); privateDenied = false; } catch {}
  const report = {
    version: process.versions.node, modules: process.versions.modules,
    uid: process.getuid(), privateDenied, intlAvailable: typeof Intl !== 'undefined',
    descriptor: { normal: Number(normal), stalled: Number(stalled), echo: Number(echo) },
    environment: process.env,
    normal: await consume(normal, false),
    cancelled: await consume(stalled, true),
    echoed: await consume(echo, false),
  };
  // Owner inspects the bounded result privately. No console/log response dump.
  fs.writeSync(Number(result), JSON.stringify(report));
  fs.closeSync(Number(result));
})().catch(() => { process.exitCode = 1; });
