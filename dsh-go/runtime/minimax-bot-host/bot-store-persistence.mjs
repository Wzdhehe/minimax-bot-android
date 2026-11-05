// SPDX-License-Identifier: AGPL-3.0-only
import { createHash } from 'node:crypto';
import { basename, dirname, isAbsolute, join, parse, resolve } from 'node:path';

const MAX_BYTES = 1024 * 1024;
const failure = (code) => Object.assign(new Error(code), { code });
const knownReadErrors = new Set(['runtime_data_denied', 'runtime_data_closed', 'runtime_data_unavailable', 'runtime_data_too_large']);

/**
 * Restricted adapter for the built-in Bot Store only. `files` is a trusted,
 * already-bound native file capability, not a path- or directory-selector.
 * State, usage and per-chat documents map to flat, private capability names.
 * This does not adapt MemoryStore, uploads or the mcode runtime filesystem.
 */
export function createBotStorePersistence({ dataDir, files } = {}) {
  if (typeof dataDir !== 'string' || !isAbsolute(dataDir) || resolve(dataDir) !== dataDir || dataDir === parse(dataDir).root || dataDir.includes('\0') ||
      typeof files?.read !== 'function' || typeof files?.replace !== 'function' || typeof files?.append !== 'function') {
    throw new TypeError('A private Bot store capability and canonical virtual directory are required');
  }
  const state = join(dataDir, 'state.json');
  const messages = join(dataDir, 'messages');
  const usage = join(dataDir, 'usage.jsonl');
  let invalidated;
  let mutationTail = Promise.resolve();
  let pendingMutations = 0;

  function requireHealthy() { if (invalidated) throw failure(invalidated); }
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
      invalidated = 'runtime_data_reload_required';
      throw failure('runtime_data_too_large');
    }
    return bytes;
  }
  async function readText(path, kind) {
    requireHealthy();
    const name = nameFor(path, kind);
    let bytes;
    try { bytes = await files.read(name); }
    catch (error) {
      if (error?.code === 'runtime_data_not_found') throw failure('ENOENT');
      throw failure(knownReadErrors.has(error?.code) ? error.code : 'runtime_data_unavailable');
    }
    if (!(bytes instanceof Uint8Array) || bytes.byteLength > MAX_BYTES) throw failure('runtime_data_unavailable');
    try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); }
    catch { throw failure('runtime_data_invalid'); }
  }
  function mutate(method, name, bytes) {
    requireHealthy();
    if (pendingMutations >= 32) {
      invalidated = 'runtime_data_reload_required';
      throw failure('runtime_data_unavailable');
    }
    pendingMutations++;
    const operation = mutationTail.then(async () => {
      requireHealthy();
      let result;
      try { result = await files[method](name, bytes); }
      catch (error) {
        // The native append limit is checked before publication, but a Store
        // caller may already have changed its in-memory state. Require reload.
        if (error?.code === 'runtime_data_too_large') {
          invalidated = 'runtime_data_reload_required';
          throw failure('runtime_data_too_large');
        }
        invalidated = 'runtime_data_outcome_unknown';
        throw failure('runtime_data_outcome_unknown');
      }
      if (result !== 'published') { invalidated = 'runtime_data_outcome_unknown'; throw failure('runtime_data_outcome_unknown'); }
    });
    mutationTail = operation.then(() => undefined, () => undefined);
    return operation.finally(() => { pendingMutations--; });
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
      catch { invalidated = 'runtime_data_reload_required'; throw failure('runtime_data_invalid'); }
      if (typeof text !== 'string') { invalidated = 'runtime_data_reload_required'; throw failure('runtime_data_invalid'); }
      await mutate('replace', name, encode(text));
    },
    async appendUsage(path, line) {
      requireHealthy();
      const name = nameFor(path, 'usage');
      if (typeof line !== 'string') throw failure('runtime_data_invalid');
      await mutate('append', name, encode(line));
    },
    readUsage(path) { return readText(path, 'usage'); },
  });
}
