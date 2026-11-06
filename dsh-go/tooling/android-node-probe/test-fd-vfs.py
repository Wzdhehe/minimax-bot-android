#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Host prototype only: pinned SQLite, real FDs and separate writer processes."""
import argparse
import ast
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent


def run(archive=None, ndk=None, node_probe=False, runtime_bundle=None):
    if runtime_bundle is not None and not node_probe:
        raise ValueError('--runtime-bundle requires --node-probe')
    spec = importlib.util.spec_from_file_location('sqlite_inputs', ROOT / 'prepare-sqlite.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory(prefix='mbot-fd-vfs-host-') as temporary:
        stage = Path(temporary)
        if archive is None:
            archive = stage / 'sqlite.tgz'
            urllib.request.urlretrieve(module.PIN['url'], archive)
        source = stage / 'source'
        module.extract(archive, source)
        definitions = ast.literal_eval((source / 'deps/defines.gypi').read_text())['defines']
        sqlite = source / 'deps/sqlite3'
        obj = stage / 'sqlite3.o'
        subprocess.run(['cc', '-std=c99', '-O0', '-fPIC', '-fvisibility=hidden', *['-D' + value for value in definitions],
                        '-c', str(sqlite / 'sqlite3.c'), '-o', str(obj)], check=True)
        executable = stage / 'probe'
        adapter = ROOT / 'src/main/cpp/fd_vfs.cpp'
        subprocess.run(['g++', '-std=c++20', '-O0', '-I', str(sqlite), str(adapter), str(ROOT / 'test-fd-vfs.cpp'),
                        str(obj), '-Wl,--wrap=fsync', '-ldl', '-lpthread', '-lm', '-o', str(executable)], check=True)
        subprocess.run([str(executable)], check=True)
        if node_probe:
            # Same verified addon instrumentation, wrapper and Android-prepared
            # headers. Desktop ABI/execution evidence only, never isolation.
            addon_source = module.instrument_addon(source)
            objects = [obj]
            for path in (addon_source, adapter, ROOT / 'src/main/cpp/fd_vfs_node.cpp'):
                output = stage / (path.name + '.o')
                subprocess.run(['g++', '-std=c++20', '-O0', '-fPIC', '-fvisibility=hidden', '-DNDEBUG',
                                '-DBUILDING_NODE_EXTENSION', '-DNODE_GYP_MODULE_NAME=better_sqlite3',
                                '-I', str(ROOT / 'build/node-headers'), '-I', str(sqlite),
                                '-c', str(path), '-o', str(output)], check=True)
                objects.append(output)
            library = stage / 'libmbot_sqlite_probe.so'
            subprocess.run(['g++', '-shared', *map(str, objects), '-ldl', '-lpthread', '-lm', '-o', str(library)], check=True)
            subprocess.run(['node', str(ROOT / 'test-fd-vfs-node.cjs'), str(library),
                            str(ROOT / 'build/sqlite-probe/wrapper.cjs')], check=True)
            if runtime_bundle is not None:
                # Dedicated host-only backing files. Parent never loads SQLite:
                # its descriptor closes cannot disturb the child's POSIX locks.
                data_dir = stage / 'real-runtime'
                data_dir.mkdir(mode=0o700)
                files = [stage / name for name in ('runtime.db', 'runtime.db-wal', 'runtime.db-shm', 'runtime.db-journal')]
                handles = [path.open('w+b') for path in files]
                try:
                    for handle in handles:
                        os.fchmod(handle.fileno(), 0o600)
                        os.fsync(handle.fileno())
                    # Popen pass_fds preserves the original descriptor numbers;
                    # a tiny exec wrapper remaps them without a shell or env Keys.
                    remap = ('import os,sys,fcntl; '
                             'originals=[int(value) for value in sys.argv[1:5]]; '
                             'fds=[fcntl.fcntl(fd, fcntl.F_DUPFD_CLOEXEC, 64) for fd in originals]; '
                             '[os.dup2(fd, 3+i, inheritable=True) for i,fd in enumerate(fds)]; '
                             '[os.close(fd) for fd in {*fds,*originals} if fd not in (3,4,5,6)]; '
                             'os.execvp("node", ["node", *sys.argv[5:]])')
                    for attempt in range(2):
                        subprocess.run([sys.executable, '-c', remap, *[str(handle.fileno()) for handle in handles],
                                        str(ROOT / 'runtime-sqlite-fd-child.mjs'), str(library),
                                        str(ROOT / 'build/sqlite-probe/wrapper.cjs'), str(runtime_bundle.resolve()),
                                        str(data_dir), str(attempt)],
                                       pass_fds=tuple(handle.fileno() for handle in handles),
                                       env={'PATH': os.environ.get('PATH', ''), 'HOME': str(stage)},
                                       check=True, timeout=60)
                    # Metadata allows original legacy schema initialization,
                    # but the v2 migration must still stop without a snapshot.
                    assert files[0].stat().st_size > 0
                    print('PASS: real runtime FD metadata advances startup to the mandatory backup boundary; not Android')
                finally:
                    for handle in handles:
                        handle.close()
        if ndk:
            compiler = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang++'
            subprocess.run([str(compiler), '-std=c++20', '-fPIC', '-O2', '-I', str(sqlite),
                            '-c', str(adapter), '-o', str(stage / 'android-vfs.o')], check=True)
            print('PASS: Android API26/x86_64 VFS object compilation only; not Android execution')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--node-probe', action='store_true', help='Also run the exact SQLite JS block with a host addon; prepare the Node headers/SQLite wrapper first')
    parser.add_argument('--runtime-bundle', type=Path, help='Verify the real runtime refuses startup without admitted filesystem metadata using the host FD addon; first prepare the pinned bundle/dependencies with runtime-host-probe/prepare.mjs')
    args = parser.parse_args()
    run(args.archive, args.ndk, args.node_probe, args.runtime_bundle)
