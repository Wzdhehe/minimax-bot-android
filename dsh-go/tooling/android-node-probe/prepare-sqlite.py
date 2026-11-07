#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build the pinned SQLite addon for the isolated verification APK, not production."""
import argparse
import ast
import hashlib
import json
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
PIN = json.loads((ROOT / 'sqlite-runtime-pin.json').read_text(encoding='utf-8'))
TARGETS = {
    'x86_64': ('x86_64-linux-android', 'x64', 62),
    'arm64-v8a': ('aarch64-linux-android', 'arm64', 183),
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def target(abi):
    if abi not in TARGETS:
        raise RuntimeError('Unsupported Android SQLite ABI')
    return TARGETS[abi]


def prepared_root(abi):
    target(abi)
    # Keep the verified x64 probe/product inputs at their existing locations.
    # ARM64 inputs and outputs never replace those files.
    return ROOT / 'build' if abi == 'x86_64' else ROOT / 'build/abi' / abi


def require_target_elf(path, abi):
    """An ABI guard, not a replacement for source/Android provenance checks."""
    with path.open('rb') as stream:
        header = stream.read(64)
    if (len(header) != 64 or header[:7] != b'\x7fELF\x02\x01\x01'
            or int.from_bytes(header[16:18], 'little') != 3
            or int.from_bytes(header[18:20], 'little') != target(abi)[2]):
        raise RuntimeError('Expected shared ELF64 library for Android SQLite ABI ' + abi)


def ndk_host_tools(ndk, system=None, abi='x86_64'):
    """Select one of two fixed Android26 targets from the pinned host NDK.

    Use the Windows executables directly: .cmd wrappers require shell handling
    and cannot safely be treated as an executable with arbitrary path quoting.
    No shell is involved, and no caller-selected Android target is accepted.
    """
    triple = target(abi)[0]
    system = platform.system() if system is None else system
    if system == 'Linux':
        toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
        compiler = [str(toolchain / 'bin' / (triple + '26-clang'))]
        cxx = [str(toolchain / 'bin' / (triple + '26-clang++'))]
    elif system == 'Windows':
        toolchain = ndk / 'toolchains/llvm/prebuilt/windows-x86_64'
        compiler = [str(toolchain / 'bin/clang.exe'), '--target=' + triple + '26']
        cxx = [str(toolchain / 'bin/clang++.exe'), '--target=' + triple + '26']
    else:
        raise RuntimeError('Verification preparation supports Linux or Windows hosts only')
    runtime = toolchain / 'sysroot/usr/lib' / triple / 'libc++_shared.so'
    if not Path(compiler[0]).is_file() or not Path(cxx[0]).is_file() or not runtime.is_file():
        raise RuntimeError('Missing pinned NDK compiler or Android ' + abi + ' runtime')
    return toolchain, compiler, cxx


def esbuild_executable(esbuild):
    """The real esbuild program, never a command wrapper.

    npm installs esbuild as a .cmd wrapper on Windows; running one needs a
    shell, so the wrapper is refused instead of being worked around.
    """
    if esbuild.suffix.lower() in {'.cmd', '.bat'}:
        raise RuntimeError('Expected the real esbuild executable, not a command wrapper')
    return esbuild


def header_inventory(headers):
    """The prepared Node headers as forward-slash relative paths.

    The paths are compared with the manifest, which names them with forward
    slashes on every host.
    """
    return {path.relative_to(headers).as_posix() for path in headers.rglob('*') if path.is_file()}


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


def instrument_addon(source):
    addon_source = source / 'src/better_sqlite3.cpp'
    contents = addon_source.read_text(encoding='utf-8')
    entry = 'NODE_MODULE_INIT(/* exports, context */) {'
    if contents.count(entry) != 1:
        raise RuntimeError('Unexpected addon initialization entry')
    addon_source.write_text(contents.replace(entry,
        'extern void MbotInstallFdVfsBinding(v8::Local<v8::Object>, v8::Local<v8::Context>);\n' +
        entry + '\nMbotInstallFdVfsBinding(exports, context);', 1), encoding='utf-8')
    return addon_source


def prepare(ndk, archive, esbuild, abi='x86_64'):
    prepared = prepared_root(abi)
    if PIN['ndkVersion'] not in (ndk / 'source.properties').read_text(encoding='utf-8'):
        raise RuntimeError('Expected pinned NDK27d')
    esbuild = esbuild_executable(esbuild)
    if subprocess.check_output([str(esbuild), '--version'], text=True, encoding='utf-8').strip() != PIN['esbuildVersion']:
        raise RuntimeError('Expected pinned esbuild')
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        temporary = archive.with_suffix('.partial')
        urllib.request.urlretrieve(PIN['url'], temporary)
        temporary.replace(archive)
    build = prepared / 'sqlite-probe'
    if build.exists():
        shutil.rmtree(build)
    source = build / 'source'
    extract(archive, source)
    package = json.loads((source / 'package.json').read_text(encoding='utf-8'))
    if package['name'] != PIN['package'] or package['version'] != PIN['version']:
        raise RuntimeError('SQLite package identity mismatch')
    headers = prepared / 'node-headers'
    header_manifest = json.loads((prepared / 'node-headers.json').read_text(encoding='utf-8'))
    # The inventory is compared by forward-slash relative paths on every host.
    inventory = header_inventory(headers)
    if inventory != set(header_manifest['files']) or not {'node.h', 'node_version.h', 'config.gypi'} <= inventory:
        raise RuntimeError('Prepared Node header inventory changed')
    for name, expected in header_manifest['files'].items():
        path = PurePosixPath(name)
        if path.is_absolute() or '..' in path.parts or digest(headers.joinpath(*path.parts)) != expected:
            raise RuntimeError('Prepared Node headers changed')
    version = (headers / 'node_version.h').read_text(encoding='utf-8')
    actual = '.'.join(re.search(r'#define NODE_' + part + r'_VERSION\s+(\d+)', version)[1] for part in ['MAJOR', 'MINOR', 'PATCH'])
    if actual != PIN['nodeVersion']:
        raise RuntimeError('Node headers version mismatch')
    variables = ast.literal_eval((headers / 'config.gypi').read_text(encoding='utf-8'))['variables']
    if any(variables.get(key) != 0 for key in ['v8_enable_pointer_compression', 'v8_enable_sandbox', 'v8_enable_31bit_smis_on_64bit_arch']):
        raise RuntimeError('Unsupported V8 ABI configuration')
    if variables.get('OS') != 'android' or variables.get('target_arch') != target(abi)[1]:
        raise RuntimeError('Prepared Node headers do not match Android ABI ' + abi)
    jni = prepared / 'verified-jni' / abi
    require_target_elf(jni / 'libnode.so', abi)
    definitions = ast.literal_eval((source / 'deps/defines.gypi').read_text(encoding='utf-8'))['defines']
    if not all(isinstance(value, str) and re.fullmatch('[A-Za-z0-9_=-]+', value) for value in definitions):
        raise RuntimeError('Unexpected SQLite compile definitions')
    # The host compilers are picked after every input was verified, so a
    # changed input is still rejected before anything is compiled.
    toolchain, compiler, cxx = ndk_host_tools(ndk, abi=abi)
    flags = ['-O3', '-fPIC', '-fvisibility=hidden', '-DNDEBUG']
    sqlite = source / 'deps/sqlite3'
    engine_version = re.search(r'#define SQLITE_VERSION\s+"([^"]+)"', (sqlite / 'sqlite3.h').read_text(encoding='utf-8'))[1]
    subprocess.run([*compiler, '-std=c99', *flags, *['-D' + value for value in definitions],
                    '-c', str(sqlite / 'sqlite3.c'), '-o', str(build / 'sqlite3.o')], check=True)
    addon_source = instrument_addon(source)
    adapter_objects = []
    for name in ('fd_vfs.cpp', 'fd_vfs_node.cpp'):
        obj = build / (name + '.o')
        subprocess.run([*cxx, '-std=c++20', *flags, '-I', str(headers), '-I', str(sqlite),
                        '-c', str(ROOT / 'src/main/cpp' / name), '-o', str(obj)], check=True)
        adapter_objects.append(str(obj))
    subprocess.run([*cxx, '-std=c++20', *flags, '-DBUILDING_NODE_EXTENSION', '-DNODE_GYP_MODULE_NAME=better_sqlite3',
                    '-I', str(headers), '-I', str(sqlite), '-c', str(source / 'src/better_sqlite3.cpp'),
                    '-o', str(build / 'addon.o')], check=True)
    library = jni / 'libmbot_sqlite_probe.so'
    subprocess.run([*cxx, '-shared', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
                    '-Wl,-soname,libmbot_sqlite_probe.so', str(build / 'addon.o'), str(build / 'sqlite3.o'), *adapter_objects,
                    '-L', str(jni), '-lnode', '-lm', '-ldl', '-o', str(library)], check=True)
    require_target_elf(library, abi)
    wrapper = build / 'wrapper.cjs'
    subprocess.run([str(esbuild), str(source / 'lib/index.js'), '--bundle', '--platform=node', '--format=cjs',
                    '--external:bindings', '--outfile=' + str(wrapper), '--log-level=error'], check=True)
    assets = prepared / 'verified-assets'
    script = assets / 'node-fd-probe.cjs'
    placeholder = '"__MBOT_SQLITE_WRAPPER__"'
    contents = script.read_text(encoding='utf-8')
    if contents.count(placeholder) != 1:
        raise RuntimeError('Prepare the base Node probe before SQLite')
    script.write_text(contents.replace(placeholder, json.dumps(wrapper.read_text(encoding='utf-8'))), encoding='utf-8')
    notices = assets / 'third_party/better-sqlite3'
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'LICENSE', notices / 'LICENSE')
    shutil.copyfile(ROOT.parents[1] / 'LICENSE', notices / 'FD-VFS-AGPL-LICENSE')
    (notices / 'SQLITE-PUBLIC-DOMAIN.txt').write_text(
        '\n'.join((sqlite / 'sqlite3.c').read_text(encoding='utf-8').splitlines()[:22]) + '\n', encoding='utf-8')
    metadata = json.loads((assets / 'node-validation.json').read_text(encoding='utf-8'))
    metadata.update(requiresSqlite=True, requiresFdVfs=True, betterSqlite3Version=PIN['version'], sqliteEngineVersion=engine_version)
    (assets / 'node-validation.json').write_text(json.dumps(metadata) + '\n', encoding='utf-8')
    (notices / 'build-inputs.json').write_text(json.dumps({
        'abi': abi, 'pin': PIN, 'librarySha256': digest(library), 'nodeHeaders': header_manifest,
        'defines': definitions, 'modifiedAddonSha256': digest(addon_source),
        'adapters': {name: digest(ROOT / 'src/main/cpp' / name) for name in ('fd_vfs.cpp', 'fd_vfs_node.cpp')},
        'scope': 'verification only; explicit non-sensitive owner-granted data FDs',
    }, indent=2) + '\n', encoding='utf-8')
    print('Prepared pinned better-sqlite3', PIN['version'], 'Android', abi, 'verification addon')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--esbuild', type=Path, required=True)
    parser.add_argument('--abi', choices=tuple(TARGETS), default='x86_64')
    parser.add_argument('--archive', type=Path, default=ROOT / 'build/downloads/better-sqlite3.tgz')
    args = parser.parse_args()
    prepare(args.ndk.resolve(), args.archive.resolve(), args.esbuild.resolve(), args.abi)
