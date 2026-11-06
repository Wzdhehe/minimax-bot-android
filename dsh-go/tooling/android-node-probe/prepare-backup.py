#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Prepare the Android backup JNI library for the broker verification APK.

This compiles the repository's backup core, its FD-VFS adapter, the JNI
entry points and the pinned SQLite into one shared library per Android ABI.
It changes no repository file: the sources are copied into a snapshot first
and the products land in the ignored build directory.
"""
import argparse
import ast
import hashlib
import importlib.util
import json
import platform
import re
import shutil
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DSH = ROOT.parent.parent
PIN_FILE = ROOT / 'sqlite-runtime-pin.json'
PREPARE_SQLITE = ROOT / 'prepare-sqlite.py'

LIBRARY = 'libmbot_sqlite_backup.so'
OUTPUT = ROOT / 'build/verified-backup'

TARGETS = {
    'x86_64': 'x86_64-linux-android26',
    'arm64-v8a': 'aarch64-linux-android26',
}

JNI_EXPORTS = (
    'Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_run',
    'Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_cancel',
)

REPO_INPUTS = {
    'backup.cpp': DSH / 'runtime/native-sqlite/backup.cpp',
    'backup.h': DSH / 'runtime/native-sqlite/backup.h',
    'fd_backup.cpp': DSH / 'runtime/native-sqlite/fd_backup.cpp',
    'fd_backup.h': DSH / 'runtime/native-sqlite/fd_backup.h',
    'fd_vfs.cpp': ROOT / 'src/main/cpp/fd_vfs.cpp',
    'jni_backup.cpp': DSH / 'runtime/native-sqlite/jni_backup.cpp',
    'sqlite-runtime-pin.json': PIN_FILE,
}

FLAGS = ['-O3', '-fPIC', '-fvisibility=hidden', '-DNDEBUG']


def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()


def digest_file(path):
    return digest_bytes(path.read_bytes())


def ndk_host_tools(ndk, system=None):
    """Select the pinned NDK's host compilers, always targeting Android.

    The Windows executables are used directly: .cmd wrappers need a shell and
    cannot be treated as an executable with arbitrary path quoting. No shell
    is involved and no caller-selected Android target is accepted.
    """
    system = platform.system() if system is None else system
    if system == 'Linux':
        toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
        compiler = [str(toolchain / 'bin/x86_64-linux-android26-clang')]
        cxx = [str(toolchain / 'bin/x86_64-linux-android26-clang++')]
    elif system == 'Windows':
        toolchain = ndk / 'toolchains/llvm/prebuilt/windows-x86_64'
        compiler = [str(toolchain / 'bin/clang.exe')]
        cxx = [str(toolchain / 'bin/clang++.exe')]
    else:
        raise RuntimeError('Verification preparation supports Linux or Windows hosts only')
    runtime = toolchain / 'sysroot/usr/lib/x86_64-linux-android/libc++_shared.so'
    if not Path(compiler[0]).is_file() or not Path(cxx[0]).is_file() or not runtime.is_file():
        raise RuntimeError('Missing pinned NDK compiler or Android x86_64 runtime')
    return toolchain, compiler, cxx


def ndk_tools(ndk, system=None):
    """The readelf and nm of the same NDK, used for the product checks."""
    system = platform.system() if system is None else system
    if system == 'Linux':
        toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    elif system == 'Windows':
        toolchain = ndk / 'toolchains/llvm/prebuilt/windows-x86_64'
    else:
        raise RuntimeError('Verification preparation supports Linux or Windows hosts only')
    suffix = '.exe' if system == 'Windows' else ''
    readelf = toolchain / ('bin/llvm-readelf' + suffix)
    nm = toolchain / ('bin/llvm-nm' + suffix)
    for tool in (readelf, nm):
        if not tool.is_file():
            raise RuntimeError('Missing pinned NDK readelf or nm')
    return readelf, nm


def sqlite_extractor():
    """The repository's own verified extractor, imported read-only."""
    spec = importlib.util.spec_from_file_location('prepare_sqlite_readonly', PREPARE_SQLITE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git_blob_digest(path):
    """The committed LF bytes of a repository file, for comparison only.

    The bytes actually compiled are the working tree bytes, which carry CRLF
    on this host; the Git digest is never presented as the real input.
    """
    try:
        blob = subprocess.check_output(['git', '-C', str(DSH), 'rev-parse', f'HEAD:{path}'],
                                       stderr=subprocess.DEVNULL).decode().strip()
        data = subprocess.check_output(['git', '-C', str(DSH), 'cat-file', 'blob', blob])
    except subprocess.CalledProcessError:
        return None, None
    return blob, digest_bytes(data)


def snapshot_inputs():
    """Copy the repository inputs into an independent snapshot and record them."""
    snapshot = OUTPUT / 'snapshot'
    if snapshot.exists():
        shutil.rmtree(snapshot)
    snapshot.mkdir(parents=True)
    recorded = {}
    for name, source in REPO_INPUTS.items():
        if not source.is_file():
            raise RuntimeError(f'Missing backup source: {source}')
        data = source.read_bytes()
        (snapshot / name).write_bytes(data)
        try:
            relative = source.relative_to(DSH).as_posix()
        except ValueError:
            relative = source.as_posix()
        blob, blob_digest = git_blob_digest(relative)
        recorded[name] = {
            'repo_path': relative,
            'sha256': digest_bytes(data),
            'bytes': len(data),
            'crlf': b'\r\n' in data,
            'git_blob': blob,
            'git_blob_lf_sha256': blob_digest,
        }
    return snapshot, recorded


def defines_from(source):
    """The pinned SQLite compile definitions, validated like prepare-sqlite.py."""
    defines = ast.literal_eval((source / 'deps/defines.gypi').read_text(encoding='utf-8'))['defines']
    if not all(isinstance(value, str) and re.fullmatch('[A-Za-z0-9_=-]+', value) for value in defines):
        raise RuntimeError('Unexpected SQLite compile definitions')
    return defines


def prepare_sqlite_source(archive):
    """Unpack the pinned archive through the repository's verified extractor."""
    module = sqlite_extractor()
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        partial = archive.with_suffix('.partial')
        with urllib.request.urlopen(module.PIN['url'], timeout=180) as source, partial.open('wb') as target:
            shutil.copyfileobj(source, target, 1024 * 1024)
        partial.replace(archive)
    source = OUTPUT / 'sqlite-source'
    if source.exists():
        shutil.rmtree(source)
    module.extract(archive, source)
    defines = defines_from(source)
    return source, defines, module


def run(command):
    result = subprocess.run([str(part) for part in command], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {command[0]}\n{result.stderr.strip()}")
    return result


def compile_abi(abi, triple, snapshot, sqlite_source, defines, ndk, toolchain, compiler, cxx):
    objects = OUTPUT / 'obj' / abi
    if objects.exists():
        shutil.rmtree(objects)
    objects.mkdir(parents=True)
    sqlite_inc = sqlite_source / 'deps/sqlite3'
    sqlite_c = sqlite_inc / 'sqlite3.c'
    product = objects / 'sqlite3.o'
    run([*compiler, f'--target={triple}', '-std=c99', *FLAGS,
         *['-D' + value for value in defines], '-c', sqlite_c, '-o', product])
    for name in ('backup.cpp', 'fd_backup.cpp', 'fd_vfs.cpp', 'jni_backup.cpp'):
        product = objects / (name.replace('.cpp', '.o'))
        run([*cxx, f'--target={triple}', '-std=c++20', *FLAGS, '-fexceptions', '-frtti',
             '-I', snapshot, '-I', sqlite_inc, '-c', snapshot / name, '-o', product])
    jni_dir = OUTPUT / 'jniLibs' / abi
    jni_dir.mkdir(parents=True, exist_ok=True)
    library = jni_dir / LIBRARY
    run([*cxx, f'--target={triple}', '-shared', '--no-undefined', '-Wl,--no-gc-sections',
         '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
         f'-Wl,-soname,{LIBRARY}',
         objects / 'sqlite3.o', objects / 'backup.o', objects / 'fd_backup.o',
         objects / 'fd_vfs.o', objects / 'jni_backup.o',
         '-lc++_shared', '-lc', '-lm', '-ldl', '-o', library])
    # The shared STL the library needs must travel with it.
    runtime = toolchain / 'sysroot/usr/lib/x86_64-linux-android/libc++_shared.so'
    if abi == 'arm64-v8a':
        runtime = toolchain / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'
    if not runtime.is_file():
        raise RuntimeError('Missing pinned NDK libc++_shared runtime')
    shutil.copyfile(runtime, jni_dir / 'libc++_shared.so')
    return library


def verify_library(library, readelf, nm, expected_machine):
    """Check the product: ELF, SONAME, dependencies, page alignment, exports."""
    header = run([readelf, '-h', library]).stdout
    if 'ELF64' not in header or "little endian" not in header or 'DYN (Shared object file)' not in header:
        raise RuntimeError('The backup library is not a 64-bit little-endian shared object')
    if expected_machine not in header:
        raise RuntimeError(f'The backup library is not {expected_machine}')
    dynamic = run([readelf, '-d', library]).stdout
    if f'Library soname: [{LIBRARY}]' not in dynamic:
        raise RuntimeError('The backup library has no expected SONAME')
    needed = re.findall(r'Shared library: \[([^\]]+)\]', dynamic)
    for forbidden in ('libnode.so', 'libpthread.so', 'librt.so', 'libc.so.6', 'libstdc++.so'):
        if forbidden in needed:
            raise RuntimeError(f'The backup library must not depend on {forbidden}')
    for required in ('libc++_shared.so', 'libc.so', 'libm.so', 'libdl.so'):
        if required not in needed:
            raise RuntimeError(f'The backup library must depend on {required}')
    segments = run([readelf, '-lW', library]).stdout
    alignments = []
    for line in segments.splitlines():
        fields = line.split()
        if fields and fields[0] == 'LOAD':
            alignments.append(int(fields[-1], 16))
    if not alignments or min(alignments) < 0x4000:
        raise RuntimeError('The backup library must keep every LOAD segment aligned to 16KiB')
    symbols = run([nm, '--dynamic', '--defined-only', library]).stdout
    for export in JNI_EXPORTS:
        if export not in symbols:
            raise RuntimeError(f'The backup library does not export {export}')
    return {
        'needed': needed,
        'load_alignments': [hex(value) for value in alignments],
        'jni_exports': list(JNI_EXPORTS),
    }


def copy_notices(ndk):
    """The licence texts that travel with the verification product."""
    notices = OUTPUT / 'third_party'
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(DSH / 'LICENSE', notices / 'DSH-AGPL-LICENSE')
    shutil.copyfile(DSH / 'runtime/vendor/minimax-bot/LICENSE', notices / 'minimax-bot-LICENSE')
    source = OUTPUT / 'sqlite-source/deps/sqlite3'
    (notices / 'SQLITE-PUBLIC-DOMAIN.txt').write_text(
        '\n'.join((source / 'sqlite3.c').read_text(encoding='utf-8').splitlines()[:22]) + '\n', encoding='utf-8')
    ndk_notice = ndk / 'NOTICE'
    if ndk_notice.is_file():
        shutil.copyfile(ndk_notice, notices / 'NDK-NOTICE')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--archive', type=Path, default=ROOT / 'build/downloads/better-sqlite3.tgz')
    args = parser.parse_args()
    ndk = args.ndk.resolve()
    revision = (ndk / 'source.properties').read_text(encoding='utf-8')
    if 'Pkg.Revision = 27.3.13750724' not in revision:
        raise RuntimeError('Use the pinned NDK27d')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    snapshot, recorded = snapshot_inputs()
    sqlite_source, defines, module = prepare_sqlite_source(args.archive.resolve())
    toolchain, compiler, cxx = ndk_host_tools(ndk)
    readelf, nm = ndk_tools(ndk)
    machines = {'x86_64': 'X86-64', 'arm64-v8a': 'AArch64'}
    libraries = {}
    checks = {}
    for abi, triple in TARGETS.items():
        library = compile_abi(abi, triple, snapshot, sqlite_source, defines, ndk, toolchain, compiler, cxx)
        checks[abi] = verify_library(library, readelf, nm, machines[abi])
        libraries[abi] = {
            name: digest_file(path) for name, path in
            ((path.name, path) for path in sorted((OUTPUT / 'jniLibs' / abi).iterdir()))
        }
    copy_notices(ndk)
    summary = {
        'ndkRevision': '27.3.13750724',
        'library': LIBRARY,
        'targets': TARGETS,
        'archive': {'sha256': digest_file(args.archive.resolve()), 'pinned': module.PIN['sha256']},
        'inputs': recorded,
        'sqlite': {
            'sqlite3.c': digest_file(sqlite_source / 'deps/sqlite3/sqlite3.c'),
            'sqlite3.h': digest_file(sqlite_source / 'deps/sqlite3/sqlite3.h'),
            'defines.gypi': digest_file(sqlite_source / 'deps/defines.gypi'),
        },
        'defines': defines,
        'libraries': libraries,
        'checks': checks,
    }
    if summary['archive']['sha256'] != summary['archive']['pinned']:
        raise RuntimeError('SQLite source archive digest mismatch')
    (OUTPUT / 'backup-inputs.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print('Prepared', LIBRARY, 'for', ', '.join(TARGETS))


if __name__ == '__main__':
    main()
