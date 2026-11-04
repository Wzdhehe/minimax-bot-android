// SPDX-License-Identifier: AGPL-3.0-only
// Host-only fixture. Sockets are preconnected by the parent and inherited as
// FDs; neither this process nor the Bot service opens a listening socket.
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { createBotFdTransport } from './bot-fd-transport.mjs';
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
for (let index = 0; index < count; index++) transport.accept(3 + index);
assert.throws(() => transport.accept(3), (error) => error.code === 'bot_transport_invalid');
process.stdout.write('ready\n');
await once(process.stdin, 'data');
await service.dispose();
process.stdout.write('closed\n');
