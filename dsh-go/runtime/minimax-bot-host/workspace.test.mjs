// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, readFile, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createHostWorkspace } from './workspace.mjs';
import { startBotService } from './service.mjs';
import { Store } from '../vendor/minimax-bot/miniapp/node/store.mjs';

test('host filesystem enforces the private workspace before reads and writes', async (t) => {
  const filesDir = await mkdtemp(join(tmpdir(), 'host-workspace-'));
  t.after(() => rm(filesDir, { recursive: true, force: true }));
  const workspace = await createHostWorkspace(filesDir);
  await workspace.writeFile('notes/hello.txt', 'hello');
  assert.equal(await workspace.readFile('notes/hello.txt'), 'hello');
  await writeFile(join(filesDir, 'secret'), 'private');
  await mkdir(join(filesDir, 'workspace-evil'));
  await symlink(filesDir, join(workspace.root, 'escape'));
  for (const path of ['', '../secret', 'notes/../secret', '/sdcard/a', '/data/data/other/secret', join(workspace.root, 'notes/hello.txt'), '../workspace-evil/new', 'escape/secret']) {
    await assert.rejects(workspace.readFile(path));
    await assert.rejects(workspace.writeFile(path, 'bad'));
  }
  assert.equal(await readFile(join(filesDir, 'secret'), 'utf8'), 'private');
  await rename(workspace.root, join(filesDir, 'old-workspace'));
  await symlink(join(filesDir, 'workspace-evil'), workspace.root);
  await assert.rejects(workspace.writeFile('new', 'bad'));
  await assert.rejects(createHostWorkspace(filesDir));
  await assert.rejects(createHostWorkspace(undefined));
});

test('authenticated media serving cannot bypass the host workspace guard', async (t) => {
  const filesDir = await mkdtemp(join(tmpdir(), 'host-media-'));
  const dataDir = join(filesDir, 'bot-data');
  const store = new Store(dataDir);
  await store.init();
  const chat = `bot:${store.state.bots[0].id}`;
  const outside = join(filesDir, 'secret.txt');
  const sources = ['hello.txt', '../secret.txt', outside, '/sdcard/private.txt', 'escape/secret.txt'];
  await store.appendMessage(chat, { role: 'bot', status: 'done', text: sources.map((src) => `<media src="${src}" />`).join('\n') });
  await writeFile(outside, 'do not disclose');
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const port = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  const service = await startBotService({ port, filesDir, dataDir, signal: new AbortController().signal, logger: { info() {}, warn() {}, error() {} } });
  t.after(async () => { await service.dispose(); await rm(filesDir, { recursive: true, force: true }); });
  await service.workspace.writeFile('hello.txt', 'allowed');
  await symlink(filesDir, join(service.workspace.root, 'escape'));
  for (const src of sources) {
    const url = new URL('/api/media', service.descriptor.baseUrl);
    url.searchParams.set('chat', chat);
    url.searchParams.set('src', src);
    const response = await fetch(url, { headers: service.descriptor.headers });
    assert.equal(response.status, src === 'hello.txt' ? 200 : 404, src);
    assert.equal(await response.text(), src === 'hello.txt' ? 'allowed' : JSON.stringify({ error: 'not_found', message: 'File not found' }));
  }
});
