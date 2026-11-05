// SPDX-License-Identifier: AGPL-3.0-only
// Host-only fixture. Sockets are preconnected by the parent and inherited as
// FDs; neither this process nor the Bot service opens a listening socket.
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { createBotFdTransport } from './bot-fd-transport.mjs';
import { startBotFdIngress } from './bot-fd-ingress.mjs';
import { startBotService } from './service.mjs';

const [root, portText, countText] = process.argv.slice(2);
const count = Number(countText);
const port = Number(portText);
assert.ok(root && Number.isInteger(port) && port > 0 && port <= 65535 && Number.isInteger(count) && count > 0 && count < 64);
await mkdir(join(root, 'workspace'), { recursive: true });
const transport = createBotFdTransport();
const service = await startBotService({ port, dataDir: join(root, 'data'), filesDir: join(root, 'workspace'),
  authToken: 'a'.repeat(64), fdTransport: transport, signal: new AbortController().signal,
  logger: { info() {}, warn() {}, error() {} },
});
let nextFd = 3, waiting;
const grants = [];
let closeRequested;
const closing = new Promise((resolve) => { closeRequested = resolve; });
const commands = createInterface({ input: process.stdin });
commands.on('line', (line) => {
  if (line === 'grant' && nextFd < 3 + count) {
    const fd = nextFd++;
    if (waiting) { const resolve = waiting; waiting = undefined; resolve(fd); }
    else grants.push(fd);
  } else if (line === 'close') closeRequested();
});
let checkedDuplicate = false;
const ingress = startBotFdIngress({
  next: () => grants.length ? grants.shift() : new Promise((resolve) => { waiting = resolve; }),
  transport: { accept(fd) {
    transport.accept(fd);
    if (!checkedDuplicate) {
      checkedDuplicate = true;
      assert.throws(() => transport.accept(fd), (error) => error.code === 'bot_transport_invalid');
    }
  } },
});
process.stdout.write('ready\n');
await closing;
await ingress.close();
commands.close();
await service.dispose();
process.stdout.write('closed\n');
