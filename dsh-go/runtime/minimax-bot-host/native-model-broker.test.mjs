// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import { spawn, execFile } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { promisify } from 'node:util';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { BrokerError, createModelBrokerClient, startModelProxy } from './model-broker.mjs';
import { buildRuntimeEnv, createRuntimeDescriptor, writeRuntimeDescriptor } from './runtime-descriptor.mjs';
import { startBotService } from './service.mjs';

const classpath = process.env.MBOT_NATIVE_CLASSPATH;
const exec = promisify(execFile);
const options = { skip: !classpath && 'Run tooling/test-native-model-broker.sh with Kotlin/JVM dependencies', timeout: 30_000 };

async function stack(t) {
  const root = await mkdtemp(join(tmpdir(), 'mbot-native-boundary-'));
  const workspace = join(root, 'workspace');
  await mkdir(workspace);
  await writeFile(join(workspace, 'project.txt'), 'Agent-readable project content');
  const native = spawn(process.env.MBOT_JAVA ?? 'java', ['-cp', classpath,
    'io.github.ccch1mneyyy.dsh_app.model.NativeBrokerFixture', root], {
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const lines = createInterface({ input: native.stdout });
  const queue = [];
  const waiting = [];
  let output = '', stderr = '';
  native.stdout.on('data', (data) => { output += data; });
  native.stderr.on('data', (data) => { stderr += data; });
  lines.on('line', (line) => waiting.length ? waiting.shift()(line) : queue.push(line));
  const next = () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('native_fixture_timeout')), 10_000);
    waiting.push((line) => { clearTimeout(timer); resolve(line); });
  });
  let proxy;
  t.after(async () => {
    await proxy?.dispose();
    native.stdin.end('stop\n');
    const timer = setTimeout(() => native.kill('SIGKILL'), 5000);
    if (native.exitCode === null) await once(native, 'exit');
    clearTimeout(timer);
    lines.close();
    await rm(root, { recursive: true, force: true });
  });
  const descriptor = JSON.parse(await next());
  const logs = [];
  proxy = await startModelProxy({
    broker: createModelBrokerClient(descriptor), token: 'e'.repeat(64), logger: (code) => logs.push(code),
  });
  const client = createModelBrokerClient({ baseUrl: proxy.baseUrl, token: proxy.token });
  return { root, workspace, descriptor, proxy, client, logs,
    async report() {
      await writeFile(join(root, 'native-output.txt'), output + stderr);
      await writeFile(join(root, 'host-logs.json'), JSON.stringify(logs));
      native.stdin.write('report\n');
      return JSON.parse(await next());
    },
  };
}

const request = (modelId = 'stream') => ({ providerId: 'native-provider', modelId,
  messages: [{ role: 'user', content: 'Hi' }] });

test('tool-shaped SDK requests cross the real native boundary with native authentication and no config overrides', options, async (t) => {
  const s = await stack(t);
  const payload = { providerId: 'native-provider', modelId: 'tools', request: {
    model: 'tools', stream: true,
    messages: [{ role: 'tool', tool_call_id: 'c1', content: 'found' }],
    tools: [{ type: 'function', function: { name: 'lookup', parameters: { type: 'object' } } }],
    tool_choice: 'auto', temperature: 0.2,
  } };
  const events = [];
  await s.client.stream({ ...payload, onEvent: (event) => events.push(event) });
  const tool = events[0].choices[0].delta.tool_calls[0];
  assert.equal(tool.function.name, 'lookup');
  assert.deepEqual(JSON.parse(tool.function.arguments), { path: 'notes/b' });
  assert.equal(events[1].choices[0].finish_reason, 'tool_calls');
  await assert.rejects(s.client.stream({ ...payload, request: { ...payload.request, headers: { Authorization: 'caller-override' } } }),
    { code: 'broker_rejected' });
  await writeFile(join(s.workspace, 'tool-result.json'), JSON.stringify(events));
  const report = await s.report();
  assert.equal(report.keyInjected, true);
  assert.equal(report.artifactsSafe, true);
  assert.equal(report.logsSafe, true);
});

