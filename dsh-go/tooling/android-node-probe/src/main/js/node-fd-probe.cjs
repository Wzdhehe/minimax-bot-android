// SPDX-License-Identifier: AGPL-3.0-only
// The build embeds the exact production FD/SSE modules using data URLs.
// This script is packaged in the verification APK, never supplied by an Agent.
const modelModuleUrl = "__MBOT_FD_MODULE_URL__";
const nativeRouteModuleUrl = "__MBOT_NATIVE_ROUTE_MODULE_URL__";
const fs = require('node:fs');
let probePhase = 'bootstrap';
const sqliteWrapper = "__MBOT_SQLITE_WRAPPER__";

(async () => {
  const [normal, stalled, echo, result, privatePath, nativeLibraryDir, providerId, ...dataFdArgs] = process.argv.slice(1);
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
    if (dataFds.length === 5) {
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
        disk.exec('CREATE TABLE durable(text TEXT); INSERT INTO durable VALUES (\'你好\')');
        probePhase = 'vfs_verify';
        verify = new Database('state.db', { ...options, readonly: true, fileMustExist: true });
        const parallelRows = verify.prepare('SELECT text FROM durable').all();
        verify.close(); verify = null;
        probePhase = 'vfs_write';
        disk.transaction(() => { disk.prepare('INSERT INTO durable VALUES (?)').run('second'); })();
        persistent = { mode, parallelRows: parallelRows.map((row) => row.text), count: disk.prepare('SELECT count(*) AS n FROM durable').get().n };
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
    version: process.versions.node, modules: process.versions.modules,
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
    const cancelledId = require('node:crypto').randomUUID();
    binding.cancel(cancelledId);
    try { fs.closeSync(await binding.open(cancelledId, request)); report.dynamicCancelled = false; }
    catch (error) { report.dynamicCancelled = error.message === 'broker_unavailable'; }
  }
  // Owner inspects the bounded result privately. No console/log response dump.
  fs.writeSync(Number(result), JSON.stringify(report));
  fs.closeSync(Number(result));
})().catch((error) => {
  const codes = new Set(['SQLITE_CANTOPEN', 'SQLITE_IOERR', 'SQLITE_IOERR_SHMOPEN', 'SQLITE_BUSY', 'SQLITE_READONLY',
    'SQLITE_ERROR', 'SQLITE_PERM', 'SQLITE_MISUSE', 'SQLITE_NOTFOUND', 'fd_vfs_unavailable', 'fd_vfs_input_invalid']);
  const code = codes.has(error?.code) ? error.code : codes.has(error?.message) ? error.message : 'unknown';
  try {
    const resultFd = Number(process.argv.slice(1)[3]);
    fs.writeSync(resultFd, JSON.stringify({ failedPhase: probePhase, failureCode: code }));
    fs.closeSync(resultFd);
  } catch {}
  process.exitCode = 1;
});
