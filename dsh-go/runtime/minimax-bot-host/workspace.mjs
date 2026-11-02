// SPDX-License-Identifier: AGPL-3.0-only
import { lstat, mkdir, realpath } from 'node:fs/promises';
import { isAbsolute, join } from 'node:path';
import { createWorkspaceGuard, resolveWorkspacePath, WorkspacePathError } from '../../../packages/mbot-workspace-guard/src/index.js';

/** filesDir must come from Android Context.getFilesDir(), never a request body.
 * These operations do not run code, expose a shell, or claim race-proof opens. */
export async function createHostWorkspace(filesDir) {
  if (typeof filesDir !== 'string' || !isAbsolute(filesDir)) {
    throw new WorkspacePathError('A trusted absolute filesDir is required', { code: 'ROOT' });
  }
  const privateRoot = await realpath(filesDir);
  const root = join(privateRoot, 'workspace');
  await mkdir(root, { recursive: true, mode: 0o700 });
  if ((await lstat(root)).isSymbolicLink() || await realpath(root) !== root) {
    throw new WorkspacePathError('Workspace root must not be redirected', { code: 'ROOT' });
  }
  const guard = await createWorkspaceGuard(root);
  const resolve = async (path) => {
    if ((await lstat(root)).isSymbolicLink() || await realpath(root) !== root) {
      throw new WorkspacePathError('Workspace root was redirected', { code: 'ROOT' });
    }
    return resolveWorkspacePath(root, path);
  };
  return Object.freeze({
    root,
    resolve,
    async readFile(path) { return guard.readFile(await resolve(path)); },
    async writeFile(path, text) {
      if (typeof text !== 'string' || Buffer.byteLength(text) > 1024 * 1024) {
        throw new WorkspacePathError('Expected at most 1 MiB of text', { code: 'INVALID' });
      }
      await guard.writeFile(await resolve(path), text, { encoding: 'utf8', mode: 0o600 });
    },
  });
}
