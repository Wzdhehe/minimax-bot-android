// SPDX-License-Identifier: AGPL-3.0-only
// Actual official Bot HTTP + compiled product filesystem; no Android claim.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import test from 'node:test';

const filesystem = process.env.MBOT_MOBILE_FS_BUNDLE;
const esbuildModule = process.env.ESBUILD_MODULE;
if (!filesystem || !esbuildModule) throw Error('Set MBOT_MOBILE_FS_BUNDLE and ESBUILD_MODULE');
const here = dirname(fileURLToPath(import.meta.url));
const child = String.raw`
import assert from 'node:assert/strict'; import fs from 'node:fs'; import {createServer} from 'node:net';
const [fsUrl, serviceFile, dir, phase] = process.argv.slice(1);
const read=fs.readFileSync.bind(fs), write=fs.writeFileSync.bind(fs), remove=fs.unlinkSync.bind(fs);
const {startBotService,createBotPersistence}=await import('data:text/javascript;base64,'+read(serviceFile).toString('base64'));
const files={
 readSync(name){try{return read(dir+'/'+name)}catch(e){if(e.code==='ENOENT')throw Object.assign(Error(),{code:'runtime_data_not_found'});throw e}},
 replaceSync(name,bytes){write(dir+'/'+name,bytes);return 'published'},
 async read(name){return this.readSync(name)},async size(name){return this.readSync(name).length},
 async replace(name,bytes){return this.replaceSync(name,bytes)},
 async append(name,bytes){let old;try{old=this.readSync(name)}catch(e){if(e.code!=='runtime_data_not_found')throw e;old=Buffer.alloc(0)}return this.replaceSync(name,Buffer.concat([old,bytes]))},
 async publish(name,bytes){try{this.readSync(name);return 'already_exists'}catch(e){if(e.code!=='runtime_data_not_found')throw e}return this.replaceSync(name,bytes)},
 async rename(from,to){this.replaceSync(to,this.readSync(from));return this.remove(from)},
 removeSync(name){try{remove(dir+'/'+name)}catch(e){if(e.code!=='ENOENT')throw e}return 'removed'},
 async remove(name){return this.removeSync(name)},
};
const {installMobileFilesystem}=await import(fsUrl);
const filesystem=installMobileFilesystem({files});
const reservation=createServer();await new Promise(done=>reservation.listen(0,'127.0.0.1',done));const port=reservation.address().port;await new Promise(done=>reservation.close(done));
const dataDir=filesystem.botFiles.dataDir;
const service=await startBotService({port,dataDir,fileMode:'mobile',mobileFiles:filesystem.botFiles,
 ...createBotPersistence({dataDir,files}),authToken:'c'.repeat(64),signal:new AbortController().signal,
 logger:{info(){},warn(){},error(){}}});
const base=service.descriptor.baseUrl,headers=service.descriptor.headers;
try{
 if(phase==='escape'){
  fs.mkdirSync('/work/.mbot',{recursive:true});
  fs.symlinkSync('/mbot-data/runtime','/work/.mbot/uploads');
  const response=await fetch(base+'/api/uploads?name=escape.bin',{method:'POST',headers,body:'refused'});
  assert.ok(response.status>=400);await response.text();
  assert.ok(!fs.readdirSync('/mbot-data/runtime').some(name=>name.includes('escape.bin')||name.startsWith('.part-')));
 }else if(phase==='write'){
  const content=Buffer.alloc(84*1024);for(let i=0;i<content.length;i++)content[i]=i%256;
  const response=await fetch(base+'/api/uploads?name=notes.bin',{method:'POST',headers,body:content});
  assert.equal(response.status,200);const {upload}=await response.json();assert.equal(upload.size,content.length);
  const downloaded=await fetch(base+'/api/uploads/'+upload.id,{headers});assert.equal(downloaded.status,200);
  assert.deepEqual(Buffer.from(await downloaded.arrayBuffer()),content);
  assert.equal((await fetch(base+'/api/uploads/'+upload.id)).status,403);
  await assert.rejects(filesystem.botFiles.resolve('../mbot-data/runtime/config.yaml'),{code:'EPERM'});
  fs.symlinkSync('/mbot-data/runtime','/work/escape');
  await assert.rejects(filesystem.botFiles.resolve('/work/escape'),{code:'EPERM'});
  write(dir+'/receipt.json',JSON.stringify(upload));
 }else{
  const upload=JSON.parse(read(dir+'/receipt.json'));
  const response=await fetch(base+'/api/uploads/'+upload.id,{headers});assert.equal(response.status,200);
  const bytes=Buffer.from(await response.arrayBuffer());assert.equal(bytes.length,84*1024);assert.equal(bytes[511],255);
 }
}finally{await service.dispose();await new Promise(done=>setTimeout(done,25));filesystem.close()}
`;

test('official upload bytes survive a new virtual filesystem process and private paths remain refused', async (t) => {
  const esbuild = await import(pathToFileURL(resolve(esbuildModule)).href);
  assert.equal(esbuild.version, '0.28.2');
  const directory = mkdtempSync(join(tmpdir(), 'mbot-mobile-uploads-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const result = await esbuild.build({ stdin: {
    contents: "export {startBotService} from './service.mjs';export {createBotPersistence} from './bot-persistence.mjs';",
    resolveDir: resolve(here, '../../runtime/minimax-bot-host'),
  }, bundle: true, format: 'esm', platform: 'node', target: 'node24', write: false, logLevel: 'silent' });
  const serviceFile = join(directory, 'service.txt');
  writeFileSync(serviceFile, result.outputFiles[0].contents);
  for (const phase of ['write', 'read']) execFileSync(process.execPath, ['--input-type=module', '-e', child,
    pathToFileURL(resolve(filesystem)).href, serviceFile, directory, phase], { timeout: 20_000, stdio: 'pipe' });
  const state = JSON.parse(readFileSync(join(directory, 'runtime-fs-state.json')));
  assert.ok(state.entries.some((row) => row.path.startsWith('.mbot/uploads/')));
  const escaped = mkdtempSync(join(tmpdir(), 'mbot-mobile-upload-escape-'));
  t.after(() => rmSync(escaped, { recursive: true, force: true }));
  execFileSync(process.execPath, ['--input-type=module', '-e', child,
    pathToFileURL(resolve(filesystem)).href, serviceFile, escaped, 'escape'], { timeout: 20_000, stdio: 'pipe' });
});
