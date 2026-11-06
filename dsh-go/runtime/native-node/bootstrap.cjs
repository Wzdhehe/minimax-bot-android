// SPDX-License-Identifier: AGPL-3.0-only
// Fixed signed-APK entry. No commands, code URLs, endpoints or model keys are
// accepted from a Bot request. Main hands over only the descriptor and DB FDs.
const fs = require('node:fs');
const { registerHooks } = require('node:module');
const product = process._linkedBinding('mbot_product_bridge');
let phase = 'bootstrap';
(async () => {
  if (typeof Intl === 'undefined' || typeof registerHooks !== 'function') throw Error('runtime_features_unavailable');
  const [descriptor, ownerUid, libraries, ...databaseFds] = process.argv.slice(1);
  let stopping = false;
  const stopped = product.waitForStop().then(() => { stopping = true; });
  phase = 'assets';
  const source = Buffer.from(product.read('runtime'));
  const filesystemSource = Buffer.from(product.read('filesystem'));
  const resources = JSON.parse(Buffer.from(product.read('resources')).toString('utf8'));
  const runtimeUrl = 'file:///mbot-code/runtime.mjs', filesystemUrl = 'file:///mbot-code/filesystem.mjs';
  const modules = new Map([[runtimeUrl, source], [filesystemUrl, filesystemSource]]);
  registerHooks({
    resolve(specifier, context, next) { return modules.has(specifier) ? { url: specifier, shortCircuit: true } : next(specifier, context); },
    load(url, context, next) { return modules.has(url) ? { source: modules.get(url), format: 'module', shortCircuit: true } : next(url, context); },
  });
  const files = process._linkedBinding('mbot_files_bridge');
  const addon = { exports: {} }, wrapper = { exports: {} };
  process.dlopen(addon, libraries + '/libmbot_sqlite_probe.so');
  new Function('require', 'module', 'exports', Buffer.from(product.read('sqlite-wrapper')).toString('utf8'))(require, wrapper, wrapper.exports);
  phase = 'filesystem';
  const { installMobileFilesystem } = await import(filesystemUrl);
  const filesystem = installMobileFilesystem({ files, assets: resources, code: source, metadata: (slot) => addon.exports.fdVfsMetadata(slot) });
  phase = 'runtime_import';
  const { startMobileBotRuntime } = await import(runtimeUrl);
  phase = 'runtime_start';
  if (stopping) { filesystem.close(); return; }
  const connections = process._linkedBinding('mbot_bot_ingress');
  const handle = await startMobileBotRuntime({
    descriptorFd: Number(descriptor), ownerUid: Number(ownerUid), Database: wrapper.exports, addon: addon.exports,
    dataFds: databaseFds.map(Number), filesystem, files,
    models: process._linkedBinding('mbot_model_bridge'), snapshots: process._linkedBinding('mbot_snapshots_bridge'),
    connections: { next: () => connections.next(), cancel: () => connections.cancel() },
    onReady() { if (stopping || !product.ready()) throw Error('runtime_stopped'); },
  });
  phase = 'runtime_loop';
  try { await Promise.race([stopped, handle.done]); }
  finally { await handle.close(); }
})().then(() => {
  // This UID owns exactly one VM. SDK background timers must not retain a
  // retired execution after the real runtime's close promise has completed.
  process.exit(0);
}).catch(() => {
  // Do not log the raw error/stack: provider responses and user content may
  // appear in arbitrary dependency errors. Main gets only a finite phase.
  try { product.failed(phase); } catch {}
  process.exit(1);
});
