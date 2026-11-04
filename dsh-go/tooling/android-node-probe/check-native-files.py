#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build the exact production file kernel for both ABIs; no device claim."""
import argparse
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parents[1] / 'app/android/app/src/main/cpp'


def check(ndk, cmake, output):
    if 'Pkg.Revision = 27.3.13750724' not in (ndk / 'source.properties').read_text():
        raise RuntimeError('native_files_ndk_revision_invalid')
    version = subprocess.check_output([str(cmake), '--version'], text=True)
    if version.splitlines()[0] not in ('cmake version 3.22.1', 'cmake version 3.22.1-g37088a8'):
        raise RuntimeError('native_files_cmake_revision_invalid')
    readelf = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    for abi, machine in [('x86_64', 62), ('arm64-v8a', 183)]:
        build = output / abi
        subprocess.run([str(cmake), '-S', str(SOURCE), '-B', str(build), '-G', 'Ninja',
            '-DCMAKE_MAKE_PROGRAM=' + str(cmake.with_name('ninja')),
            '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
            '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26',
            '-DANDROID_STL=c++_static', '-DCMAKE_BUILD_TYPE=Release'], check=True)
        subprocess.run([str(cmake), '--build', str(build)], check=True)
        library = build / 'libmbot_runtime_files.so'
        data = library.read_bytes()
        if data[:6] != b'\x7fELF\x02\x01':
            raise RuntimeError('native_files_elf_format_invalid')
        header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
        if header[2] != machine or header[9] != 56:
            raise RuntimeError('native_files_elf_abi_invalid')
        loads = [struct.unpack_from('<IIQQQQQQ', data, header[5] + i * header[9])
                 for i in range(header[10])]
        loads = [p for p in loads if p[0] == 1]
        if not loads or any(p[7] < 16384 or p[2] % p[7] != p[3] % p[7] for p in loads):
            raise RuntimeError('native_files_page_alignment_invalid')
        symbols = subprocess.check_output([str(readelf), '--dyn-syms', str(library)], text=True)
        if not any(' GLOBAL ' in line and ' UND ' not in line and line.rstrip().endswith('JNI_OnLoad') for line in symbols.splitlines()):
            raise RuntimeError('native_files_jni_export_missing')
        dynamic = subprocess.check_output([str(readelf), '--dynamic', str(library)], text=True)
        if 'libc++_shared.so' in dynamic:
            raise RuntimeError('native_files_unexpected_shared_stl')
        print('PASS: production native file kernel ' + abi + ' API26, JNI export and 16KiB LOAD alignment; not device execution')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--cmake', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/native-files-check')
    args = parser.parse_args()
    check(args.ndk.resolve(), args.cmake.resolve(), args.output.resolve())
