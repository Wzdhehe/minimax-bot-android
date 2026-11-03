#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Host prototype only: pinned SQLite, real FDs and separate writer processes."""
import argparse
import ast
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent


def run(archive=None, ndk=None):
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
        subprocess.run(['cc', '-std=c99', '-O0', *['-D' + value for value in definitions],
                        '-c', str(sqlite / 'sqlite3.c'), '-o', str(obj)], check=True)
        executable = stage / 'probe'
        adapter = ROOT / 'src/main/cpp/fd_vfs.cpp'
        subprocess.run(['g++', '-std=c++20', '-O0', '-I', str(sqlite), str(adapter), str(ROOT / 'test-fd-vfs.cpp'),
                        str(obj), '-ldl', '-lpthread', '-lm', '-o', str(executable)], check=True)
        subprocess.run([str(executable)], check=True)
        if ndk:
            compiler = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang++'
            subprocess.run([str(compiler), '-std=c++20', '-fPIC', '-O2', '-I', str(sqlite),
                            '-c', str(adapter), '-o', str(stage / 'android-vfs.o')], check=True)
            print('PASS: Android API26/x86_64 VFS object compilation only; not Android execution')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--ndk', type=Path)
    args = parser.parse_args()
    run(args.archive, args.ndk)
