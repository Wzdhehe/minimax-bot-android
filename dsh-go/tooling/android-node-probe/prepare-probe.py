#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Prepare a pinned, verification-only Android Node/JNI target, never production."""
import argparse
import ast
import base64
import hashlib
import json
import re
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


def bundle(output_dir=None):
    host = DSH / 'runtime/minimax-bot-host'
    urls = {}
    dependencies = {
        'model-broker.mjs': [],
        'model-fd-client.mjs': ['model-broker.mjs'],
        'model-sdk-fetch.mjs': ['model-broker.mjs', 'model-fd-client.mjs'],
        'native-model-route.mjs': ['model-broker.mjs', 'model-sdk-fetch.mjs'],
    }
    def module_url(name):
        if name not in urls:
            text = (host / name).read_text()
            for dependency in dependencies[name]:
                specifier = "'./" + dependency + "'"
                assert text.count(specifier) == 1
                text = text.replace(specifier, json.dumps(module_url(dependency)))
            urls[name] = 'data:text/javascript;base64,' + base64.b64encode(text.encode()).decode()
        return urls[name]
    template = (ROOT / 'src/main/js/node-fd-probe.cjs').read_text()
    placeholder = '"__MBOT_FD_MODULE_URL__"'
    assert template.count(placeholder) == 1
    assets = output_dir or ROOT / 'build/verified-assets'
    assets.mkdir(parents=True, exist_ok=True)
    template = template.replace(placeholder, json.dumps(module_url('model-fd-client.mjs')))
    route_placeholder = '"__MBOT_NATIVE_ROUTE_MODULE_URL__"'
    assert template.count(route_placeholder) == 1
    template = template.replace(route_placeholder, json.dumps(module_url('native-model-route.mjs')))
    (assets / 'node-fd-probe.cjs').write_text(template)


def prepare(ndk, archive, source_sha=None):
    if source_sha is not None and not re.fullmatch('[0-9a-f]{64}', source_sha):
        raise RuntimeError('Expected the trusted build job archive SHA256')
    if not archive.is_file():
        if source_sha is not None:
            raise RuntimeError('Source build archive is missing')
        archive.parent.mkdir(parents=True, exist_ok=True)
        partial = archive.with_suffix('.partial')
        with urllib.request.urlopen(PIN['url'], timeout=180) as source, partial.open('wb') as output:
            shutil.copyfileobj(source, output, 1024 * 1024)
        partial.replace(archive)
    if digest(archive) != (source_sha or PIN['sha256']):
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
    binary_sha = PIN['binarySha256']
    config_sha = None
    with zipfile.ZipFile(archive) as package:
        metadata = json.loads(package.read('build-info.json'))
        assert metadata['source']['commit'] == PIN['nodeCommit']
        assert metadata['source']['tree'] == PIN['nodeTree']
        assert metadata['source']['version'] == PIN['version']
        if source_sha is not None:
            source_pin = json.loads((ROOT / 'node-source-build.json').read_text())
            build = metadata.get('mbotSourceBuild', {})
            if any(build.get(key) != value for key, value in source_pin.items()):
                raise RuntimeError('Unexpected source build recipe')
            slices = metadata['slices']
            if len(slices) != 1 or slices[0]['target'] != PIN['abi']:
                raise RuntimeError('Unexpected source build ABI')
            binary_sha = slices[0]['binary_sha256'][f"bin/{PIN['abi']}/libnode.so"]
            config_sha = slices[0]['config_sha256']
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
    assert digest(jni / 'libnode.so') == binary_sha
    if source_sha is not None:
        assert digest(source_dir / 'config.gypi') == config_sha
        variables = ast.literal_eval((source_dir / 'config.gypi').read_text())['variables']
        if variables.get('v8_enable_i18n_support') not in (1, True, '1', 'true') or variables.get('icu_small') not in (0, False, '0', 'false'):
            raise RuntimeError('Source build must include full ICU')
    (ROOT / 'build/node-headers.json').write_text(json.dumps({
        'nodeArchiveSha256': digest(archive),
        'files': {str(path.relative_to(source_dir)): digest(path) for path in sorted(source_dir.rglob('*')) if path.is_file()},
    }) + '\n')
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
    (ROOT / 'build/verified-assets/node-validation.json').write_text(json.dumps({
        'requiresIntl': source_sha is not None, 'sourceArchiveSha256': source_sha, 'requiresSqlite': False,
    }) + '\n')
    print('Prepared pinned Node', PIN['version'], PIN['abi'], 'and JNI verification glue')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--archive', type=Path, default=ROOT / 'build/downloads/nodejs-mobile.zip')
    parser.add_argument('--bundle-output', type=Path)
    parser.add_argument('--bundle-only', action='store_true')
    parser.add_argument('--source-build-sha', help='Trusted full-ICU build job SHA256; not a runtime override')
    args = parser.parse_args()
    if args.bundle_only:
        bundle(args.bundle_output)
    elif not args.ndk:
        parser.error('--ndk is required for native preparation')
    else:
        prepare(args.ndk.resolve(), args.archive.resolve(), args.source_build_sha)
