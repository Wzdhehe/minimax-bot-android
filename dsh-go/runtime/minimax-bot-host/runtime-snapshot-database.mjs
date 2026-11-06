// SPDX-License-Identifier: AGPL-3.0-only
const failure = () => Object.assign(new Error('runtime_snapshot_database_unavailable'), { code: 'runtime_snapshot_database_unavailable' });

/** Private launcher adapter, ONLY for fresh read-only descriptors returned by
 * Main's snapshot capability. Never call with a tool-supplied descriptor or a
 * primary family alias: consuming such an alias can release SQLite locks.
 * The low-level VFS registration itself never closes the caller's descriptor.
 * Database/nativeBinding are the pinned real addon, not an integrity stub.
 */
export function createSnapshotFdDatabase({ Database, nativeBinding, closeDescriptor } = {}) {
  if (typeof Database !== 'function' || typeof nativeBinding?.fdVfsReadOnlySnapshot !== 'function' ||
      typeof closeDescriptor !== 'function') throw failure();
  const register = nativeBinding.fdVfsReadOnlySnapshot.bind(nativeBinding);
  return function openGrantedSnapshot(fd, options) {
    if (!Number.isInteger(fd) || fd < 3 || fd > 0x7fffffff) throw failure();
    let database, error = false;
    try {
      if (!options || options.readonly !== true || options.fileMustExist !== true ||
          Object.keys(options).some((key) => !['readonly', 'fileMustExist', 'timeout'].includes(key)) ||
          (options.timeout !== undefined && (!Number.isInteger(options.timeout) || options.timeout < 0 || options.timeout > 5000))) throw failure();
      const name = register(fd);
      if (typeof name !== 'string' || !/^snapshot-[1-8]\.db$/u.test(name)) throw failure();
      database = new Database(name, { ...options, nativeBinding });
      if (database.open !== true || database.readonly !== true) throw failure();
    } catch { error = true; }
    finally {
      // A valid input here is a NEW Main-issued snapshot FD, whose ownership
      // this function consumes on both successful and failed construction.
      try { closeDescriptor(fd); } catch { error = true; }
    }
    if (error) {
      try { database?.close(); } catch {}
      throw failure();
    }
    return database;
  };
}
