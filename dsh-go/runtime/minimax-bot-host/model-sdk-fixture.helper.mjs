// SPDX-License-Identifier: AGPL-3.0-only
// Test-only inherited Unix FD fixture. No credential is sent to the child.
import { createServer, connect } from 'node:net';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { buildRuntimeEnv } from './runtime-descriptor.mjs';

const helper = fileURLToPath(new URL('./model-sdk-reader.helper.mjs', import.meta.url));
export async function runSdkFdProbe({ format = 'openai-completions', events = [], mode = '', quiet = false,
  truncated = false, sdkRoot = '' } = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'mbot-sdk-fd-'));
  let peer, owner, child, release;
  let closed;
  const peerClosed = new Promise((resolve) => { closed = resolve; });
  const server = createServer((socket) => {
    peer = socket;
    socket.on('error', () => {});
    socket.once('close', closed);
    socket.resume();
    release = () => {
      if (quiet) return;
      socket.end('HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nX-Mbot-Broker-Protocol: 1\r\nConnection: close\r\n\r\n' +
        events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('') + (truncated ? '' : 'data: [DONE]\n\n'));
    };
  });
  server.listen(join(directory, 'model.sock'));
  await once(server, 'listening');
  const watchdog = setTimeout(() => { child?.kill('SIGKILL'); peer?.destroy(); owner?.destroy(); }, 7000);
  try {
    owner = connect(join(directory, 'model.sock')); owner.on('error', () => {});
    await once(owner, 'connect'); owner.pause();
    child = spawn(process.execPath, [helper, format, mode, sdkRoot], {
      stdio: ['ignore', 'pipe', 'pipe', owner], env: buildRuntimeEnv(process.env),
    });
    const done = once(child, 'exit');
    let stdout = '', stderr = '';
    child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
    child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
    await once(child, 'spawn');
    owner.destroy(); release();
    const [code] = await done;
    assert.equal(code, 0, stderr);
    assert.equal(stderr, '');
    await peerClosed;
    return JSON.parse(stdout);
  } finally {
    clearTimeout(watchdog); child?.kill(); owner?.destroy(); peer?.destroy();
    await new Promise((resolve) => server.close(resolve));
    rmSync(directory, { recursive: true, force: true });
  }
}
