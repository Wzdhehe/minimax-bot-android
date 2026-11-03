// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFile } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { BrokerError, createModelBrokerClient, startModelProxy } from './model-broker.mjs';
import { buildRuntimeEnv, createRuntimeDescriptor, writeRuntimeDescriptor } from './runtime-descriptor.mjs';
import { startMockUpstream, startNativeBrokerDouble } from './native-broker-double.helper.mjs';

const run = promisify(execFile);
const KEY = 'sk-provider-key-value-9f2c';
const PIN = '97818b2890b4acae1c6bd1200a226ff506680628';

/** mock provider -> native broker double -> host proxy */
async function stack({ frames = [{ delta: 'a' }, { delta: 'b' }], status = 200 } = {}) {
  const upstream = await startMockUpstream({ frames, status });
  const native = await startNativeBrokerDouble({ key: KEY, upstream });
  const logs = [];
  const broker = createModelBrokerClient({ baseUrl: native.baseUrl, token: native.token });
  const proxy = await startModelProxy({ broker, token: 'd'.repeat(64), logger: (code) => logs.push(code) });
  return { upstream, native, broker, proxy, logs };
}

test('model events stream through the host proxy and never carry the provider key', async () => {
  const { upstream, native, proxy, logs } = await stack({ frames: [{ delta: 'hello' }, { delta: ' world' }] });
  try {
    const client = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: proxy.token });
    const events = [];
    await client.stream({
      providerId: 'provider-1',
      modelId: 'model-1',
      messages: [{ role: 'user', content: 'hi' }],
      onEvent: (event) => events.push(event),
    });
    assert.deepEqual(events, [{ delta: 'hello' }, { delta: ' world' }]);
    // The key was injected natively, upstream.
    assert.equal(upstream.state.requests[0].headers.authorization, `Bearer ${KEY}`);
    assert.ok(!JSON.stringify(events).includes(KEY));
    assert.ok(!JSON.stringify(logs).includes(KEY));
    assert.ok(!JSON.stringify(upstream.state.requests[0].body).includes(KEY));
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('cancelling the stream closes the upstream provider connection', async () => {
  const { upstream, native, proxy } = await stack({ frames: [{ delta: 'a' }, { delta: 'b' }, { delta: 'c' }] });
  try {
    const client = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: proxy.token });
    const controller = new AbortController();
    const events = [];
    await assert.rejects(() => client.stream({
      providerId: 'provider-1',
      modelId: 'model-1',
      messages: [{ role: 'user', content: 'hi' }],
      signal: controller.signal,
      onEvent: (event) => { events.push(event); if (events.length === 1) controller.abort(); },
    }), (error) => error instanceof BrokerError && error.code === 'broker_cancelled');
    // The provider connection is closed, not left dangling.
    await new Promise((done) => setTimeout(done, 100));
    assert.ok(upstream.state.closed + upstream.state.aborted > 0, 'the upstream provider connection was not closed');
    assert.equal(events.length, 1);
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('a rejected provider request surfaces a stable code without the key', async () => {
  const { upstream, native, proxy, logs } = await stack({ status: 401 });
  try {
    const client = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: proxy.token });
    await assert.rejects(
      () => client.stream({ providerId: 'provider-1', modelId: 'model-1', messages: [] }),
      (error) => {
        assert.ok(error instanceof BrokerError);
        assert.ok(!error.message.includes(KEY));
        assert.ok(!JSON.stringify(logs).includes(KEY));
        return true;
      },
    );
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('the proxy refuses a foreign token and a browser origin', async () => {
  const { upstream, native, proxy } = await stack();
  try {
    const wrong = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: 'e'.repeat(64) });
    await assert.rejects(
      () => wrong.stream({ providerId: 'p', modelId: 'm', messages: [] }),
      (error) => error instanceof BrokerError && error.code === 'broker_rejected',
    );
    const crossOrigin = await fetch(`${proxy.baseUrl}/v1/model/stream`, {
      method: 'POST',
      headers: { authorization: `Bearer ${proxy.token}`, origin: 'https://example.com', 'content-type': 'application/json' },
      body: JSON.stringify({ providerId: 'p', modelId: 'm', messages: [] }),
    });
    assert.equal(crossOrigin.status, 403);
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});

test('the runtime child process gets no key, no token and no descriptor on the command line', async () => {
  const { upstream, native, proxy } = await stack({ frames: [{ delta: 'ok' }] });
  const priv = mkdtempSync(join(tmpdir(), 'mbot-runtime-'));
  try {
    const descriptor = createRuntimeDescriptor({
      entryPath: join(priv, 'runtime', 'entry.mjs'),
      pinCommit: PIN,
      port: proxy.port,
      token: proxy.token,
    });
    const path = writeRuntimeDescriptor({ dir: priv, descriptor, allowedRoots: [priv] });
    const child = `
import { readFileSync } from 'node:fs';
const descriptor = JSON.parse(readFileSync(process.argv[1], 'utf8'));
const response = await fetch('http://127.0.0.1:' + descriptor.port + '/v1/model/stream', {
  method: 'POST',
  headers: { authorization: 'Bearer ' + descriptor.token, 'content-type': 'application/json' },
  body: JSON.stringify({ providerId: 'provider-1', modelId: 'model-1', messages: [{ role: 'user', content: 'hi' }] }),
});
const body = await response.text();
console.log(JSON.stringify({ body, env: process.env, argv: process.argv }));
`;
    const env = buildRuntimeEnv({ baseEnv: process.env, extra: { MINIMAX_API_KEY: KEY, NODE_EXTRA: 'ok' } });
    assert.ok(!JSON.stringify(env).includes(KEY), 'the host put the provider key into the runtime environment');
    const { stdout } = await run(process.execPath, ['--input-type=module', '-e', child, path], { env });
    const report = JSON.parse(stdout.trim().split('\n').pop());
    assert.ok(report.body.includes('ok'), 'the runtime did not receive the streamed answer');
    assert.ok(!stdout.includes(KEY), 'the provider key reached the runtime output');
    assert.ok(!JSON.stringify(report.env).includes(KEY), 'the provider key reached the runtime environment');
    assert.ok(!JSON.stringify(report.env).includes(proxy.token), 'the session token reached the runtime environment');
    assert.ok(!report.argv.includes(proxy.token), 'the session token reached the command line');
    assert.ok(!report.argv.includes(KEY), 'the provider key reached the command line');
    // The key is still injected natively, upstream.
    assert.equal(upstream.state.requests[0].headers.authorization, `Bearer ${KEY}`);
  } finally {
    await proxy.dispose();
    await native.dispose();
    await upstream.dispose();
  }
});
