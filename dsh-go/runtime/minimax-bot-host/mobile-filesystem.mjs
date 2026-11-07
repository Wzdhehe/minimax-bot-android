// SPDX-License-Identifier: AGPL-3.0-only
// One isolated execution unit. POSIX compatibility is provided by memfs;
// durable state is an atomic Main-owned manifest plus bounded content blobs. This is
// not the security boundary: Android UID and the native capability enforce it.
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { posix as path } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Volume, createFsFromVolume } from 'memfs';
import os from 'node:os';
import { createHash } from 'node:crypto';

const STATE = 'runtime-fs-state.json';
const MAX = 1024 * 1024;
const CHUNK = 256 * 1024;
const MAX_ENTRIES = 4096;
const MAX_DATA = 64 * MAX;
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const blobName = (hash) => `fsblob-${hash}.bin`;
const fault = (code) => Object.assign(new Error(code), { code });
const within = (value, root) => value === root || value.startsWith(root + '/');
const mutations = new Set(['appendFile', 'chmod', 'chown', 'copyFile', 'cp', 'fchmod', 'fchown', 'fdatasync', 'fsync',
  'ftruncate', 'futimes', 'lchmod', 'lchown', 'link', 'lutimes', 'mkdir', 'mkdtemp', 'rename', 'rm', 'rmdir', 'symlink',
  'truncate', 'unlink', 'utimes', 'write', 'writeFile', 'writev']);
const descriptorOps = new Set(['close', 'fchmod', 'fchown', 'fdatasync', 'fstat', 'fsync', 'ftruncate', 'futimes', 'read', 'readv', 'write', 'writev']);
let installed = false;

