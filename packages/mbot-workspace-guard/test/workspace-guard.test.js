import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  WorkspacePathError,
  createWorkspaceGuard,
  resolveWithinWorkspace,
} from '../src/index.js';

function tmpDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

test('in-workspace read/write succeeds', async () => {
  const root = tmpDir('mbot-ws-');
  const guard = await createWorkspaceGuard(root);
  await guard.writeFile('notes/hello.txt', 'hi');
  assert.equal(await guard.readFile('notes/hello.txt'), 'hi');
  const abs = await guard.resolve('notes/hello.txt');
  assert.equal(abs, path.join(fs.realpathSync(root), 'notes', 'hello.txt'));
});

test('rejects .. escape', async () => {
  const root = tmpDir('mbot-ws-');
  const outside = tmpDir('mbot-out-');
  fs.writeFileSync(path.join(outside, 'secret.txt'), 'LEAK');
  const guard = await createWorkspaceGuard(root);
  const rel = path.relative(root, path.join(outside, 'secret.txt'));
  assert.ok(rel.startsWith('..'));
  await assert.rejects(() => guard.resolve(rel), (err) => {
    assert.ok(err instanceof WorkspacePathError);
    assert.equal(err.code, 'OUTSIDE');
    return true;
  });
  await assert.rejects(() => guard.readFile('../' + path.basename(outside) + '/secret.txt'));
});

test('rejects absolute /sdcard style paths', async () => {
  const root = tmpDir('mbot-ws-');
  const guard = await createWorkspaceGuard(root);
  await assert.rejects(() => guard.resolve('/sdcard/DCIM'), WorkspacePathError);
  await assert.rejects(() => guard.readFile('/sdcard/DCIM/x.jpg'), WorkspacePathError);
});

test('rejects other-app style absolute paths', async () => {
  const root = tmpDir('mbot-ws-');
  const guard = await createWorkspaceGuard(root);
  await assert.rejects(
    () => guard.resolve('/data/data/com.other.app/files/secret'),
    WorkspacePathError,
  );
});

test('rejects symlink file escape', async () => {
  const root = tmpDir('mbot-ws-');
  const outside = tmpDir('mbot-out-');
  fs.writeFileSync(path.join(outside, 'secret.txt'), 'LEAK');
  fs.symlinkSync(path.join(outside, 'secret.txt'), path.join(root, 'innocent.txt'));
  const guard = await createWorkspaceGuard(root);
  await assert.rejects(() => guard.resolve('innocent.txt'), WorkspacePathError);
  await assert.rejects(() => guard.readFile('innocent.txt'), WorkspacePathError);
});

test('rejects symlink directory escape', async () => {
  const root = tmpDir('mbot-ws-');
  const outside = tmpDir('mbot-out-');
  fs.writeFileSync(path.join(outside, 'secret.txt'), 'LEAK');
  fs.symlinkSync(outside, path.join(root, 'escape'));
  const guard = await createWorkspaceGuard(root);
  await assert.rejects(() => guard.resolve('escape/secret.txt'), WorkspacePathError);
  await assert.rejects(() => guard.readFile('escape/secret.txt'), WorkspacePathError);
});

test('prefix confusion: workspace vs workspace-evil', async () => {
  const parent = tmpDir('mbot-parent-');
  const workspace = path.join(parent, 'workspace');
  const evil = path.join(parent, 'workspace-evil');
  fs.mkdirSync(workspace);
  fs.mkdirSync(evil);
  fs.writeFileSync(path.join(evil, 'secret.txt'), 'LEAK');
  fs.writeFileSync(path.join(workspace, 'ok.txt'), 'ok');

  const guard = await createWorkspaceGuard(workspace);
  assert.equal(await guard.readFile('ok.txt'), 'ok');
  await assert.rejects(() => guard.resolve(path.join(evil, 'secret.txt')), WorkspacePathError);
  await assert.rejects(() => guard.readFile('../workspace-evil/secret.txt'), WorkspacePathError);

  // startsWith without separator would wrongly allow this; guard must not.
  const rootReal = fs.realpathSync(workspace);
  assert.ok(fs.realpathSync(evil).startsWith(rootReal)); // naive bug condition
  await assert.rejects(
    () => resolveWithinWorkspace(path.join(evil, 'secret.txt'), workspace),
    WorkspacePathError,
  );
});

test('NUL in path rejected', async () => {
  const root = tmpDir('mbot-ws-');
  const guard = await createWorkspaceGuard(root);
  await assert.rejects(() => guard.resolve('a\0b'), WorkspacePathError);
});
