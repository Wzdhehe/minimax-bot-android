#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Assemble the actual Android Bot product from verified native inputs.

Run the existing Node/full-ICU, SQLite and backup preparations first. This
does not download an engine, accept a substitute ABI or build a DSH rootfs.
The build-time verifier is also invoked by the product Gradle flavor.
"""
import argparse
import ast
import hashlib
import json
import platform
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
DSH = HERE.parent.parent
PROBE = DSH / 'tooling/android-node-probe'
PREPARED = PROBE / 'build'
PIN_COMMIT = '97818b2890b4acae1c6bd1200a226ff506680628'
NODE_LIBRARY = 'libmbot_node_runtime.so'
NATIVE = DSH / 'runtime/native-node'


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def document(path):
    return json.loads(path.read_text(encoding='utf-8'))


def matches(path, expected):
    if not isinstance(expected, str) or len(expected) != 64 or not path.is_file() or sha(path) != expected:
        raise RuntimeError(f'Prepared product input is missing or stale: {path.name}')


def inventory(root):
    return {path.relative_to(root).as_posix(): sha(path) for path in sorted(root.rglob('*')) if path.is_file()}


def repository_path(value):
    result = (DSH / value).resolve()
    if not result.is_relative_to(DSH.resolve()) or result == DSH.resolve():
        raise RuntimeError('Invalid prepared product source path')
    return result


def verify(output, abi):
    summary = document(output / 'product-inputs.json')
    if summary.get('schemaVersion') != 1 or summary.get('abi') != abi or summary.get('pinCommit') != PIN_COMMIT:
        raise RuntimeError('Wrong product ABI or runtime pin')
    for path, digest in summary['sources'].items():
        matches(repository_path(path), digest)
    expected = summary['products']
    # Reject extra assets/libraries as well as changed bytes. No fallback to
    # a stale library or a desktop native addon can enter a flavor unnoticed.
    actual = {**{'assets/' + key: value for key, value in inventory(output / 'assets').items()},
              **{'jniLibs/' + key: value for key, value in inventory(output / 'jniLibs').items()}}
    if actual != expected:
        raise RuntimeError('Prepared product assets or native libraries are stale')
    return summary


def admitted_native(source_sha):
    pin = document(PROBE / 'node-runtime-pin.json')
    verification = document(PREPARED / 'verified-assets/node-validation.json')
    if not isinstance(source_sha, str) or len(source_sha) != 64 or any(c not in '0123456789abcdef' for c in source_sha):
        raise RuntimeError('Provide the archive SHA256 from the authorized full-ICU producer')
    if any(verification.get(key) is not True for key in ('requiresIntl', 'requiresSqlite', 'requiresFdVfs')) or verification.get('sourceArchiveSha256') != source_sha:
        raise RuntimeError('Prepare full-ICU Node, SQLite and FD-VFS together before product assembly')
    info = document(PREPARED / 'verified-assets/third_party/nodejs-mobile/build-info.json')
    if any(info.get('mbotSourceBuild', {}).get(key) != value for key, value in document(PROBE / 'node-source-build.json').items()) or any(info['source'][key] != pin[target] for key, target in [('version', 'version'), ('commit', 'nodeCommit'), ('tree', 'nodeTree')]):
        raise RuntimeError('Node source build does not match the authorized recipe')
    slices = info['slices']
    if len(slices) != 1 or slices[0]['target'] != 'x86_64':
        raise RuntimeError('Only the verified x86_64 full-ICU slice is currently admitted')
    headers = document(PREPARED / 'node-headers.json')
    if headers['nodeArchiveSha256'] != source_sha or headers['files'] != inventory(PREPARED / 'node-headers'):
        raise RuntimeError('Prepared Node headers are stale')
    matches(PREPARED / 'node-headers/config.gypi', slices[0]['config_sha256'])
    config = ast.literal_eval((PREPARED / 'node-headers/config.gypi').read_text(encoding='utf-8'))['variables']
    if config.get('v8_enable_i18n_support') not in (1, True, '1', 'true') or config.get('icu_small') not in (0, False, '0', 'false'):
        raise RuntimeError('Product Node must include full ICU')
    matches(PREPARED / 'verified-jni/x86_64/libnode.so', slices[0]['binary_sha256']['bin/x86_64/libnode.so'])
    sqlite = document(PREPARED / 'verified-assets/third_party/better-sqlite3/build-inputs.json')
    if sqlite['pin'] != document(PROBE / 'sqlite-runtime-pin.json') or sqlite['nodeHeaders'] != headers:
        raise RuntimeError('SQLite was compiled against different Node headers or pin')
    for name, digest in sqlite['adapters'].items():
        if name not in ('fd_vfs.cpp', 'fd_vfs_node.cpp'):
            raise RuntimeError('Unknown FD adapter input')
        matches(PROBE / 'src/main/cpp' / name, digest)
    matches(PREPARED / 'verified-jni/x86_64/libmbot_sqlite_probe.so', sqlite['librarySha256'])
    backup = document(PREPARED / 'verified-backup/backup-inputs.json')
    if backup['ndkRevision'] != pin['ndkRevision'] or backup['archive']['sha256'] != sqlite['pin']['sha256']:
        raise RuntimeError('Backup library uses a different NDK or SQLite source')
    for entry in backup['inputs'].values():
        matches(repository_path(entry['repo_path']), entry['sha256'])
    for name, digest in backup['libraries']['x86_64'].items():
        matches(PREPARED / 'verified-backup/jniLibs/x86_64' / name, digest)
    matches(PREPARED / 'verified-jni/x86_64/libc++_shared.so', backup['libraries']['x86_64']['libc++_shared.so'])
    return pin, headers, backup


def prepare(args):
    # ARM64 needs its own full-ICU source producer and SQLite header inventory.
    # Do not silently ship the no-Intl release just to produce a phone APK.
    if args.abi != 'x86_64':
        raise RuntimeError('ARM64 full-ICU product inputs are not yet admitted')
    output = args.output.resolve() if args.output else HERE / 'build/product' / args.abi
    if output.exists():
        raise RuntimeError('Refusing to overwrite product staging; choose a new output or move the old staging first')
    pin, headers, backup = admitted_native(args.source_build_sha)
    ndk = args.ndk.resolve()
    if f"Pkg.Revision = {pin['ndkRevision']}" not in (ndk / 'source.properties').read_text(encoding='utf-8'):
        raise RuntimeError('Use pinned NDK27d')
    system = platform.system()
    if system not in ('Windows', 'Linux'):
        raise RuntimeError('Only Linux and Windows preparation hosts are supported')
    suffix = '.exe' if system == 'Windows' else ''
    tools = ndk / ('toolchains/llvm/prebuilt/windows-x86_64' if system == 'Windows' else 'toolchains/llvm/prebuilt/linux-x86_64') / 'bin'
    compiler = tools / ('clang++' + suffix)
    if not compiler.is_file():
        raise RuntimeError('Pinned NDK host compiler missing')
    mobile = args.mobile_bundle.resolve()
    manifest = document(mobile / 'mobile-manifest.json')
    if manifest['upstream']['commit'] != PIN_COMMIT or manifest['dependencyLockSha256'] != sha(HERE / 'package-lock.json'):
        raise RuntimeError('Mobile JS bundle uses a different runtime or dependency lock')
    for name, field in [('runtime.mjs', 'runtimeSha256'), ('filesystem.mjs', 'filesystemSha256'), ('resources.json', 'resourcesSha256'), ('THIRD-PARTY-NOTICES.txt', 'noticesSha256')]:
        matches(mobile / name, manifest[field])
    assets = output / 'assets/mbot'
    jni = output / 'jniLibs' / args.abi
    assets.mkdir(parents=True)
    jni.mkdir(parents=True)
    for name in ('runtime.mjs', 'filesystem.mjs', 'resources.json', 'THIRD-PARTY-NOTICES.txt'):
        shutil.copyfile(mobile / name, assets / name)
    shutil.copyfile(NATIVE / 'bootstrap.cjs', assets / 'bootstrap.cjs')
    wrapper = PREPARED / 'sqlite-probe/wrapper.cjs'
    shutil.copyfile(wrapper, assets / 'sqlite-wrapper.cjs')
    for name in ('libnode.so', 'libmbot_sqlite_probe.so', 'libc++_shared.so'):
        shutil.copyfile(PREPARED / 'verified-jni/x86_64' / name, jni / name)
    shutil.copyfile(PREPARED / 'verified-backup/jniLibs/x86_64/libmbot_sqlite_backup.so', jni / 'libmbot_sqlite_backup.so')
    for origin, destination in [(PREPARED / 'verified-assets/third_party', assets / 'third_party'), (PREPARED / 'verified-backup/third_party', assets / 'backup-notices')]:
        shutil.copytree(origin, destination)
    sources = [PROBE / 'src/main/cpp/node_probe.cpp', PROBE / 'src/main/cpp/node_snapshot_bridge.inc',
               NATIVE / 'product_entry.inc', NATIVE / 'synchronous_files.inc', NATIVE / 'bootstrap.cjs',
               HERE / 'prepare-product.py', HERE / 'build.mjs', HERE / 'package.json', HERE / 'package-lock.json', HERE / 'license-overrides.json',
               PROBE / 'node-runtime-pin.json', PROBE / 'node-source-build.json', PROBE / 'sqlite-runtime-pin.json']
    sources += [DSH / 'runtime/minimax-bot-host/mobile-runtime-entry.mjs', DSH / 'runtime/minimax-bot-host/mobile-filesystem.mjs']
    sources += [PROBE / 'src/main/cpp/fd_vfs_node.cpp']
    sources += [repository_path(row['repo_path']) for row in backup['inputs'].values()]
    # Also revalidate JS adapter bytes recorded by the bundler. Dependencies
    # are fixed by npm's integrity lock; record every repository input used.
    for path, digest in manifest['inputs'].items():
        if path.startswith('optional-unavailable:'):
            continue
        origin = (HERE / path).resolve()
        if origin.is_relative_to(DSH.resolve()) and 'node_modules' not in origin.parts:
            matches(origin, digest)
            sources.append(origin)
    expected = {path.relative_to(DSH).as_posix(): sha(path) for path in set(sources)}
    command = [str(compiler), '--target=x86_64-linux-android26', '-DMBOT_PRODUCT_NODE=1', '-std=c++20',
               '-fPIC', '-shared', '-fvisibility=hidden', '-O2', '-Wl,--no-undefined',
               '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', f'-Wl,-soname,{NODE_LIBRARY}',
               '-I', str(PREPARED / 'node-headers'), '-I', str(NATIVE), str(PROBE / 'src/main/cpp/node_probe.cpp'),
               '-L', str(jni), '-lnode', '-o', str(jni / NODE_LIBRARY)]
    subprocess.run(command, check=True)
    product_files = {}
    for name, path in [('bootstrap', 'bootstrap.cjs'), ('runtime', 'runtime.mjs'), ('filesystem', 'filesystem.mjs'),
                       ('resources', 'resources.json'), ('sqlite-wrapper', 'sqlite-wrapper.cjs')]:
        product_files[name] = {'path': path, 'bytes': (assets / path).stat().st_size, 'sha256': sha(assets / path)}
    (assets / 'product-manifest.json').write_text(json.dumps({'schemaVersion': 1, 'abi': args.abi, 'pinCommit': PIN_COMMIT,
                                                          'files': product_files}, indent=2) + '\n', encoding='utf-8')
    summary = {'schemaVersion': 1, 'abi': args.abi, 'pinCommit': PIN_COMMIT, 'sourceArchiveSha256': args.source_build_sha,
               'sources': expected, 'products': {**{'assets/' + key: value for key, value in inventory(output / 'assets').items()},
                                                **{'jniLibs/' + key: value for key, value in inventory(output / 'jniLibs').items()}}}
    (output / 'product-inputs.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    verify(output, args.abi)
    print(f'Prepared actual Bot product assets and isolated Node JNI for {args.abi}; device startup remains unverified')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--abi', choices=('x86_64', 'arm64-v8a'), default='x86_64')
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--mobile-bundle', type=Path)
    parser.add_argument('--source-build-sha')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    if args.verify_only:
        verify(args.output.resolve() if args.output else HERE / 'build/product' / args.abi, args.abi)
        print('Verified exact product source inputs, assets and Android native libraries')
    else:
        if args.ndk is None or args.mobile_bundle is None:
            parser.error('--ndk and --mobile-bundle are required for preparation')
        prepare(args)


if __name__ == '__main__':
    main()
