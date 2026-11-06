#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Host acceptance of the native SQLite backup core; never an Android result."""
import argparse
import ast
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import sys

ROOT = Path(__file__).resolve().parent
CORE = ROOT.parents[1] / 'runtime/native-sqlite'


def jni_acceptance(stage, sqlite, obj, grants, java_home):
    if java_home is None:
        compiler = shutil.which('javac')
        java_home = Path(compiler).resolve().parents[1] if compiler else None
    if java_home is None:
        raise RuntimeError('A complete JDK is required for native backup JNI acceptance')
    java_home = java_home.resolve()
    javac, java = java_home / 'bin/javac', java_home / 'bin/java'
    include = java_home / 'include'
    if not all(path.is_file() for path in (javac, java, include / 'jni.h', include / 'linux/jni_md.h')):
        raise RuntimeError('A Linux JDK with compiler and JNI headers is required')
    library = stage / 'libmbot_sqlite_backup.so'
    subprocess.run(['g++', '-shared', '-std=c++20', '-O0', '-Wall', '-Wextra', '-Werror',
                    '-fPIC', '-fvisibility=hidden', '-I', str(sqlite), '-I', str(CORE),
                    '-I', str(include), '-I', str(include / 'linux'), str(CORE / 'backup.cpp'),
                    str(CORE / 'fd_backup.cpp'), str(CORE / 'jni_backup.cpp'),
                    str(ROOT / 'src/main/cpp/fd_vfs.cpp'), str(obj), '-ldl', '-lpthread', '-lm',
                    '-Wl,--no-undefined', '-o', str(library)], check=True)
    classes = stage / 'classes'
    classes.mkdir()
    subprocess.run([str(javac), '-d', str(classes), str(ROOT / 'NativeSqliteBackupBindings.java')], check=True)
    directory = stage / 'jni-files'
    directory.mkdir(mode=0o700)
    paths = [directory / name for name in ('state.db', 'state.db-wal', 'state.db-shm', 'state.db-journal',
                                         'snapshot.db', 'snapshot.db-wal', 'snapshot.db-shm', 'snapshot.db-journal')]
    fds = []
    try:
        for path in paths:
            fds.append(os.open(path, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600))
        arguments = [str(fd) for fd in fds]
        subprocess.run([str(grants), 'seed', *arguments], pass_fds=fds, check=True, timeout=10)
        # The Python parent never opens SQLite. Each JVM is a fresh one-shot
        # worker, retired before any subsequent verifier touches that family.
        for mode in ('shape', 'null', 'uid', 'pid', 'cancel', 'copy'):
            subprocess.run([str(java), '-cp', str(classes),
                            'io.github.ccch1mneyyy.dsh_app.isolation.NativeSqliteBackupBindings',
                            str(library), mode, str(os.getuid()), str(os.getpid()), *arguments],
                           pass_fds=fds, check=True, timeout=15)
            if mode != 'copy' and any(path.stat().st_size for path in paths[4:]):
                raise AssertionError('Refused JNI call wrote a snapshot')
        subprocess.run([str(grants), 'verify', *arguments], pass_fds=fds, check=True, timeout=10)
        print('PASS: actual JVM/JNI backup over eight inherited FDs, malformed input, '
              'owner-PID refusal, one-way cancellation, single invocation and independent reopen; host only', flush=True)
    finally:
        for fd in fds:
            os.close(fd)


def run(archive=None, java_home=None):
    if sys.platform != 'linux':
        raise RuntimeError('Native backup acceptance requires a Linux host; this is not an Android runner')
    spec = importlib.util.spec_from_file_location('sqlite_backup_inputs', ROOT / 'prepare-sqlite.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    with tempfile.TemporaryDirectory(prefix='mbot-native-backup-build-') as temporary:
        stage = Path(temporary)
        if archive is None:
            archive = stage / 'sqlite.tgz'
            urllib.request.urlretrieve(module.PIN['url'], archive)
        source = stage / 'source'
        module.extract(archive, source)
        definitions = ast.literal_eval((source / 'deps/defines.gypi').read_text(encoding='utf-8'))['defines']
        sqlite = source / 'deps/sqlite3'
        obj = stage / 'sqlite3.o'
        subprocess.run(['cc', '-std=c99', '-O0', '-fPIC', '-fvisibility=hidden',
                        *['-D' + value for value in definitions], '-c', str(sqlite / 'sqlite3.c'), '-o', str(obj)], check=True)
        executable = stage / 'backup-probe'
        subprocess.run(['g++', '-std=c++20', '-O0', '-Wall', '-Wextra', '-I', str(sqlite), '-I', str(CORE),
                        str(CORE / 'backup.cpp'), str(ROOT / 'src/main/cpp/fd_vfs.cpp'),
                        str(ROOT / 'test-sqlite-backup.cpp'), str(obj), '-ldl', '-lpthread', '-lm',
                        '-o', str(executable)], check=True)
        subprocess.run([str(executable)], check=True, timeout=30)
        grants = stage / 'backup-grants'
        subprocess.run(['g++', '-std=c++20', '-O0', '-Wall', '-Wextra', '-I', str(sqlite), '-I', str(CORE),
                        str(CORE / 'backup.cpp'), str(CORE / 'fd_backup.cpp'), str(ROOT / 'src/main/cpp/fd_vfs.cpp'),
                        str(ROOT / 'test-sqlite-backup-fds.cpp'), str(obj), '-ldl', '-lpthread', '-lm',
                        '-Wl,--wrap=fsync,--wrap=fstat,--wrap=sqlite3_close,--wrap=sqlite3_prepare_v2',
                        '-o', str(grants)], check=True)
        subprocess.run([str(grants)], check=True, timeout=30)
        jni_acceptance(stage, sqlite, obj, grants, java_home)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--java-home', type=Path, default=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    args = parser.parse_args()
    run(args.archive, args.java_home)