export function installMobileFilesystem({ files, assets = [], code = null, metadata = null,
  runtimeRoot = '/mbot-data/runtime', workspaceRoot = '/work', codeRoot = '/mbot-code', temporaryRoot = '/mbot-tmp' } = {}) {
  if (installed || typeof files?.readSync !== 'function' || typeof files?.replaceSync !== 'function' || (metadata !== null && typeof metadata !== 'function')) throw fault('mobile_fs_invalid');
  const roots = [runtimeRoot, workspaceRoot, codeRoot, temporaryRoot];
  if (roots.some((root) => typeof root !== 'string' || root === '/' || path.resolve(root) !== root || root.includes('\0')) ||
      roots.some((root, index) => roots.some((other, otherIndex) => index !== otherIndex && within(root, other)))) throw fault('mobile_fs_invalid');
  // proper-lockfile locks the data directory by making a sibling .lock
  // directory. It is process-local coordination, never a physical Android
  // pathname or durable user data. Do not pre-create the lock itself.
  const runtimeLock = runtimeRoot + '.lock';
  const admitted = [...roots, runtimeLock];
  let active = true, poisoned = false;
  const volume = new Volume(), memory = createFsFromVolume(volume), descriptors = new Map(), restores = [];
  const contentCache = new Map(), acknowledged = new Set();
  let previousChunks = new Set();
  const persistent = [runtimeRoot, workspaceRoot];
  const uploadsRoot = path.join(workspaceRoot, '.mbot/uploads');
  const sqlite = path.join(runtimeRoot, 'v2/sqlite');
  const protectedSqlite = (value) => within(value, sqlite);
  const sqlPaths = [0, 1, 2, 3, 4].map((slot) => slot === 4 ? sqlite : path.join(sqlite,
    ['runtime-state.sqlite', 'runtime-state.sqlite-wal', 'runtime-state.sqlite-shm', 'runtime-state.sqlite-journal'][slot]));
  const unhandled = Symbol('ordinary_virtual_filesystem');
  const check = () => {
    if (!active) throw fault('mobile_fs_closed');
    if (poisoned) throw fault('mobile_fs_outcome_unknown');
  };
  for (const root of roots) volume.mkdirSync(root, { recursive: true, mode: 0o700 });
  volume.mkdirSync(path.join(runtimeRoot, 'home'), { recursive: true, mode: 0o700 });
  function relative(value) {
    return typeof value === 'string' && value.length <= 2048 && value !== '' && value !== '.' &&
      !value.includes('\0') && !value.includes('\\') && !path.isAbsolute(value) && path.normalize(value) === value &&
      value.split('/').every((part) => part !== '..' && part !== '.');
  }
  function base64(value) {
    if (typeof value !== 'string' || value.length > MAX * 2) throw fault('mobile_fs_state_invalid');
    const bytes = Buffer.from(value, 'base64');
    if (bytes.toString('base64') !== value) throw fault('mobile_fs_state_invalid');
    return bytes;
  }
  if (!Array.isArray(assets) || assets.length > 2048) throw fault('mobile_fs_assets_invalid');
  let assetBytes = 0;
  const assetsByPath = new Map(), assetsByHash = new Map();
  for (const asset of assets) {
    if (!relative(asset?.path) || !asset.path.startsWith('assets/') || assetsByPath.has(asset.path) || typeof asset.base64 !== 'string') throw fault('mobile_fs_assets_invalid');
    const bytes = Buffer.from(asset.base64, 'base64');
    const hash = digest(bytes);
    if (bytes.toString('base64') !== asset.base64 || bytes.length !== asset.bytes || hash !== asset.sha256 || (assetBytes += bytes.length) > MAX_DATA) throw fault('mobile_fs_assets_invalid');
    const row = { path: asset.path, bytes, sha256: hash };
    assetsByPath.set(asset.path, row); assetsByHash.set(hash, row);
  }
  function fileContents(entry) {
    if (Object.hasOwn(entry, 'data')) return base64(entry.data);
    if (Object.hasOwn(entry, 'asset')) {
      const asset = assetsByPath.get(entry.asset);
      if (!asset || asset.sha256 !== entry.sha256) throw Error();
      return asset.bytes;
    }
    if (!Number.isSafeInteger(entry.bytes) || entry.bytes < 1 || entry.bytes > 16 * MAX || !Array.isArray(entry.chunks) || entry.chunks.length !== Math.ceil(entry.bytes / CHUNK)) throw Error();
    return Buffer.concat(entry.chunks.map((hash, index) => {
      if (typeof hash !== 'string' || !/^[0-9a-f]{64}$/u.test(hash)) throw Error();
      const bytes = Buffer.from(files.readSync(blobName(hash)));
      if (bytes.length !== Math.min(CHUNK, entry.bytes - index * CHUNK) || digest(bytes) !== hash) throw Error();
      acknowledged.add(hash); previousChunks.add(hash);
      return bytes;
    }), entry.bytes);
  }
  let previous;
  try { previous = files.readSync(STATE); }
  catch (error) { if (error?.code !== 'runtime_data_not_found') throw fault('mobile_fs_state_unavailable'); }
  if (previous !== undefined) {
    try {
      const bytes = Buffer.from(previous);
      if (bytes.length > MAX) throw Error();
      const state = JSON.parse(bytes.toString('utf8'));
      if (![1, 2].includes(state.version) || !Array.isArray(state.entries) || state.entries.length > MAX_ENTRIES) throw Error();
      const names = new Set(), directories = [], links = [];
      let restoredBytes = 0;
      for (const entry of state.entries) {
        if (!entry || ![0, 1].includes(entry.root) || !relative(entry.path) || !['file', 'directory', 'symlink', 'hardlink'].includes(entry.kind) ||
            !Number.isInteger(entry.mode) || entry.mode < 0 || entry.mode > 0o777 ||
            !Number.isSafeInteger(entry.mtimeMs) || entry.mtimeMs < 0 || entry.mtimeMs > 8640000000000000) throw Error();
        const name = path.join(persistent[entry.root], entry.path);
        if (names.has(name) || protectedSqlite(name)) throw Error();
        names.add(name);
        if (entry.kind === 'directory') { volume.mkdirSync(name, { recursive: true, mode: 0o700 }); directories.push(entry); }
        else if (entry.kind === 'file') {
          const bytes = fileContents(entry);
          if ((restoredBytes += bytes.length) > MAX_DATA) throw Error();
          volume.writeFileSync(name, bytes, { mode: 0o600, flag: 'wx' });
        }
        else if (entry.kind === 'hardlink') {
          if (![0, 1].includes(entry.targetRoot) || !relative(entry.targetPath)) throw Error();
          links.push({ name, target: path.join(persistent[entry.targetRoot], entry.targetPath) });
          continue;
        }
        else {
          if (typeof entry.target !== 'string' || entry.target.length > 2048 || entry.target.includes('\0')) throw Error();
          volume.symlinkSync(entry.target, name);
        }
        if (entry.kind === 'file') { volume.chmodSync(name, entry.mode); volume.utimesSync(name, entry.mtimeMs / 1000, entry.mtimeMs / 1000); }
      }
      for (const link of links) {
        if (protectedSqlite(link.target) || !volume.lstatSync(link.target).isFile()) throw Error();
        volume.linkSync(link.target, link.name);
      }
      for (const entry of directories.reverse()) {
        const name = path.join(persistent[entry.root], entry.path);
        volume.chmodSync(name, entry.mode);
        volume.utimesSync(name, entry.mtimeMs / 1000, entry.mtimeMs / 1000);
      }
    } catch { throw fault('mobile_fs_state_invalid'); }
  }
  volume.mkdirSync(path.join(sqlite, 'backups'), { recursive: true, mode: 0o700 });
  for (const asset of assetsByPath.values()) {
    const name = path.join(codeRoot, asset.path);
    volume.mkdirSync(path.dirname(name), { recursive: true, mode: 0o700 });
    volume.writeFileSync(name, asset.bytes, { mode: 0o600 });
    volume.chmodSync(name, 0o444);
  }
  if (code !== null) {
    const name = path.join(codeRoot, 'runtime.mjs');
    volume.writeFileSync(name, Buffer.from(code), { mode: 0o600 });
    volume.chmodSync(name, 0o444);
  }

  function persist() {
    check();
    try {
      const entries = [], inodes = new Map(), referenced = new Set();
      let dataBytes = 0;
      function walk(directory, root) {
        for (const item of volume.readdirSync(directory, { withFileTypes: true })) {
          const name = path.join(directory, item.name);
          if (protectedSqlite(name)) continue;
          if (entries.length >= MAX_ENTRIES) throw Error();
          const info = volume.lstatSync(name);
          const row = { root, path: path.relative(persistent[root], name), mode: info.mode & 0o777, mtimeMs: Math.trunc(info.mtimeMs) };
          if (info.isDirectory()) { entries.push({ ...row, kind: 'directory' }); walk(name, root); }
          else if (info.isFile()) {
            const original = inodes.get(info.ino);
            if (original) entries.push({ ...row, kind: 'hardlink', targetRoot: original.root, targetPath: original.path });
            else {
              if (info.size > 16 * MAX || (dataBytes += info.size) > MAX_DATA) throw Error();
              let saved = contentCache.get(info.ino)?.saved;
              if (!saved) {
                const bytes = volume.readFileSync(name), asset = assetsByHash.get(digest(bytes));
                if (!bytes.length) saved = { data: '' };
                else if (asset) saved = { asset: asset.path, sha256: asset.sha256 };
                else if (bytes.length <= 4096) saved = { data: bytes.toString('base64') };
                else {
                  const chunks = [];
                  for (let offset = 0; offset < bytes.length; offset += CHUNK) {
                    const chunk = bytes.subarray(offset, offset + CHUNK), hash = digest(chunk);
                    if (!acknowledged.has(hash)) {
                      if (files.replaceSync(blobName(hash), chunk) !== 'published') throw Error();
                      acknowledged.add(hash);
                    }
                    chunks.push(hash);
                  }
                  saved = { bytes: bytes.length, chunks };
                }
                contentCache.set(info.ino, { name, saved });
              }
              saved.chunks?.forEach((hash) => referenced.add(hash));
              inodes.set(info.ino, row); entries.push({ ...row, kind: 'file', ...saved });
            }
          }
          else if (info.isSymbolicLink()) entries.push({ ...row, kind: 'symlink', target: volume.readlinkSync(name) });
          else throw Error();
        }
      }
      persistent.forEach((root, index) => walk(root, index));
      const bytes = Buffer.from(JSON.stringify({ version: 2, entries }));
      if (bytes.length > MAX || files.replaceSync(STATE, bytes) !== 'published') throw Error();
      // The manifest replacement is the sole commit point. Only now may a
      // former blob be retired. Failed optional cleanup cannot undo success.
      for (const hash of previousChunks) if (!referenced.has(hash) && typeof files.removeSync === 'function') {
        // An uncertain cleanup might have deleted it. Any future reuse must
        // republish these bytes, never trust this session's former receipt.
        acknowledged.delete(hash);
        try { files.removeSync(blobName(hash)); } catch {}
      }
      previousChunks = referenced;
    } catch { poisoned = true; throw fault('mobile_fs_outcome_unknown'); }
  }
  function invalidate(selected) {
    const changedInodes = new Set(selected.paths.map((name) => {
      try { return volume.lstatSync(name).ino; } catch { return null; }
    }));
    for (const [inode, cached] of contentCache) if (changedInodes.has(inode) || selected.paths.some((name) => within(cached.name, name))) contentCache.delete(inode);
  }
  function filename(value) {
    if (value instanceof URL) value = fileURLToPath(value);
    if (Buffer.isBuffer(value)) value = value.toString();
    return typeof value === 'string' && !value.includes('\0') ? path.resolve(value) : null;
  }
  function sqliteResult(operation, args) {
    const name = filename(args[0]), slot = sqlPaths.indexOf(name);
    if (slot < 0) return unhandled;
    if (typeof metadata !== 'function') throw fault('runtime_sqlite_metadata_unavailable');
    const value = metadata(slot), directory = slot === 4, mode = directory ? 0o40700 : 0o100600;
    if (!value || typeof value.present !== 'boolean' || value.mode !== mode || !Number.isSafeInteger(value.size) || value.size < 0) throw fault('runtime_sqlite_metadata_unavailable');
    if (operation === 'exists' || operation === 'existsSync') return value.present;
    if (!value.present) throw fault('ENOENT');
    if (operation === 'stat' || operation === 'lstat') {
      const base = volume.statSync(sqlite, args[1]);
      const integer = (n) => typeof base.mode === 'bigint' ? BigInt(n) : n;
      return Object.assign(base, { mode: integer(mode), size: integer(value.size), ino: integer(slot + 10),
        nlink: integer(1), blocks: integer(Math.ceil(value.size / 512)) });
    }
    if (operation === 'realpath') return name;
    if (operation === 'chmod' && args[1] === (directory ? 0o700 : 0o600)) return undefined;
    if (operation === 'mkdir' && directory && args[1]?.recursive === true) return undefined;
    if (operation === 'access' && !(Number(args[1] ?? 0) & 1)) return undefined;
    throw fault('EPERM'); // Actual bytes/opens/locks belong exclusively to the native SQLite VFS.
  }
  function info(operation, args) {
    if (descriptorOps.has(operation) || (typeof args[0] === 'number' && descriptors.has(args[0]))) {
      const selected = descriptors.get(args[0]);
      if (!selected) return null;
      const changed = mutations.has(operation);
      if (changed && selected.paths.some((name) => within(name, codeRoot) || protectedSqlite(name))) throw fault('EROFS');
      return { ...selected, changed };
    }
    const indices = operation === 'symlink' ? [1] : ['rename', 'copyFile', 'cp', 'link'].includes(operation) ? [0, 1] : [0];
    const paths = indices.map((index) => filename(args[index]));
    const selected = paths.filter((name) => name && admitted.some((root) => within(name, root)));
    if (!selected.length) {
      const fd = args[1]?.fd;
      return typeof fd === 'number' ? descriptors.get(fd) ?? null : null;
    }
    if (selected.length !== paths.length) throw fault('EXDEV');
    const resolved = selected.map((name) => {
      try { return volume.realpathSync(name); }
      catch { try { return path.join(volume.realpathSync(path.dirname(name)), path.basename(name)); } catch { return name; } }
    });
    // Official HTTP upload paths may never be redirected by a workspace
    // symlink into private runtime configuration or another namespace.
    if (selected.some((name, index) => within(name, uploadsRoot) && !within(resolved[index], uploadsRoot))) throw fault('EPERM');
    const changed = operation === 'open' ? (typeof args[1] === 'string' ? /^[wa]|\+/u.test(args[1]) : Boolean(args[1] & (1 | 2 | 64 | 512 | 1024))) : mutations.has(operation) || operation === 'createWriteStream';
    const targets = operation === 'copyFile' || operation === 'cp' ? resolved.slice(1) : resolved;
    if (changed && targets.some((name) => within(name, codeRoot))) throw fault('EROFS');
    if (changed && targets.some((name) => protectedSqlite(name))) {
      const directories = [sqlite, path.join(sqlite, 'backups')];
      if (!targets.every((name) => directories.includes(name)) || !['mkdir', 'chmod', 'utimes'].includes(operation) ||
          (operation === 'chmod' && args[1] !== 0o700)) throw fault('EPERM');
    }
    if (['rename', 'rm', 'rmdir', 'unlink'].includes(operation) && targets.some((name) => within(sqlite, name))) throw fault('EPERM');
    if (operation === 'link' && !volume.lstatSync(paths[0]).isFile()) throw fault('ENOTSUP');
    return { paths: resolved, changed, durable: targets.some((name) => persistent.some((root) => within(name, root))) };
  }
  function finish(operation, args, selected, result) {
    if (operation === 'open' && typeof result === 'number') descriptors.set(result, selected);
    if (operation === 'close') descriptors.delete(args[0]);
    if (selected.changed && selected.durable) { invalidate(selected); persist(); }
    return result;
  }
  function argumentsFor(operation, args) {
    const result = [...args];
    if (typeof args[0] === 'number') return result;
    const indices = operation === 'symlink' ? [1] : ['rename', 'copyFile', 'cp', 'link'].includes(operation) ? [0, 1] : [0];
    for (const index of indices) {
      const name = filename(result[index]);
      if (name !== null) result[index] = name;
    }
    return result;
  }
  function stream(value, selected) {
    value.prependListener('open', (fd) => descriptors.set(fd, selected));
    value.once('close', () => { if (typeof value.fd === 'number') descriptors.delete(value.fd); });
    if (selected.changed && selected.durable) {
      const original = value._final?.bind(value);
      value._final = (done) => {
        const commit = (error) => { if (error) return done(error); try { invalidate(selected); persist(); done(); } catch (failure) { done(failure); } };
        if (original) original(commit); else commit();
      };
    }
    return value;
  }
  function handle(value, selected) {
    descriptors.set(value.fd, selected);
    return new Proxy(value, { get(target, key) {
      const method = Reflect.get(target, key, target);
      if (typeof method !== 'function') return method;
      return (...args) => {
        check();
        if (mutations.has(key) && selected.paths.some((name) => within(name, codeRoot) || protectedSqlite(name))) throw fault('EROFS');
        if (key === 'createReadStream' || key === 'createWriteStream') return stream(method.apply(target, args), { ...selected, changed: key === 'createWriteStream' });
        return Promise.resolve(method.apply(target, args)).then((result) => {
          check();
          if (key === 'close') descriptors.delete(value.fd);
          if (mutations.has(key) && selected.durable) { invalidate(selected); persist(); }
          return result;
        });
      };
    } });
  }
  function patch(target, name, replacement) {
    const original = target[name];
    target[name] = replacement;
    restores.push(() => { target[name] = original; });
  }
  try {
    // Legacy services consult the shared config getter even when the v2
    // factory received an explicit dataDir. Keep both generations on the
    // same admitted logical root; never alias a second physical database.
    process.env.MINIMAX_DATA_DIR = runtimeRoot;
    process.env.MAVIS_DATA_DIR = runtimeRoot;
    patch(process, 'cwd', () => workspaceRoot);
    patch(os, 'homedir', () => path.join(runtimeRoot, 'home'));
    patch(os, 'tmpdir', () => temporaryRoot);
    for (const name of Object.keys(fs)) {
      if (typeof fs[name] !== 'function' || name[0] !== name[0].toLowerCase() || !name.match(/^[a-z]/u)) continue;
      const raw = fs[name], original = raw.bind(fs), sync = name.endsWith('Sync'), operation = sync ? name.slice(0, -4) : name;
      const wrapped = (...args) => {
        const projected = sqliteResult(sync ? operation : name, args);
        if (projected !== unhandled) {
          if (sync) return projected;
          const callback = args[args.length - 1];
          if (typeof callback !== 'function') throw fault('EINVAL');
          queueMicrotask(() => operation === 'exists' ? callback(projected) : callback(null, projected));
          return;
        }
        const selected = info(operation, args);
        if (!selected) return original(...args);
        check();
        if (typeof memory[name] !== 'function') throw fault('ENOTSUP');
        const routed = argumentsFor(operation, args);
        if (operation === 'createReadStream' || operation === 'createWriteStream') return stream(memory[name](...routed), selected);
        if (sync) return finish(operation, args, selected, memory[name](...routed));
        const callback = args[args.length - 1];
        if (typeof callback !== 'function' || operation === 'watch' || operation === 'watchFile' || operation === 'unwatchFile' || operation === 'exists') return memory[name](...routed);
        const values = routed.slice(0, -1);
        return memory[name](...values, (error, ...result) => {
          if (error) return callback(error);
          try { check(); finish(operation, args, selected, result[0]); }
          catch (failure) { callback(failure); return; }
          callback(null, ...result);
        });
      };
      if (typeof raw.native === 'function') wrapped.native = wrapped;
      patch(fs, name, wrapped);
    }
    const promises = fs.promises;
    for (const name of Object.keys(promises)) {
      if (typeof promises[name] !== 'function') continue;
      const original = promises[name].bind(promises);
      patch(promises, name, (...args) => {
        const projected = sqliteResult(name, args);
        if (projected !== unhandled) return Promise.resolve(projected);
        const selected = info(name, args);
        if (!selected) return original(...args);
        check();
        if (typeof memory.promises[name] !== 'function') return Promise.reject(fault('ENOTSUP'));
        return memory.promises[name](...argumentsFor(name, args)).then((result) => {
          check();
          if (name === 'open') { finish(name, args, selected, result.fd); return handle(result, selected); }
          return finish(name, args, selected, result);
        });
      });
    }
    syncBuiltinESMExports(); installed = true;
  } catch (error) { for (const restore of restores.reverse()) restore(); syncBuiltinESMExports(); throw error; }
  const botFiles = Object.freeze({ dataDir: path.join(workspaceRoot, '.mbot'), root: workspaceRoot, maxUploadBytes: 16 * MAX,
    async resolve(value) {
      check();
      if (typeof value !== 'string' || value.includes('\0') || value.length > 2048) throw fault('EPERM');
      const name = path.resolve(workspaceRoot, value);
      if (!within(name, workspaceRoot)) throw fault('EPERM');
      const resolved = volume.realpathSync(name);
      if (!within(resolved, workspaceRoot)) throw fault('EPERM');
      return resolved;
    },
  });
  return Object.freeze({ runtimeRoot, workspaceRoot, codeRoot, temporaryRoot, botFiles,
    close() { if (!active) return; active = false; /* Never rebind a retired execution unit. */ },
  });
}