test('real Kotlin broker streams through the Node proxy and blocks runtime credential/config overrides', options, async (t) => {
  const s = await stack(t);
  const events = [];
  await s.client.stream({ ...request(), onEvent: (event) => events.push(event) });
  assert.deepEqual(events, [{ delta: 'hello' }, { delta: ' world' }]);
  const response = await fetch(`${s.descriptor.baseUrl}/v1/model/stream`, {
    method: 'POST', headers: { authorization: `Bearer ${s.descriptor.token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ ...request(), apiKey: 'runtime-override', baseUrl: 'https://elsewhere.invalid' }),
  });
  assert.equal(response.status, 400);
  assert.equal(await response.text(), 'broker_request_invalid');
  const unauthorized = await fetch(`${s.descriptor.baseUrl}/v1/model/stream`, {
    method: 'POST', headers: { authorization: 'Bearer wrong', 'content-type': 'application/json' }, body: JSON.stringify(request()),
  });
  assert.equal(unauthorized.status, 401);
  await unauthorized.body.cancel();
  const origin = await fetch(`${s.descriptor.baseUrl}/v1/model/stream`, {
    method: 'POST', headers: { authorization: `Bearer ${s.descriptor.token}`, origin: 'https://elsewhere.invalid', 'content-type': 'application/json' }, body: JSON.stringify(request()),
  });
  assert.equal(origin.status, 403);
  await origin.body.cancel();
});

test('cancelling the runtime stream closes the real native OkHttp provider call', options, async (t) => {
  const s = await stack(t);
  const controller = new AbortController();
  const events = [];
  await assert.rejects(s.client.stream({ ...request('cancel'), signal: controller.signal,
    onEvent(event) { events.push(event); controller.abort(); },
  }), (error) => error instanceof BrokerError && error.code === 'broker_cancelled');
  assert.equal(events.length, 1);
  let report;
  for (let attempt = 0; attempt < 20; attempt++) {
    report = await s.report();
    if (report.cancelClosed) break;
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  assert.equal(report.cancelClosed, true, 'native upstream read did not return after cancellation');
});

test('native-held key never reaches runtime descriptor, environment, logs or Agent-readable files', options, async (t) => {
  const s = await stack(t);
  const descriptor = createRuntimeDescriptor({ entryPath: join(s.root, 'entry.mjs'),
    pinCommit: '97818b2890b4acae1c6bd1200a226ff506680628', port: s.proxy.port, token: s.proxy.token,
    // The non-secret provider catalog travels with the descriptor; the key and
    // the real endpoint never do.
    catalog: { providers: [{ providerId: 'native-provider', name: 'Native', apiFormat: 'openai-completions',
      enabled: true, models: ['stream'] }], defaultProviderId: 'native-provider', defaultModelId: 'stream' } });
  const path = writeRuntimeDescriptor({ dir: s.root, descriptor, allowedRoots: [s.root], workspaceRoot: s.workspace });
  const env = buildRuntimeEnv({ baseEnv: process.env });
  const source = `
import { readFile, readdir } from 'node:fs/promises';
const descriptor = JSON.parse(await readFile(process.argv[1], 'utf8'));
const files = {};
for (const name of await readdir(process.argv[2])) files[name] = await readFile(process.argv[2] + '/' + name, 'utf8');
const response = await fetch('http://127.0.0.1:' + descriptor.port + '/v1/model/stream', {
 method:'POST', headers:{authorization:'Bearer '+descriptor.token,'content-type':'application/json'},
 body:JSON.stringify({providerId:'native-provider',modelId:'stream',messages:[{role:'user',content:'Hi'}]})});
console.log(JSON.stringify({descriptor, env:process.env, argv:process.argv, files, stream:await response.text()}));
`;
  const { stdout, stderr } = await exec(process.execPath, ['--input-type=module', '-e', source, path, s.workspace], { env });
  const result = JSON.parse(stdout);
  assert.equal(result.files['project.txt'], 'Agent-readable project content');
  assert.match(result.stream, /hello/);
  // The fake key was generated inside the native JVM, not sent to this runner.
  // Ask that process to inspect every runtime-visible artifact for its key.
  await writeFile(join(s.workspace, 'runtime-observed.json'), stdout);
  await writeFile(join(s.root, 'runtime-stderr.txt'), stderr);
  await writeFile(join(s.root, 'runtime-env.json'), JSON.stringify(env));
  const report = await s.report();
  assert.equal(report.keyInjected, true, 'fixture did not authenticate an upstream request');
  assert.equal(report.artifactsSafe, true, 'native key reached an observable artifact');
  assert.equal(report.logsSafe, true);
  assert.equal(report.nativeSurfacesSafe, true);
  const persistedText = await readFile(path, 'utf8');
  const persistedDescriptor = JSON.parse(persistedText);
  assert.deepEqual(Object.keys(persistedDescriptor).sort(), ['broker', 'catalog', 'entryPath', 'pinCommit', 'port', 'token']);
  assert.equal(persistedDescriptor.broker, null, 'this FD/proxy fixture must not acquire a broker session');
  // The catalog is a projection: provider, api format and models only.
  assert.deepEqual(persistedDescriptor.catalog.providers, [{ providerId: 'native-provider', name: 'Native',
    apiFormat: 'openai-completions', enabled: true, models: ['stream'] }]);
  assert.equal(persistedDescriptor.catalog.defaultModelId, 'stream');
  assert.ok(!persistedText.includes('apiKey'), 'the descriptor must not carry an api key field');
  assert.ok(!persistedText.includes('baseUrl'), 'the descriptor must not carry the real upstream endpoint');
});

test('native blocks echoed/escaped keys, provider bodies, redirects and secret-bearing exceptions', options, async (t) => {
  const s = await stack(t);
  for (const [model, code] of [['echo', 'provider_sensitive_response'], ['escaped', 'provider_sensitive_response'],
    ['error', 'provider_rejected'], ['redirect', 'provider_rejected'], ['throw', 'broker_rejected']]) {
    const events = [];
    await assert.rejects(s.client.stream({ ...request(model), onEvent: (event) => events.push(event) }),
      (error) => error instanceof BrokerError && error.code === code);
    assert.deepEqual(events, [], 'sensitive response event escaped native');
  }
  await writeFile(join(s.root, 'error-observations.json'), JSON.stringify(s.logs));
  const report = await s.report();
  assert.equal(report.artifactsSafe, true);
  assert.equal(report.logsSafe, true);
});

test('authenticated settings probe uses the real native stream without a Node credential resolver', options, async (t) => {
  const s = await stack(t);
  const probe = createServer();
  await new Promise((resolve, reject) => { probe.once('error', reject); probe.listen(0, '127.0.0.1', resolve); });
  const port = probe.address().port;
  await new Promise((resolve, reject) => probe.close((error) => error ? reject(error) : resolve()));
  const service = await startBotService({ port, dataDir: join(s.root, 'bot-data'), filesDir: s.root,
    modelBroker: createModelBrokerClient(s.descriptor), signal: new AbortController().signal,
    logger: { info() {}, warn() {}, error() {} },
  });
  try {
    const response = await fetch(`${service.descriptor.baseUrl}/api/providers/test`, {
      method: 'POST', headers: { ...service.descriptor.headers, 'content-type': 'application/json' },
      body: JSON.stringify({ providerId: 'native-provider', modelId: 'stream' }),
    });
    const result = await response.json();
    assert.equal(response.status, 200);
    assert.deepEqual(result, { ok: true });
    await writeFile(join(s.root, 'settings-probe-result.json'), JSON.stringify(result));
    const report = await s.report();
    assert.equal(report.keyInjected, true);
    assert.equal(report.artifactsSafe, true);
    assert.equal(report.logsSafe, true);
  } finally {
    await service.dispose();
  }
});

test('native rejects deeply nested request JSON and does not call a truncated provider stream finished', options, async (t) => {
  const s = await stack(t);
  const response = await fetch(`${s.descriptor.baseUrl}/v1/model/stream`, {
    method: 'POST', headers: { authorization: `Bearer ${s.descriptor.token}`, 'content-type': 'application/json' },
    body: '{"providerId":"native-provider","modelId":"stream","messages":' + '['.repeat(1000) + '0' + ']'.repeat(1000) + '}',
  });
  assert.equal(response.status, 400);
  assert.equal(await response.text(), 'broker_request_invalid');
  const events = [];
  await assert.rejects(s.client.stream({ ...request('truncated'), onEvent: (event) => events.push(event) }),
    (error) => error instanceof BrokerError && error.code === 'broker_invalid_response');
  assert.deepEqual(events, [{ delta: 'partial' }]);
  await s.client.stream(request()); // The native process remains alive.
});

test('real pinned pi-ai request contracts pass native body policy with native-only authentication', options, async () => {
  const contracts = new URL('../../tooling/model-sdk-probe/request-contracts.json', import.meta.url);
  const { stdout, stderr } = await exec(process.env.MBOT_JAVA ?? 'java', ['-cp', classpath,
    'io.github.ccch1mneyyy.dsh_app.model.NativePayloadContractFixture', fileURLToPath(contracts)], { timeout: 10_000 });
  assert.equal(stdout.trim(), 'accepted=4');
  assert.equal(stderr, '');
});
