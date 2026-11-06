// SPDX-License-Identifier: AGPL-3.0-only
// Exact pinned DB import seam. Other runtime files still need their own native
// filesystem assembly; this is not an isolation boundary or a global fs patch.
import * as fs from 'node:fs';
import { runtimeSqliteFilesystem, SQLITE_FS_UNHANDLED } from './runtime-sqlite-binding.mjs';
export * from 'node:fs';

function invoke(operation, path, value) {
  const result = runtimeSqliteFilesystem(operation, path, value);
  return result === SQLITE_FS_UNHANDLED ? fs[operation](path, value) : result;
}
export const mkdirSync = (path, options) => invoke('mkdirSync', path, options);
export const chmodSync = (path, mode) => invoke('chmodSync', path, mode);
export const existsSync = (path) => invoke('existsSync', path);
export default Object.freeze({ ...fs, mkdirSync, chmodSync, existsSync });
