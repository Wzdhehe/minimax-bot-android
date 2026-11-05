// SPDX-License-Identifier: AGPL-3.0-only
// The build embeds the exact production FD/SSE modules using data URLs.
// This script is packaged in the verification APK, never supplied by an Agent.
const modelModuleUrl = "__MBOT_FD_MODULE_URL__";
const nativeRouteModuleUrl = "__MBOT_NATIVE_ROUTE_MODULE_URL__";
const nativeBindingModuleUrl = "__MBOT_NATIVE_BINDING_MODULE_URL__";
const descriptorModuleUrl = "__MBOT_DESCRIPTOR_MODULE_URL__";
const botTransportModuleUrl = "__MBOT_BOT_FD_TRANSPORT_MODULE_URL__";
const botIngressModuleUrl = "__MBOT_BOT_FD_INGRESS_MODULE_URL__";
const botStoreModuleUrl = "__MBOT_BOT_STORE_MODULE_URL__";
const botMemoryModuleUrl = "__MBOT_BOT_MEMORY_MODULE_URL__";
const botPersistenceModuleUrl = "__MBOT_BOT_PERSISTENCE_MODULE_URL__";
const fs = require('node:fs');
let probePhase = 'bootstrap';
const sqliteWrapper = "__MBOT_SQLITE_WRAPPER__";

