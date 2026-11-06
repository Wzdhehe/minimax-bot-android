// SPDX-License-Identifier: AGPL-3.0-only
const failure = (code) => Object.assign(new Error(code), { code });
const namePattern = /^runtime-state-before-v2-migration-([1-9][0-9]{0,15})-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.sqlite$/u;
export function validSnapshotName(value) {
  if (typeof value !== 'string') return false;
  const match = namePattern.exec(value);
  return Boolean(match && Number.isSafeInteger(Number(match[1])));
}

/** Keyless projection of RuntimeSnapshotOwner.Info, not arbitrary fs metadata.
 * Reject whole malformed catalogs; never return raw native exception text. */
export function validateSnapshotCatalog(input) {
  try {
    if (typeof input === 'string') {
      if (Buffer.byteLength(input, 'utf8') > 4096) throw Error();
      input = JSON.parse(input);
    }
    if (!Array.isArray(input) || input.length > 8 || Object.getOwnPropertySymbols(input).length ||
        Object.getOwnPropertyNames(input).some((key) => key !== 'length' && !/^(0|[1-9][0-9]*)$/u.test(key))) throw Error();
    for (let index = 0; index < input.length; index++) {
      const item = Object.getOwnPropertyDescriptor(input, String(index));
      if (!item || !Object.hasOwn(item, 'value')) throw Error();
    }
    const names = new Set(), identities = new Set();
    const rows = input.map((row) => {
      if (!row || typeof row !== 'object' || Array.isArray(row) || Object.getOwnPropertySymbols(row).length) throw Error();
      const descriptors = Object.getOwnPropertyDescriptors(row);
      if (Object.keys(descriptors).sort().join(',') !== 'bytes,createdAtMs,fileName,identity' ||
          Object.values(descriptors).some((item) => !Object.hasOwn(item, 'value'))) throw Error();
      const fileName = descriptors.fileName.value, bytes = descriptors.bytes.value;
      const createdAtMs = descriptors.createdAtMs.value, identity = descriptors.identity.value;
      if (!validSnapshotName(fileName) || !Number.isSafeInteger(bytes) || bytes <= 0 || bytes > 512 * 1024 * 1024 ||
          !Number.isSafeInteger(createdAtMs) || createdAtMs < 0 || typeof identity !== 'string' || !/^[0-9a-f]{32}$/u.test(identity) ||
          names.has(fileName) || identities.has(identity)) throw Error();
      names.add(fileName); identities.add(identity);
      return Object.freeze({ fileName, bytes, createdAtMs, identity });
    });
    if (rows.length !== input.length || rows.some((row) => row === undefined)) throw Error();
    return Object.freeze(rows);
  } catch { throw failure('runtime_snapshot_catalog_invalid'); }
}

/** Native supplies ONLY private Binder operations plus an actual SQLite
 * constructor over a read-only FD. openDatabase MUST consume that FD on every
 * outcome, including refusal. It must not accept a path or manufacture an
 * integrity-check response. This adapter grants no directory/writable FDs.
 */
export function createNativeSnapshotSqlitePort(native) {
  const methods = {};
  try {
    for (const key of ['copy', 'catalog', 'openReadOnly', 'remove', 'openDatabase']) {
      if (typeof native?.[key] !== 'function') throw Error();
      methods[key] = native[key].bind(native);
    }
  } catch { throw failure('runtime_snapshot_port_invalid'); }
  let active = true, poisoned = false;
  function check() {
    if (poisoned) throw failure('runtime_snapshot_outcome_unknown');
    if (!active) throw failure('runtime_snapshot_closed');
  }
  function catalog() {
    check();
    let rows;
    try { rows = methods.catalog(); } catch { throw failure('runtime_snapshot_unavailable'); }
    check();
    return validateSnapshotCatalog(rows); // Deliberately synchronous for SQLite constructor calls.
  }
  function selected(fileName) {
    if (!validSnapshotName(fileName)) throw failure('runtime_snapshot_invalid');
    const row = catalog().find((entry) => entry.fileName === fileName);
    if (!row) throw failure('ENOENT');
    return row;
  }
  return Object.freeze({
    async backup(_database, request) {
      check();
      if (!validSnapshotName(request?.fileName)) throw failure('runtime_snapshot_invalid');
      let status;
      try { status = await methods.copy(request.fileName); }
      catch { poisoned = true; throw failure('runtime_snapshot_outcome_unknown'); }
      if (status === 'outcome_unknown' || !['published', 'already_exists', 'not_published'].includes(status)) {
        poisoned = true; throw failure('runtime_snapshot_outcome_unknown');
      }
      check();
      if (status !== 'published') throw failure(status === 'already_exists' ? 'runtime_snapshot_exists' : 'runtime_snapshot_refused');
      return 'published';
    },
    openBackup(request) {
      check();
      const options = request?.options;
      if (!options || options.readonly !== true || options.fileMustExist !== true ||
          Object.keys(options).some((key) => !['readonly', 'fileMustExist', 'timeout'].includes(key)) ||
          (options.timeout !== undefined && (!Number.isInteger(options.timeout) || options.timeout < 0 || options.timeout > 5000))) {
        throw failure('runtime_snapshot_invalid');
      }
      const row = selected(request?.fileName);
      let fd;
      try { fd = methods.openReadOnly(row.fileName, row.identity); } catch { throw failure('runtime_snapshot_unavailable'); }
      if (!Number.isSafeInteger(fd) || fd < 3 || fd > 0x7fffffff) throw failure('runtime_snapshot_unavailable');
      // No asynchronous gap after FD receipt. The consuming native constructor
      // owns cleanup even if the scope closes during its invocation.
      let database;
      try { database = methods.openDatabase(fd, Object.freeze({ ...options })); }
      catch { throw failure('runtime_snapshot_unavailable'); }
      try { check(); return database; }
      catch (error) { try { database?.close?.(); } catch {} throw error; }
    },
    snapshots: Object.freeze({
      catalog,
      async remove(request) {
        check();
        const row = selected(request?.fileName);
        if (request.identity !== row.identity) throw failure('runtime_snapshot_identity_mismatch');
        let status;
        try { status = await methods.remove(row.fileName, row.identity); }
        catch { poisoned = true; throw failure('runtime_snapshot_outcome_unknown'); }
        if (status !== 'removed') { poisoned = true; throw failure('runtime_snapshot_outcome_unknown'); }
        check();
        return 'removed';
      },
    }),
    deactivate() { active = false; },
  });
}
