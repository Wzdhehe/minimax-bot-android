// SPDX-License-Identifier: AGPL-3.0-only
// ONLY the pinned infra/db/backup.ts import is redirected here, not global fs.
import * as fs from 'node:fs/promises';
import { runtimeSqliteSnapshotFilesystem, SQLITE_FS_UNHANDLED } from './runtime-sqlite-binding.mjs';
export * from 'node:fs/promises';
async function invoke(operation, path, options) {
  const result = await runtimeSqliteSnapshotFilesystem(operation, path, options);
  return result === SQLITE_FS_UNHANDLED ? fs[operation](path, options) : result;
}
export const realpath = (path, options) => invoke('realpath', path, options);
export const lstat = (path, options) => invoke('lstat', path, options);
export const mkdir = (path, options) => invoke('mkdir', path, options);
export const readdir = (path, options) => invoke('readdir', path, options);
export const rm = (path, options) => invoke('rm', path, options);
export default Object.freeze({ ...fs, realpath, lstat, mkdir, readdir, rm });
