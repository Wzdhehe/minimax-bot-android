// SPDX-License-Identifier: AGPL-3.0-only
import { createHash } from 'node:crypto';
import { basename, dirname, isAbsolute, join, parse, resolve } from 'node:path';
import { createBotFileLease } from './bot-file-lease.mjs';

const MAX_BYTES = 1024 * 1024;
const failure = (code) => Object.assign(new Error(code), { code });
const knownReadErrors = new Set(['runtime_data_denied', 'runtime_data_closed', 'runtime_data_unavailable', 'runtime_data_too_large', 'runtime_data_invalid', 'runtime_data_outcome_unknown', 'runtime_data_reload_required']);

/**
 * Restricted adapter for the built-in Bot Store only. `files` is a trusted,
 * already-bound native file capability, not a path- or directory-selector.
 * State, usage and per-chat documents map to flat, private capability names.
 * This does not adapt MemoryStore, uploads or the mcode runtime filesystem.
 */
export function createBotStorePersistence({ dataDir, files, lease } = {}) {
  if (typeof dataDir !== 'string' || !isAbsolute(dataDir) || resolve(dataDir) !== dataDir || dataDir === parse(dataDir).root || dataDir.includes('\0') ||
      (lease !== undefined && files !== undefined)) {
    throw new TypeError('A private Bot store capability and canonical virtual directory are required');
  }
  const fileLease = lease ?? createBotFileLease(files);
  if (['healthy', 'invalidate', 'read', 'replace', 'append'].some((method) => typeof fileLease?.[method] !== 'function')) {
    throw new TypeError('A private Bot store capability and canonical virtual directory are required');
  }
  const state = join(dataDir, 'state.json');
  const messages = join(dataDir, 'messages');
  const usage = join(dataDir, 'usage.jsonl');
  const requireHealthy = () => fileLease.healthy();
  function nameFor(path, kind) {
    if (kind === 'state-or-message' && path === state) return 'bot-state.json';
    if (kind === 'usage' && path === usage) return 'bot-usage.jsonl';
    if (kind === 'state-or-message' && typeof path === 'string' && dirname(path) === messages) {
      const name = basename(path);
      if (/^[A-Za-z0-9_-]{0,250}\.json$/.test(name) && join(messages, name) === path) {
        return `bot-chat-${createHash('sha256').update(name).digest('hex')}.json`;
      }
    }
    throw failure('runtime_data_invalid');
  }
  function encode(text) {
    const bytes = Buffer.from(text, 'utf8');
    if (bytes.length > MAX_BYTES) {
      // Store state may already have changed in memory before save() fails.
      fileLease.invalidate();
      throw failure('runtime_data_too_large');
    }
    return bytes;
  }
  async function readText(path, kind) {
    requireHealthy();
    const name = nameFor(path, kind);
    let bytes;
    try { bytes = await fileLease.read(name); }
    catch (error) {
      if (error?.code === 'runtime_data_not_found') throw failure('ENOENT');
      throw failure(knownReadErrors.has(error?.code) ? error.code : 'runtime_data_unavailable');
    }
    if (!(bytes instanceof Uint8Array) || bytes.byteLength > MAX_BYTES) throw failure('runtime_data_unavailable');
    try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
    catch { throw failure('runtime_data_invalid'); }
  }
  return Object.freeze({
    async init(path) {
      requireHealthy();
      if (path !== messages) throw failure('runtime_data_invalid');
    },
    async readJson(path, fallback) {
      let text;
      try { text = await readText(path, 'state-or-message'); }
      catch (error) { if (error?.code === 'ENOENT') return fallback; throw error; }
      try { return JSON.parse(text); } catch { throw failure('runtime_data_invalid'); }
    },
    async writeJsonAtomic(path, value) {
      requireHealthy();
      const name = nameFor(path, 'state-or-message');
      let text;
      try { text = JSON.stringify(value, null, 2); }
      catch { fileLease.invalidate(); throw failure('runtime_data_invalid'); }
      if (typeof text !== 'string') { fileLease.invalidate(); throw failure('runtime_data_invalid'); }
      await fileLease.replace(name, encode(text));
    },
    async appendUsage(path, line) {
      requireHealthy();
      const name = nameFor(path, 'usage');
      if (typeof line !== 'string') throw failure('runtime_data_invalid');
      await fileLease.append(name, encode(line));
    },
    readUsage(path) { return readText(path, 'usage'); },
  });
}
