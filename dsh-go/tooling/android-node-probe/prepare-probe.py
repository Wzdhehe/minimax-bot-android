#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Prepare a pinned, verification-only Android Node/JNI target, never production."""
import argparse
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
DSH = ROOT.parent.parent
PIN = json.loads((ROOT / 'node-runtime-pin.json').read_text())


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def bundle():
    host = DSH / 'runtime/minimax-bot-host'
    broker = (host / 'model-broker.mjs').read_text()
    fd = (host / 'model-fd-client.mjs').read_text()
    specifier = "'./model-broker.mjs'"
    assert fd.count(specifier) == 1
    broker_url = 'data:text/javascript;base64,' + base64.b64encode(broker.encode()).decode()
    fd = fd.replace(specifier, json.dumps(broker_url))
    module_url = 'data:text/javascript;base64,' + base64.b64encode(fd.encode()).decode()
    template = (ROOT / 'src/main/js/node-fd-probe.cjs').read_text()
    placeholder = '"__MBOT_FD_MODULE_URL__"'
    assert template.count(placeholder) == 1
    assets = ROOT / 'build/verified-assets'
    assets.mkdir(parents=True, exist_ok=True)
    (assets / 'node-fd-probe.cjs').write_text(template.replace(placeholder, json.dumps(module_url)))


def prepare(ndk, archive):
    if not archive.is_file():
        archive.parent.mkdir(parents=True, exist_ok=True)
        partial = archive.with_suffix('.partial')
        with urllib.request.urlopen(PIN['url'], timeout=180) as source, partial.open('wb') as output:
            shutil.copyfileobj(source, output, 1024 * 1024)
        partial.replace(archive)
    if digest(archive) != PIN['sha256']:
        raise RuntimeError('Node archive digest mismatch')
    revision = (ndk / 'source.properties').read_text()
    if f"Pkg.Revision = {PIN['ndkRevision']}" not in revision:
        raise RuntimeError('Use the pinned NDK revision')
    source_dir = ROOT / 'build/node-headers'
    jni = ROOT / 'build/verified-jni' / PIN['abi']
    notices = ROOT / 'build/verified-assets/third_party'
    source_dir.mkdir(parents=True, exist_ok=True)
    jni.mkdir(parents=True, exist_ok=True)
    total = 0
    with zipfile.ZipFile(archive) as package:
        metadata = json.loads(package.read('build-info.json'))
        assert metadata['source']['commit'] == PIN['nodeCommit']
        assert metadata['source']['tree'] == PIN['nodeTree']
        assert metadata['source']['version'] == PIN['version']
        for info in package.infolist():
            path = PurePosixPath(info.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in info.filename or stat.S_ISLNK(info.external_attr >> 16):
                raise RuntimeError('Unsafe archive member')
            if info.is_dir():
                continue
            if info.filename.startswith('include/node/'):
                destination = source_dir.joinpath(*path.parts[2:])
            elif info.filename == f"bin/{PIN['abi']}/libnode.so":
                destination = jni / 'libnode.so'
            elif info.filename in {'LICENSE', 'LICENSE-nodejs-mobile', 'build-info.json'}:
                destination = notices / 'nodejs-mobile' / info.filename
            else:
                continue
            total += info.file_size
            if info.file_size > 128 * 1024 * 1024 or total > 256 * 1024 * 1024:
                raise RuntimeError('Archive extraction budget exceeded')
            destination.parent.mkdir(parents=True, exist_ok=True)
            with package.open(info) as source, destination.open('wb') as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
    assert digest(jni / 'libnode.so') == PIN['binarySha256']
    toolchain = ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
    shutil.copyfile(toolchain / 'sysroot/usr/lib/x86_64-linux-android/libc++_shared.so', jni / 'libc++_shared.so')
    (notices / 'ndk').mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ndk / 'NOTICE', notices / 'ndk/NOTICE')
    command = [str(toolchain / 'bin/x86_64-linux-android26-clang++'), '-std=c++20', '-fPIC', '-shared',
               '-fvisibility=hidden', '-O2', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
               '-I', str(source_dir), str(ROOT / 'src/main/cpp/node_probe.cpp'),
               '-L', str(jni), '-lnode', '-o', str(jni / 'libmbot_node_probe.so')]
    subprocess.run(command, check=True)
    bundle()
    print('Prepared pinned Node', PIN['version'], PIN['abi'], 'and JNI verification glue')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--archive', type=Path, default=ROOT / 'build/downloads/nodejs-mobile.zip')
    parser.add_argument('--bundle-only', action='store_true')
    args = parser.parse_args()
    if args.bundle_only:
        bundle()
    elif not args.ndk:
        parser.error('--ndk is required for native preparation')
    else:
        prepare(args.ndk.resolve(), args.archive.resolve())
