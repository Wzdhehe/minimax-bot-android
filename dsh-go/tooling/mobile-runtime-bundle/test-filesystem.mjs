// SPDX-License-Identifier: AGPL-3.0-only
// Run with MBOT_MOBILE_FS_BUNDLE pointing to the compiled filesystem.mjs.
// Separate processes are deliberate: one isolated execution never rebinds.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

const bundle = process.env.MBOT_MOBILE_FS_BUNDLE;
if (!bundle) throw Error('MBOT_MOBILE_FS_BUNDLE must select an actual compiled mobile filesystem');
const child = String.raw`
import assert from 'node:assert/strict'; import fs from 'node:fs'; import {createHash} from 'node:crypto';
const [url,dir,phase] = process.argv.slice(1);
const rawRead=fs.readFileSync.bind(fs),rawWrite=fs.writeFileSync.bind(fs),rawRemove=fs.unlinkSync.bind(fs);
let uncertain=false, commits=0;
const files={
 readSync(name){try{return rawRead(dir+'/'+name)}catch(e){if(e.code==='ENOENT')throw Object.assign(Error(),{code:'runtime_data_not_found'});throw e}},
 replaceSync(name,bytes){rawWrite(dir+'/'+name,bytes);if(name==='runtime-fs-state.json'){commits++;if(uncertain)return 'outcome_unknown'}return 'published'},
 removeSync(name){try{rawRemove(dir+'/'+name)}catch(e){if(e.code!=='ENOENT')throw e}return 'outcome_unknown'}
};
const bytes=Buffer.alloc(8192,97),sha256=createHash('sha256').update(bytes).digest('hex');
const {installMobileFilesystem}=await import(url);
if(phase==='corrupt'){assert.throws(()=>installMobileFilesystem({files,assets:[{path:'assets/example.txt',bytes:bytes.length,sha256,base64:bytes.toString('base64')}]}),{code:'mobile_fs_state_invalid'});assert.equal(commits,0);process.exit(0)}
installMobileFilesystem({files,assets:[{path:'assets/example.txt',bytes:bytes.length,sha256,base64:bytes.toString('base64')}]});
if(phase==='write'){
 const large=Buffer.alloc(1536*1024,120);
 fs.writeFileSync('/work/a',large);fs.linkSync('/work/a','/work/alias');
 fs.writeFileSync('/work/alias','small'); // Removes old chunks after a durable manifest.
 fs.writeFileSync('/work/alias',large);  // Uncertain cleanup may have removed them: republish.
 assert.equal(fs.readFileSync('/work/a').length,large.length);
 fs.copyFileSync('/mbot-code/assets/example.txt','/work/copied-asset');
 assert.throws(()=>fs.writeFileSync('/mbot-code/assets/example.txt','bad'),{code:'EROFS'});
 await fs.promises.writeFile('/work/async','saved');
 const handle=await fs.promises.open('/work/async','r+');await handle.writeFile('newer');await handle.close();
}else if(phase==='read'){
 assert.equal(fs.readFileSync('/work/a').length,1536*1024);
 assert.equal(fs.readFileSync('/work/a')[0],120);
 assert.equal(fs.statSync('/work/a').ino,fs.statSync('/work/alias').ino);
 assert.equal(fs.readFileSync('/work/copied-asset').length,8192);
 assert.equal(fs.readFileSync('/work/async','utf8'),'newer');
}else if(phase==='poison'){
 uncertain=true;const before=commits;
 assert.throws(()=>fs.writeFileSync('/work/uncertain','accepted but receipt lost'),{code:'mobile_fs_outcome_unknown'});
 assert.equal(commits,before+1);
 assert.throws(()=>fs.readFileSync('/work/uncertain'),{code:'mobile_fs_outcome_unknown'});
}
process.exit(0);
`;

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'mbot-mobile-fs-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  return root;
}
function run(root, phase) {
  execFileSync(process.execPath, ['--input-type=module', '-e', child,
    pathToFileURL(resolve(bundle)).href, root, phase], { timeout: 15_000, stdio: 'pipe' });
}
test('large files, hard-link mutation, uncertain blob cleanup, APK resource references and FileHandles survive a new process', (t) => {
  const root = fixture(t); run(root, 'write'); run(root, 'read');
  const state = JSON.parse(readFileSync(join(root, 'runtime-fs-state.json'), 'utf8'));
  assert.equal(state.version, 2);
  assert.ok(readFileSync(join(root, 'runtime-fs-state.json')).length < 1024 * 1024);
  assert.ok(state.entries.some((row) => row.asset === 'assets/example.txt'));
  for (const name of readdirSync(root).filter((name) => name.startsWith('fsblob-'))) assert.ok(readFileSync(join(root, name)).length <= 256 * 1024);
});
test('a lost manifest receipt poisons the execution without retrying or pretending rollback', (t) => {
  run(fixture(t), 'poison');
});
test('a corrupt persisted blob refuses the entire restored execution without creating empty state', (t) => {
  const root = fixture(t); run(root, 'write');
  const name = readdirSync(root).find((name) => name.startsWith('fsblob-'));
  assert.ok(name); writeFileSync(join(root, name), Buffer.from('corrupt bytes'));
  run(root, 'corrupt');
});
