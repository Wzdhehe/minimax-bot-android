// SPDX-License-Identifier: MIT
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createWorkspaceGuard, resolveWorkspacePath, WorkspacePathError } from '../src/index.js';

test('strict Agent API rejects absolute and traversal input even inside root', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'strict-workspace-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  for (const value of ['', ' ', null, 'a\0b', '../x', 'a/../x', '/sdcard/x', '/data/data/other/x', join(root, 'x'), 'C:\\x', '..\\x']) {
    await assert.rejects(resolveWorkspacePath(root, value), WorkspacePathError);
  }
  assert.equal(await resolveWorkspacePath(root, 'notes/new.txt'), join(root, 'notes/new.txt'));
});

test('a dangling symlink cannot be treated as an innocently missing directory', async (t) => {
  const base = await mkdtemp(join(tmpdir(), 'dangling-workspace-'));
  t.after(() => rm(base, { recursive: true, force: true }));
  const root = join(base, 'workspace');
  await mkdir(root);
  await symlink(join(base, 'outside-missing'), join(root, 'escape'));
  const guard = await createWorkspaceGuard(root);
  await assert.rejects(resolveWorkspacePath(root, 'escape/new.txt'), WorkspacePathError);
  await assert.rejects(guard.writeFile('escape/new.txt', 'blocked'), WorkspacePathError);
  await mkdir(join(base, 'outside-missing'));
  await writeFile(join(base, 'outside-missing/secret'), 'secret');
  await assert.rejects(resolveWorkspacePath(root, 'escape/secret'), WorkspacePathError);
});
