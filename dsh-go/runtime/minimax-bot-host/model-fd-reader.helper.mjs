// SPDX-License-Identifier: AGPL-3.0-only
// Test subprocess: FD 3 is provided through spawn's descriptor table, not a
// path/token/environment variable. It never creates a network connection.
import { streamNativeModelFd } from './model-fd-client.mjs';

const mode = process.argv[2];
const controller = new AbortController();
const events = [];
const timer = mode === 'abort-header' ? setTimeout(() => controller.abort(), 30) : null;
let code = 'done';
try {
  await streamNativeModelFd({
    fd: 3,
    signal: controller.signal,
    timeoutMs: ['deadline', 'blocked-callback'].includes(mode) ? 75 : 3000,
    onEvent: async (event) => {
      events.push(event);
      if (mode === 'cancel-event') controller.abort();
      if (mode === 'callback-failure') throw new Error('must-not-appear-in-result');
      if (mode === 'slow-events') await new Promise((resolve) => setTimeout(resolve, 30));
      if (mode === 'blocked-callback') await new Promise(() => {});
    },
  });
} catch (error) { code = error.code ?? 'unexpected_failure'; }
finally { clearTimeout(timer); }
process.stdout.write(JSON.stringify({ code, events }) + '\n');
