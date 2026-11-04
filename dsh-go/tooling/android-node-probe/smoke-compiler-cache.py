#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Small host/NDK cache and timeout checks before a long source build."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time


def smoke(ndk, ccache):
    with tempfile.TemporaryDirectory(prefix='mbot-cache-check-') as temporary:
        root = Path(temporary)
        source = root / 'fixture.cc'
        source.write_text('// SPDX-License-Identifier: AGPL-3.0-only\n#ifdef __ANDROID__\nstatic_assert(__ANDROID_API__ == 26);\n#endif\nint answer(){return 7;}\n')
        env = {'PATH': os.environ['PATH'], 'CCACHE_DIR': str(root / 'cache'), 'CCACHE_BASEDIR': str(root),
               'CCACHE_COMPILERCHECK': 'content', 'CCACHE_MAXSIZE': '64M'}
        subprocess.run([str(ccache), '--zero-stats'], env=env, check=True, stdout=subprocess.DEVNULL)
        compilers = [shutil.which('clang++') or shutil.which('g++'),
                     str(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang++')]
        for index, compiler in enumerate(compilers):
            if not compiler:
                raise RuntimeError('host_compiler_unavailable')
            objects = []
            for attempt in range(2):
                obj = root / f'{index}-{attempt}.o'
                objects.append(obj)
                subprocess.run([str(ccache), compiler, '-std=c++20', '-O2', '-fPIC', '-c', str(source), '-o', str(obj)], env=env, check=True)
            if hashlib.sha256(objects[0].read_bytes()).digest() != hashlib.sha256(objects[1].read_bytes()).digest():
                raise RuntimeError('cache_object_mismatch')
        stats = subprocess.check_output([str(ccache), '--print-stats'], env=env, text=True)
        counts = {line.split()[0]: int(line.split()[1]) for line in stats.splitlines()
                  if len(line.split()) == 2 and line.split()[1].isdigit()}
        hits = sum(counts.get(key, 0) for key in ('direct_cache_hit', 'preprocessed_cache_hit', 'cache_hit_direct', 'cache_hit_preprocessed'))
        if hits != 2 or counts.get('cache_miss', 0) != 2:
            raise RuntimeError('cache_hit_check_failed')
        print('PASS: real host + NDK API26/x86_64 compiler cache hits=2 misses=2; objects match')
        pidfile = root / 'child.pid'
        # A fixed build fixture, never an Android or user-code execution API.
        parent = '''import subprocess,sys,time,signal
from pathlib import Path
child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])
def stop(*args):
    child.wait(timeout=2)
    sys.exit(0)
signal.signal(signal.SIGTERM,stop)
Path(sys.argv[1]).write_text(str(child.pid))
time.sleep(60)
'''
        result = subprocess.run(['timeout', '--signal=TERM', '--kill-after=3s', '1s', sys.executable, '-c', parent, str(pidfile)],
                                env={'PATH': os.environ['PATH']}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode != 124:
            raise RuntimeError('timeout_exit_check_failed')
        stat = Path('/proc') / pidfile.read_text() / 'stat'
        for _ in range(20):
            if not stat.exists() or stat.read_text().split()[2] == 'Z':
                break
            time.sleep(0.05)
        else:
            raise RuntimeError('build_descendant_active_after_timeout')
        print('PASS: GNU timeout returns 124 and stops the fixture build process group including its child')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--ccache', type=Path, default=shutil.which('ccache'))
    args = parser.parse_args()
    if not args.ccache:
        parser.error('ccache is required')
    smoke(args.ndk.resolve(), args.ccache.resolve())
