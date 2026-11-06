#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Linux I/O + production JNI checks. Never an Android/SQLite/device claim."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parents[1] / 'app/android/app/src/main/cpp'


def run(java_home=None):
    if sys.platform != 'linux':
        raise RuntimeError('Snapshot stage acceptance requires a Linux host')
    if java_home is None:
        compiler = shutil.which('javac')
        java_home = Path(compiler).resolve().parents[1] if compiler else None
    if java_home is None:
        raise RuntimeError('A complete JDK is required for snapshot JNI acceptance')
    java_home = java_home.resolve()
    include = java_home / 'include'
    if not all((java_home / p).is_file() for p in ('bin/java', 'bin/javac', 'include/jni.h', 'include/linux/jni_md.h')):
        raise RuntimeError('A Linux JDK compiler and JNI headers are required')
    with tempfile.TemporaryDirectory(prefix='mbot-snapshot-native-build-') as temporary:
        stage = Path(temporary)
        probe = stage / 'snapshot-stages'
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-O0', '-I', str(SOURCE),
                        str(ROOT / 'test-snapshot-stages.cpp'), '-Wl,--wrap=fsync', '-o', str(probe)], check=True)
        subprocess.run([str(probe)], check=True, timeout=15)
        library = stage / 'libmbot_runtime_files.so'
        subprocess.run(['g++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-fPIC', '-fvisibility=hidden',
                        '-shared', '-I', str(include), '-I', str(include / 'linux'),
                        str(SOURCE / 'runtime_publication.cpp'), '-Wl,--no-undefined', '-o', str(library)], check=True)
        classes = stage / 'classes'; classes.mkdir()
        subprocess.run([str(java_home / 'bin/javac'), '-d', str(classes), str(ROOT / 'NativeRuntimePublication.java')], check=True)
        directory = stage / 'private'; directory.mkdir(mode=0o700)
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
        try:
            subprocess.run([str(java_home / 'bin/java'), '--add-opens=java.base/java.io=ALL-UNNAMED',
                            '-cp', str(classes), 'io.github.ccch1mneyyy.dsh_app.isolation.NativeRuntimePublication',
                            str(library), str(fd), str(directory)], pass_fds=(fd,), check=True, timeout=15)
        finally:
            os.close(fd)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', type=Path, default=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
    run(parser.parse_args().java_home)
