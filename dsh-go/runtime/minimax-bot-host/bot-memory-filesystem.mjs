// SPDX-License-Identifier: AGPL-3.0-only
import { createHash } from 'node:crypto';
import { constants } from 'node:fs';
import { basename, dirname, isAbsolute, join, parse, relative, resolve } from 'node:path';

const failure = (code) => Object.assign(new Error(code), { code });
const scope = '[A-Za-z0-9_-]{1,128}';
const operation = '[a-f0-9]{32}';

/** Narrow filesystem vocabulary for the vendored Bot MemoryStore. Files are
 * addressed only through the same trusted lease as the Bot Store; no physical
 * path or directory descriptor crosses into the isolated runtime. */
export function createBotMemoryFilesystem({ dataDir, lease } = {}) {
  const needed = ['read', 'size', 'replace', 'append', 'publish', 'rename', 'remove'];
  if (typeof dataDir !== 'string' || !isAbsolute(dataDir) || resolve(dataDir) !== dataDir || dataDir === parse(dataDir).root || dataDir.includes('\0') ||
      typeof lease?.healthy !== 'function' || typeof lease?.invalidate !== 'function' || typeof lease?.supports !== 'function' || !lease.supports(needed) ||
      needed.some((method) => typeof lease[method] !== 'function')) {
    throw new TypeError('A shared Bot file lease and canonical virtual directory are required');
  }
  const root = join(dataDir, 'memory');
  const bots = join(root, 'bots'), archive = join(root, 'archive'), backups = join(root, 'backups');
  const scopeDocument = new RegExp(`^${scope}\\.md(?:\\.${operation}\\.pending)?$`);
  const backupName = new RegExp(`^(?:${scope}\\.pre-storage-v2\\.md|\\.${scope}\\.${operation}\\.tmp)$`);
  const archiveName = new RegExp(`^${scope}-[0-9]{1,16}\\.md$`);
  const rootName = new RegExp(`^(?:journal\\.jsonl|team\\.md(?:\\.${operation}\\.pending)?|\\.pending\\.json(?:\\.${operation}\\.tmp)?)$`);

  function nameFor(path) {
    if (typeof path !== 'string' || !isAbsolute(path) || resolve(path) !== path) throw failure('runtime_data_invalid');
    const parent = dirname(path), name = basename(path);
    const allowed = (parent === root && rootName.test(name)) || (parent === bots && scopeDocument.test(name)) ||
      (parent === backups && backupName.test(name)) || (parent === archive && archiveName.test(name));
    if (!allowed || join(parent, name) !== path) throw failure('runtime_data_invalid');
    // A pending record contains all bytes needed to regenerate its document;
    // temporary preparations and backup copies are never durable documents.
    // Distinguish those names so Main can eventually clean only this reserved
    // namespace after retiring the previous exclusive writer. Existing durable
    // document, journal, backup and pending-record names stay unchanged.
    const transient = name.endsWith('.pending') || name.endsWith('.tmp');
    const prefix = transient ? 'bot-memory-stage-' : 'bot-memory-';
    return `${prefix}${createHash('sha256').update(relative(root, path)).digest('hex')}.bin`;
  }
  function missing(error) { return error?.code === 'runtime_data_not_found' ? failure('ENOENT') : error; }
  function bytes(content, encoding) {
    if (encoding !== undefined && encoding !== 'utf8') { lease.invalidate(); throw failure('runtime_data_invalid'); }
    if (typeof content === 'string') return Buffer.from(content, 'utf8');
    if (content instanceof Uint8Array) return content;
    lease.invalidate();
    throw failure('runtime_data_invalid');
  }
  async function read(path) {
    try { return await lease.read(nameFor(path)); } catch (error) { throw missing(error); }
  }
  async function publishExclusive(path, contents) {
    const result = await lease.publish(nameFor(path), contents);
    if (result === 'already_exists') throw failure('EEXIST');
  }
  return Object.freeze({
    async mkdir(path, options) {
      lease.healthy();
      if (![bots, archive, backups].includes(path) || options?.recursive !== true) throw failure('runtime_data_invalid');
    },
    async readFile(path, encoding) {
      const data = await read(path);
      if (encoding === undefined) return Buffer.from(data);
      if (encoding !== 'utf8') throw failure('runtime_data_invalid');
      try { return new TextDecoder('utf-8', { fatal: true }).decode(data); }
      catch { throw failure('runtime_data_invalid'); }
    },
    async writeFile(path, content, encoding) { await lease.replace(nameFor(path), bytes(content, encoding)); },
    async appendFile(path, content, encoding) { await lease.append(nameFor(path), bytes(content, encoding)); },
    async copyFile(source, target, flags) {
      if (flags !== constants.COPYFILE_EXCL) throw failure('runtime_data_invalid');
      await publishExclusive(target, await read(source));
    },
    async link(source, target) { await publishExclusive(target, await read(source)); },
    async stat(path) {
      try { await lease.size(nameFor(path)); } catch (error) { throw missing(error); }
      return Object.freeze({ isFile: () => true });
    },
    async rename(source, target) {
      try { await lease.rename(nameFor(source), nameFor(target)); } catch (error) { throw missing(error); }
    },
    async rm(path, options) {
      try { await lease.remove(nameFor(path)); }
      catch (error) {
        const mapped = missing(error);
        if (mapped?.code === 'ENOENT' && options?.force === true) return;
        throw mapped;
      }
    },
  });
}
