#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Host acceptance of the native SQLite backup core; never an Android result."""
import argparse
import ast
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import sys

ROOT = Path(__file__).resolve().parent
CORE = ROOT.parents[1] / 'runtime/native-sqlite'


def run(archive=None):
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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path)
    run(parser.parse_args().archive)
