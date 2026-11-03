#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build the pinned SQLite addon for the isolated verification APK, not production."""
import argparse
import ast
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
PIN = json.loads((ROOT / 'sqlite-runtime-pin.json').read_text())


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def extract(archive, output):
    if digest(archive) != PIN['sha256']:
        raise RuntimeError('SQLite source archive digest mismatch')
    total, names = 0, set()
    with tarfile.open(archive) as package:
        for item in package:
            path = PurePosixPath(item.name)
            if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != 'package' or item.name in names:
                raise RuntimeError('Unsafe SQLite archive path')
            names.add(item.name)
            if len(names) > 5000 or not (item.isdir() or item.isfile()):
                raise RuntimeError('Unsafe SQLite archive member')
            if item.isdir():
                continue
            total += item.size
            if item.size > 32 * 1024 * 1024 or total > 64 * 1024 * 1024:
                raise RuntimeError('SQLite archive extraction budget exceeded')
            destination = output.joinpath(*path.parts[1:])
            destination.parent.mkdir(parents=True, exist_ok=True)
            with package.extractfile(item) as source, destination.open('wb') as target:
                shutil.copyfileobj(source, target, 1024 * 1024)


def prepare(ndk, archive, esbuild):
    if PIN['ndkVersion'] not in (ndk / 'source.properties').read_text():
        raise RuntimeError('Expected pinned NDK27d')
    if subprocess.check_output([str(esbuild), '--version'], text=True).strip() != PIN['esbuildVersion']:
        raise RuntimeError('Expected pinned esbuild')
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        temporary = archive.with_suffix('.partial')
        urllib.request.urlretrieve(PIN['url'], temporary)
        temporary.replace(archive)
    build = ROOT / 'build/sqlite-probe'
    if build.exists():
        shutil.rmtree(build)
    source = build / 'source'
    extract(archive, source)
    package = json.loads((source / 'package.json').read_text())
    if package['name'] != PIN['package'] or package['version'] != PIN['version']:
        raise RuntimeError('SQLite package identity mismatch')
    headers = ROOT / 'build/node-headers'
    header_manifest = json.loads((ROOT / 'build/node-headers.json').read_text())
    inventory = {str(path.relative_to(headers)) for path in headers.rglob('*') if path.is_file()}
    if inventory != set(header_manifest['files']) or not {'node.h', 'node_version.h', 'config.gypi'} <= inventory:
        raise RuntimeError('Prepared Node header inventory changed')
    for name, expected in header_manifest['files'].items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or digest(headers.joinpath(*path.parts)) != expected:
            raise RuntimeError('Prepared Node headers changed')
    version = (headers / 'node_version.h').read_text()
    actual = '.'.join(re.search(r'#define NODE_' + part + r'_VERSION\s+(\d+)', version)[1] for part in ['MAJOR', 'MINOR', 'PATCH'])
    if actual != PIN['nodeVersion']:
        raise RuntimeError('Node headers version mismatch')
    variables = ast.literal_eval((headers / 'config.gypi').read_text())['variables']
    if any(variables.get(key) != 0 for key in ['v8_enable_pointer_compression', 'v8_enable_sandbox', 'v8_enable_31bit_smis_on_64bit_arch']):
        raise RuntimeError('Unsupported V8 ABI configuration')
    definitions = ast.literal_eval((source / 'deps/defines.gypi').read_text())['defines']
    if not all(isinstance(value, str) and re.fullmatch('[A-Za-z0-9_=-]+', value) for value in definitions):
        raise RuntimeError('Unexpected SQLite compile definitions')
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    compiler = toolchain / 'bin/x86_64-linux-android26-clang'
    cxx = toolchain / 'bin/x86_64-linux-android26-clang++'
    flags = ['-O3', '-fPIC', '-fvisibility=hidden', '-DNDEBUG']
    sqlite = source / 'deps/sqlite3'
    engine_version = re.search(r'#define SQLITE_VERSION\s+"([^"]+)"', (sqlite / 'sqlite3.h').read_text())[1]
    subprocess.run([str(compiler), '-std=c99', *flags, *['-D' + value for value in definitions],
                    '-c', str(sqlite / 'sqlite3.c'), '-o', str(build / 'sqlite3.o')], check=True)
    addon_source = source / 'src/better_sqlite3.cpp'
    contents = addon_source.read_text()
    entry = 'NODE_MODULE_INIT(/* exports, context */) {'
    if contents.count(entry) != 1:
        raise RuntimeError('Unexpected addon initialization entry')
    contents = contents.replace(entry,
        'extern void MbotInstallFdVfsBinding(v8::Local<v8::Object>, v8::Local<v8::Context>);\n' +
        entry + '\nMbotInstallFdVfsBinding(exports, context);', 1)
    addon_source.write_text(contents)
    adapter_objects = []
    for name in ('fd_vfs.cpp', 'fd_vfs_node.cpp'):
        obj = build / (name + '.o')
        subprocess.run([str(cxx), '-std=c++20', *flags, '-I', str(headers), '-I', str(sqlite),
                        '-c', str(ROOT / 'src/main/cpp' / name), '-o', str(obj)], check=True)
        adapter_objects.append(str(obj))
    subprocess.run([str(cxx), '-std=c++20', *flags, '-DBUILDING_NODE_EXTENSION', '-DNODE_GYP_MODULE_NAME=better_sqlite3',
                    '-I', str(headers), '-I', str(sqlite), '-c', str(source / 'src/better_sqlite3.cpp'),
                    '-o', str(build / 'addon.o')], check=True)
    jni = ROOT / 'build/verified-jni/x86_64'
    library = jni / 'libmbot_sqlite_probe.so'
    subprocess.run([str(cxx), '-shared', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
                    '-Wl,-soname,libmbot_sqlite_probe.so', str(build / 'addon.o'), str(build / 'sqlite3.o'), *adapter_objects,
                    '-L', str(jni), '-lnode', '-lm', '-ldl', '-o', str(library)], check=True)
    wrapper = build / 'wrapper.cjs'
    subprocess.run([str(esbuild), str(source / 'lib/index.js'), '--bundle', '--platform=node', '--format=cjs',
                    '--external:bindings', '--outfile=' + str(wrapper), '--log-level=error'], check=True)
    assets = ROOT / 'build/verified-assets'
    script = assets / 'node-fd-probe.cjs'
    placeholder = '"__MBOT_SQLITE_WRAPPER__"'
    contents = script.read_text()
    if contents.count(placeholder) != 1:
        raise RuntimeError('Prepare the base Node probe before SQLite')
    script.write_text(contents.replace(placeholder, json.dumps(wrapper.read_text())))
    notices = assets / 'third_party/better-sqlite3'
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'LICENSE', notices / 'LICENSE')
    shutil.copyfile(ROOT.parents[1] / 'LICENSE', notices / 'FD-VFS-AGPL-LICENSE')
    (notices / 'SQLITE-PUBLIC-DOMAIN.txt').write_text('\n'.join((sqlite / 'sqlite3.c').read_text().splitlines()[:22]) + '\n')
    metadata = json.loads((assets / 'node-validation.json').read_text())
    metadata.update(requiresSqlite=True, requiresFdVfs=True, betterSqlite3Version=PIN['version'], sqliteEngineVersion=engine_version)
    (assets / 'node-validation.json').write_text(json.dumps(metadata) + '\n')
    (notices / 'build-inputs.json').write_text(json.dumps({
        'pin': PIN, 'librarySha256': digest(library), 'nodeHeaders': header_manifest,
        'defines': definitions, 'modifiedAddonSha256': digest(addon_source),
        'adapters': {name: digest(ROOT / 'src/main/cpp' / name) for name in ('fd_vfs.cpp', 'fd_vfs_node.cpp')},
        'scope': 'verification only; explicit non-sensitive owner-granted data FDs',
    }, indent=2) + '\n')
    print('Prepared pinned better-sqlite3', PIN['version'], 'Android x86_64 verification addon')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--esbuild', type=Path, required=True)
    parser.add_argument('--archive', type=Path, default=ROOT / 'build/downloads/better-sqlite3.tgz')
    args = parser.parse_args()
    prepare(args.ndk.resolve(), args.archive.resolve(), args.esbuild.resolve())