(async () => {
  const [normal, stalled, echo, result, privatePath, nativeLibraryDir, providerId, descriptorFd, descriptorOwnerUid, ...dataFdArgs] = process.argv.slice(1);
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
  let sqlite = null;
  let persistent = null;
  const dataFds = dataFdArgs.map(Number);
  if (nativeLibraryDir && !sqliteWrapper.startsWith('__MBOT_SQLITE_')) {
    const wrapper = { exports: {} };
    new Function('require', 'module', 'exports', sqliteWrapper)(require, wrapper, wrapper.exports);
    const addon = { exports: {} };
    probePhase = 'sqlite_load';
    process.dlopen(addon, nativeLibraryDir + '/libmbot_sqlite_probe.so');
    const Database = wrapper.exports;
    const options = { nativeBinding: addon.exports };
    probePhase = 'sqlite_memory';
    const db = new Database(':memory:', options);
    try {
      db.exec('CREATE TABLE notes(id INTEGER PRIMARY KEY, text TEXT)');
      const insert = db.prepare('INSERT INTO notes(text) VALUES (?)');
      db.transaction(() => { insert.run('你好'); insert.run('second'); })();
      try { db.transaction(() => { insert.run('rollback'); throw Error('rollback'); })(); } catch {}
      const rows = db.prepare('SELECT text FROM notes ORDER BY id').all();
      const snapshot = db.serialize();
      const restored = new Database(snapshot, options);
      try {
        sqlite = { version: db.prepare('SELECT sqlite_version() AS version').get().version,
          rows: rows.map((row) => row.text), restoredCount: restored.prepare('SELECT count(*) AS n FROM notes').get().n,
          journalMode: db.pragma('journal_mode', { simple: true }),
          jsonValue: db.prepare("SELECT json_extract('{\"v\":7}', '$.v') AS v").get().v };
      } finally { restored.close(); }
    } finally { db.close(); }
    if (dataFds.length === 4) {
      probePhase = 'vfs_register';
      addon.exports.setFdVfs(...dataFds);
      // Remove input aliases before SQLite creates locks; registry owns dup FDs.
      dataFds.forEach((fd) => fs.closeSync(fd)); dataFds.length = 0;
      probePhase = 'vfs_open';
      const disk = new Database('state.db', options);
      let verify;
      try {
        probePhase = 'vfs_wal';
        const mode = disk.pragma('journal_mode=WAL', { simple: true });
        disk.pragma('temp_store=MEMORY');
        disk.exec('CREATE TABLE IF NOT EXISTS durable(text TEXT)');
        const initialRows = disk.prepare('SELECT text FROM durable').all().map((row) => row.text);
        if (initialRows.length === 0) disk.prepare('INSERT INTO durable VALUES (?)').run('你好');
        probePhase = 'vfs_verify';
        verify = new Database('state.db', { ...options, readonly: true, fileMustExist: true });
        const parallelRows = verify.prepare('SELECT text FROM durable').all();
        verify.close(); verify = null;
        probePhase = 'vfs_write';
        if (initialRows.length === 0) disk.transaction(() => { disk.prepare('INSERT INTO durable VALUES (?)').run('second'); })();
        persistent = { mode, initialRows, parallelRows: parallelRows.map((row) => row.text), count: disk.prepare('SELECT count(*) AS n FROM durable').get().n };
      } finally { verify?.close(); disk.close(); }
      probePhase = 'vfs_reopen';
      const reopened = new Database('state.db', options);
      try {
        persistent.reopened = reopened.prepare('SELECT text FROM durable').all().map((row) => row.text);
        persistent.integrity = reopened.pragma('integrity_check', { simple: true });
      } finally { reopened.close(); }
    }
  }
  dataFds.forEach((fd) => fs.closeSync(fd));
  probePhase = 'model_streams';
  const report = { sqlite, persistent,
    pid: process.pid, version: process.versions.node, modules: process.versions.modules,
    uid: process.getuid(), privateDenied, intlAvailable: typeof Intl !== 'undefined',
    intlSmoke: typeof Intl === 'undefined' ? null : {
      locale: new Intl.DateTimeFormat('zh-CN').resolvedOptions().locale,
      timeZone: new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai' }).resolvedOptions().timeZone,
    },
    descriptor: { normal: Number(normal), stalled: Number(stalled), echo: Number(echo), providerId },
    environment: process.env,
    normal: await consume(normal, false),
    cancelled: await consume(stalled, true),
    echoed: await consume(echo, false),
  };
  if (providerId) {
    probePhase = 'descriptor_transfer';
    const { readRuntimeDescriptorFd } = await import(descriptorModuleUrl);
    const descriptorNumber = Number(descriptorFd);
    const guestDescriptor = readRuntimeDescriptorFd({ fd: descriptorNumber, expectedOwnerUid: Number(descriptorOwnerUid) });
    let descriptorClosed = false;
    try { fs.fstatSync(descriptorNumber); } catch (error) { descriptorClosed = error.code === 'EBADF'; }
    const row = guestDescriptor.catalog?.providers?.find((provider) => provider.providerId === providerId);
    report.descriptorTransfer = {
      pin: guestDescriptor.pinCommit === '97818b2890b4acae1c6bd1200a226ff506680628',
      provider: !!row && row.models.includes('normal') && row.apiFormat === 'openai-completions',
      generation: guestDescriptor.port === 43117,
      brokerAbsent: guestDescriptor.broker === null,
      closed: descriptorClosed,
    };
    probePhase = 'bot_http_fd';
    const { createBotFdTransport } = await import(botTransportModuleUrl);
    const botTransport = createBotFdTransport();
    let complete;
    let connections = 0;
    let allValid = true;
    const received = new Promise((resolve) => { complete = resolve; });
    const server = require('node:http').createServer((request, response) => {
      const valid = request.method === 'GET' && request.url === '/fd-probe' && request.headers.host === '127.0.0.1:43117';
      response.writeHead(valid ? 200 : 400, { 'content-type': 'text/plain', connection: 'close' });
      response.once('finish', () => { allValid &&= valid; if (++connections === 2) complete(allValid); });
      response.end(valid ? 'fd-ok' : 'fd-invalid');
    });
    let timer, ingress, pump, pendingNative, nativeCalls = 0;
    try {
      await botTransport.listenServer(server);
      ingress = process._linkedBinding('mbot_bot_ingress');
      const { startBotFdIngress } = await import(botIngressModuleUrl);
      pump = startBotFdIngress({ transport: botTransport, next: () => {
        nativeCalls++;
        pendingNative = ingress.next();
        return pendingNative;
      } });
      // One private byte tells Main Node is running before it sends either FD.
      fs.writeSync(Number(result), 'R');
      const request = await Promise.race([received, pump.done.then(() => { throw Object.assign(new Error('bot_transport_unavailable'), { code: 'bot_transport_unavailable' }); }), new Promise((_, reject) => {
        timer = setTimeout(() => reject(Object.assign(new Error('bot_transport_timeout'), { code: 'bot_transport_timeout' })), 45_000);
      })]);
      report.botFdTransport = { request, noListener: !server.listening, connections };
    } finally {
      clearTimeout(timer);
      let cancelledWait = false;
      try {
        if (ingress) ingress.cancel();
        if (pump) await pump.close();
        if (pendingNative && nativeCalls >= 3) {
          let deadline;
          try {
            cancelledWait = await Promise.race([pendingNative.then(() => true, () => true), new Promise((resolve) => {
              deadline = setTimeout(() => resolve(false), 10_000);
            })]);
          } finally { clearTimeout(deadline); }
        }
      } finally {
        if (report.botFdTransport) report.botFdTransport.cancelledWait = cancelledWait;
        botTransport.close(); server.close(() => {});
      }
    }
    probePhase = 'model_dynamic';
    const binding = process._linkedBinding('mbot_model_bridge');
    const request = JSON.stringify({ providerId, modelId: 'normal', messages: [{ role: 'user', content: 'Hi dynamic' }] });
    const id = require('node:crypto').randomUUID();
    report.dynamic = await consume(await binding.open(id, request), false);
    const large = JSON.stringify({ providerId, modelId: 'normal', messages: [{ role: 'user', content: 'large-'.repeat(14_000) }] });
    report.dynamicLarge = await consume(await binding.open(require('node:crypto').randomUUID(), large), false);
    probePhase = 'sdk_route';
    const { createNativeModelRoute } = await import(nativeRouteModuleUrl);
    const route = createNativeModelRoute({ providerId, modelId: 'normal', apiFormat: 'openai-completions', binding });
    const sdkResponse = await route.fetch(route.requestUrl, { method: 'POST', body: JSON.stringify({ model: 'normal', stream: true,
      messages: [{ role: 'user', content: 'tool check' }], tools: [{ type: 'function', function: { name: 'lookup', parameters: { type: 'object' } } }] }) });
    const sdkBody = await sdkResponse.text();
    report.sdkRoute = { status: sdkResponse.status, toolCalls: sdkBody.includes('tool_calls'), done: sdkBody.includes('[DONE]') };
    probePhase = 'scoped_model_route';
    // Exercise the production per-turn dispatcher through the same linked
    // JNI/Binder/native broker capability as the fixed SDK route above.
    const { createNativeModelBinding } = await import(nativeBindingModuleUrl);
    const byForkProviderId = { 'custom_provider:probe': { nativeProviderId: providerId,
      apiFormat: 'openai-completions', models: ['normal'] } };
    const firstScope = createNativeModelBinding({ byForkProviderId, binding });
    const secondScope = createNativeModelBinding({ byForkProviderId, binding });
    firstScope.install(globalThis);
    secondScope.install(globalThis);
    try {
      const endpoint = 'https://models.mbot.invalid/v1/chat/completions';
      const request = { method: 'POST', body: JSON.stringify({ model: 'normal', stream: true,
        messages: [{ role: 'user', content: 'scoped tool check' }],
        tools: [{ type: 'function', function: { name: 'lookup', parameters: { type: 'object' } } }] }) };
      const denied = await fetch(endpoint, request);
      const run = (scope) => scope.runForModelKey('custom_provider:probe/normal', async () => {
        const response = await fetch(endpoint, request);
        const text = await response.text();
        return { status: response.status, toolCalls: text.includes('tool_calls'), done: text.includes('[DONE]') };
      });
      const first = await run(firstScope);
      firstScope.close();
      const second = await run(secondScope);
      report.scopedModelRoute = { unboundStatus: denied.status, first, second };
    } finally { firstScope.close(); secondScope.close(); }
    const cancelledId = require('node:crypto').randomUUID();
    binding.cancel(cancelledId);
    try { fs.closeSync(await binding.open(cancelledId, request)); report.dynamicCancelled = false; }
    catch (error) { report.dynamicCancelled = error.message === 'broker_unavailable'; }
    probePhase = 'file_commit';
    const files = process._linkedBinding('mbot_files_bridge');
    const storage = new Uint8Array(84 * 1024 + 16).fill(99);
    const body = storage.subarray(8, -8);
    for (let i = 0; i < body.length; i++) body[i] = i % 251;
    // Native must snapshot the exact view before scheduling asynchronous JNI.
    const snapshotPromise = files.publish('node-snapshot.bin', body);
    body.fill(0);
    const snapshot = await snapshotPromise;
    const initial = await files.publish('node-state.bin', body);
    for (let i = 0; i < body.length; i++) body[i] = 255 - i % 251;
    const replaced = await files.replace('node-state.bin', body);
    const empty = await files.publish('node-empty.bin', new Uint8Array());
    async function refused(name, value) {
      try { await files.publish(name, value); return false; }
      catch (error) { return error.code === 'runtime_data_invalid'; }
    }
    const detached = new ArrayBuffer(8);
    const detachedView = new Uint8Array(detached);
    structuredClone(detached, { transfer: [detached] });
    const rejected = [
      await refused('../outside', new Uint8Array()),
      await refused('never.bin', {}),
      await refused('never.bin', new Uint8Array(1024 * 1024 + 1)),
      await refused('never.bin', new Uint8Array(new SharedArrayBuffer(8))),
      await refused('never.bin', new Uint8Array(new ArrayBuffer(8, { maxByteLength: 16 }))),
      await refused('never.bin', detachedView),
    ];
    report.fileCommits = { snapshot, initial, replaced, empty, rejected };
    probePhase = 'file_read';
    const read = await files.read('node-state.bin');
    const original = await files.read('node-snapshot.bin');
    async function queryRefused(operation, name, code) {
      try { await files[operation](name); return false; }
      catch (error) { return error.code === code; }
    }
    report.fileReads = {
      size: await files.size('node-state.bin'), emptySize: await files.size('node-empty.bin'),
      binary: read.length === 84 * 1024 && read.every((value, i) => value === 255 - i % 251),
      original: original.length === 84 * 1024 && original.every((value, i) => value === i % 251),
      empty: (await files.read('node-empty.bin')).length === 0,
      missing: await queryRefused('read', 'missing.bin', 'runtime_data_not_found'),
      invalidPath: await queryRefused('read', '../outside', 'runtime_data_invalid'),
      invalidMetadata: await queryRefused('size', '../outside', 'runtime_data_invalid'),
    };
    probePhase = 'file_mutation';
    async function mutationRefused(operation, args, code) {
      try { await files[operation](...args); return false; }
      catch (error) { return error.code === code; }
    }
    const first = new Uint8Array([17, 0, 255]);
    const second = new Uint8Array([42]);
    const created = await files.publish('node-move.tmp', first);
    const moved = await files.move('node-move.tmp', 'node-moved.json');
    const movedBytes = await files.read('node-moved.json');
    const sourceMissing = await queryRefused('read', 'node-move.tmp', 'runtime_data_not_found');
    const createdConflict = await files.publish('node-conflict.tmp', second);
    const conflict = await files.move('node-conflict.tmp', 'node-moved.json');
    const preserved = (await files.read('node-conflict.tmp'))[0] === 42 &&
      (await files.read('node-moved.json'))[0] === 17;
    const renamed = await files.rename('node-conflict.tmp', 'node-moved.json');
    const replacement = await files.read('node-moved.json');
    const removed = await files.remove('node-moved.json');
    report.fileMutations = {
      created, createdConflict, moved, conflict, renamed, removed, sourceMissing, preserved,
      readback: movedBytes.length === first.length && movedBytes.every((value, i) => value === first[i]),
      replacement: replacement.length === 1 && replacement[0] === 42,
      missing: await mutationRefused('remove', ['node-moved.json'], 'runtime_data_not_found'),
      invalid: [
        await mutationRefused('move', ['node-state.bin', '../outside'], 'runtime_data_invalid'),
        await mutationRefused('rename', ['../outside', 'node-state.bin'], 'runtime_data_invalid'),
        await mutationRefused('move', ['node-state.bin', 'node-state.bin'], 'runtime_data_invalid'),
        await mutationRefused('move', ['node-state.bin', {}], 'runtime_data_invalid'),
        await mutationRefused('remove', ['node-state.bin', true], 'runtime_data_invalid'),
        await mutationRefused('remove', ['.native-publish-reserved.tmp'], 'runtime_data_invalid'),
      ],
    };
    probePhase = 'file_append';
    const appendCreated = await files.append('node-append.jsonl', new Uint8Array([0, 255]));
    const appendCommitted = await files.append('node-append.jsonl', new Uint8Array([17, 42]));
    const appendedBytes = await files.read('node-append.jsonl');
    const appendEmpty = await files.append('node-append-empty.bin', new Uint8Array());
    const appendEmptySize = await files.size('node-append-empty.bin');
    report.fileAppends = {
      created: appendCreated, committed: appendCommitted, empty: appendEmpty,
      binary: appendedBytes.length === 4 && appendedBytes.every((value, i) => value === [0, 255, 17, 42][i]),
      emptySize: appendEmptySize,
      overLimit: await mutationRefused('append', ['node-append.jsonl', new Uint8Array(1024 * 1024)], 'runtime_data_too_large'),
      unchanged: (await files.read('node-append.jsonl')).length === 4,
      invalid: await mutationRefused('append', ['../outside', new Uint8Array()], 'runtime_data_invalid'),
      removed: await files.remove('node-append.jsonl'), emptyRemoved: await files.remove('node-append-empty.bin'),
    };
    probePhase = 'bot_store';
    if (typeof Intl === 'undefined') {
      // The pinned Bot i18n module accesses Intl at import time. The release
      // candidate without ICU cannot exercise this branch.
      report.botStore = { available: false };
    } else {
      const { Store } = await import(botStoreModuleUrl);
      const { MemoryStore } = await import(botMemoryModuleUrl);
      const { createBotPersistence } = await import(botPersistenceModuleUrl);
      const root = '/mbot-bot-store'; // virtual name; no directory is opened.
      // The first process deliberately loses one journal append receipt AFTER
      // the native file operation succeeds. This exercises pending recovery
      // after Main retires the lease and cleans the temporary document.
      let loseAppendReceipt = false;
      const probeFiles = {
        read: (...args) => files.read(...args), size: (...args) => files.size(...args),
        replace: (...args) => files.replace(...args), publish: (...args) => files.publish(...args),
        rename: (...args) => files.rename(...args), remove: (...args) => files.remove(...args),
        async append(...args) {
          const result = await files.append(...args);
          if (loseAppendReceipt && result === 'published') { loseAppendReceipt = false; return 'outcome_unknown'; }
          return result;
        },
      };
      const { storePersistence, memoryFilesystem } = createBotPersistence({ dataDir: root, files: probeFiles });
      const store = new Store(root, storePersistence);
      const memory = new MemoryStore(root, memoryFilesystem);
      await store.init();
      await memory.init();
      const chief = store.state.bots.find((bot) => bot.isMain);
      if (!chief) throw Object.assign(new Error('runtime_data_invalid'), { code: 'runtime_data_invalid' });
      const chat = `bot:${chief.id}`;
      const before = (await store.getMessages(chat)).filter((message) => message.kind === 'node-probe').length;
      await store.appendMessage(chat, { role: 'user', text: 'node-store-probe', kind: 'node-probe' });
      if (!store.state.schedules.some((schedule) => schedule.id === 'schedule_node_probe')) {
        store.state.schedules.push({ id: 'schedule_node_probe', botId: chief.id, prompt: 'probe', enabled: true });
        await store.save();
      }
      const recorded = await store.recordUsageOnce({ ts: 123, turnId: 'node-store-turn', botId: chief.id });
      const usage = await store.readUsage(0);
      const priorMemory = (await memory.list('team')).find((entry) => entry.key === 'node.probe');
      const memoryBefore = priorMemory ? 1 : 0;
      await memory.remember('team', { text: memoryBefore ? 'node-memory-second' : 'node-memory-first', key: 'node.probe' });
      const memories = (await memory.list('team')).filter((entry) => entry.key === 'node.probe');
      const journal = memory.journalEntries(await memory.journalText());
      const memoryBackup = !memoryBefore || (await memoryFilesystem.readFile(`${root}/memory/backups/team.pre-storage-v2.md`, 'utf8')).includes('node-memory-first');
      report.botStore = {
        available: true, before, after: (await store.getMessages(chat)).filter((message) => message.kind === 'node-probe').length,
        schedules: store.state.schedules.filter((schedule) => schedule.id === 'schedule_node_probe').length, usage: usage.filter((row) => row.turnId === 'node-store-turn').length, recorded,
        memoryBefore, memoryAfter: memories.length, memoryJournal: journal.length, memoryBackup,
        memoryRecovery: memoryBefore ? priorMemory.text === 'node-memory-recovered' : false,
      };
      if (!memoryBefore) {
        loseAppendReceipt = true;
        try { await memory.remember('team', { text: 'node-memory-recovered', key: 'node.probe' }); }
        catch (error) {
          if (error?.code === 'memory_pending' && error?.details?.causeCode === 'runtime_data_outcome_unknown') report.botStore.memoryRecovery = true;
          else throw error;
        }
        if (!report.botStore.memoryRecovery) throw Object.assign(new Error('memory_pending'), { code: 'memory_pending' });
      }
    }
  }
  // Owner inspects the bounded result privately. No console/log response dump.
  fs.writeSync(Number(result), JSON.stringify(report));
  fs.closeSync(Number(result));
})().catch((error) => {
  const codes = new Set(['SQLITE_CANTOPEN', 'SQLITE_IOERR', 'SQLITE_IOERR_SHMOPEN', 'SQLITE_BUSY', 'SQLITE_READONLY',
    'SQLITE_ERROR', 'SQLITE_PERM', 'SQLITE_MISUSE', 'SQLITE_NOTFOUND', 'fd_vfs_unavailable', 'fd_vfs_input_invalid',
    'runtime_data_invalid', 'runtime_data_denied', 'runtime_data_closed', 'runtime_data_unavailable',
    'runtime_data_not_found', 'runtime_data_too_large', 'runtime_data_outcome_unknown', 'runtime_data_reload_required', 'descriptor_invalid', 'descriptor_permissions',
    'descriptor_not_a_regular_file', 'bot_transport_timeout', 'bot_transport_invalid', 'bot_transport_unavailable', 'memory_pending', 'memory_backup_failed']);
  const code = codes.has(error?.code) ? error.code : codes.has(error?.message) ? error.message :
    error?.message === 'Cannot open database because the directory does not exist' ? 'sqlite_parent_missing' : 'unknown';
  try {
    const resultFd = Number(process.argv.slice(1)[3]);
    fs.writeSync(resultFd, JSON.stringify({ failedPhase: probePhase, failureCode: code }));
    fs.closeSync(resultFd);
  } catch {}
  process.exitCode = 1;
});
