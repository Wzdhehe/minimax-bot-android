#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Produce an ARM64 full-ICU candidate without changing x86_64 authority.

Linux x86_64 host; pinned NDK27d; clang/clang++, ninja and ccache on PATH.
Use --check-only for source/toolchain admission without starting compilation.
The output is NOT automatically admitted by prepare-product.py: its archive
digest, independent provenance and ARM64 consumers still require verification.
"""
import argparse
import ast
import hashlib
import importlib.util
import json
import os
import platform
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
REPOSITORY = ROOT.parents[2]
PIN_FILE = ROOT / 'node-arm64-source-build.json'
PIN = json.loads(PIN_FILE.read_text(encoding='utf-8'))
spec = importlib.util.spec_from_file_location('readonly_x64_source_recipe', ROOT / 'build-source.py')
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
PRODUCER_INPUTS = (PIN_FILE, Path(__file__).resolve(), ROOT / 'build-source.py', ROOT / 'node-source-build.json')


def check_pins():
    # Pure recipe transforms are shared, not their mutable configuration or
    # output directories. Never assign shared.PIN, shared.ROOT or modify the
    # five authority files of the existing x86_64 source producer.
    if PIN != {**shared.PIN, 'abi': 'arm64-v8a'}:
        raise RuntimeError('ARM64 and x86_64 Node source pins differ beyond ABI')


def inputs():
    result = {}
    for path in PRODUCER_INPUTS:
        name = path.relative_to(REPOSITORY).as_posix()
        committed = subprocess.check_output(['git', '-C', str(REPOSITORY), 'show', f'HEAD:{name}'])
        if path.read_bytes() != committed:
            raise RuntimeError('Commit exact producer inputs before starting the ARM64 source build')
        result[name] = hashlib.sha256(committed).hexdigest()
    return {'commit': shared.git(REPOSITORY, 'rev-parse', 'HEAD'), 'inputs': result}


def preflight(mobile, ndk):
    check_pins()
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'amd64'):
        raise RuntimeError('This pinned Android NDK requires a Linux x86_64 build host')
    for tool in ('clang', 'clang++', 'ninja', 'ccache'):
        if shutil.which(tool) is None:
            raise RuntimeError(f'Missing source build tool: {tool}')
    if shared.git(mobile, 'rev-parse', 'HEAD') != PIN['mobileCommit']:
        raise RuntimeError('Wrong mobile source commit')
    if shared.git(mobile / 'node', 'rev-parse', 'HEAD') != PIN['nodeCommit'] or shared.git(mobile / 'node', 'rev-parse', 'HEAD^{tree}') != PIN['upstreamTree']:
        raise RuntimeError('Wrong Node source submodule commit or tree')
    if shared.git(mobile, 'status', '--porcelain') or shared.git(mobile / 'node', 'status', '--porcelain'):
        raise RuntimeError('Mobile and Node source checkouts must be clean')
    if shared.sha(mobile / 'scripts/build.py') != PIN['buildScriptSha256'] or shared.sha(mobile / 'scripts/versions.py') != shared.VERSIONS_SCRIPT_SHA256:
        raise RuntimeError('Pinned mobile build or version recipe changed')
    if f"Pkg.Revision = {PIN['ndkRevision']}" not in (ndk / 'source.properties').read_text(encoding='utf-8'):
        raise RuntimeError('Use the pinned NDK27d')
    tools = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    for name in ('aarch64-linux-android26-clang', 'aarch64-linux-android26-clang++', 'llvm-ar', 'llvm-readelf', 'llvm-strip', 'llvm-objcopy'):
        if not (tools / name).is_file():
            raise RuntimeError(f'Missing pinned ARM64 NDK tool: {name}')
    return inputs()


def command(recipe, mobile, ndk, jobs):
    if not isinstance(jobs, int) or not 1 <= jobs <= 32:
        raise RuntimeError('jobs must be between 1 and 32')
    return [sys.executable, str(recipe), 'android', '--ndk', str(ndk), '--arch', 'arm64',
            '--api', str(PIN['androidApi']), '--source', str(mobile / '.build/mbot-source-full-icu-arm64'),
            '--output', str(mobile / '.build/mbot-full-icu-arm64'), '--jobs', str(jobs)]


def validate_result(source, destination):
    variables = ast.literal_eval((source / 'config.gypi').read_text(encoding='utf-8'))['variables']
    if (variables.get('OS'), variables.get('target_arch'), variables.get('node_shared')) != ('android', 'arm64', 'true'):
        raise RuntimeError('Built Node configuration is not shared Android ARM64')
    if variables.get('v8_enable_i18n_support') not in (1, True, '1', 'true') or variables.get('icu_small') not in (0, False, '0', 'false'):
        raise RuntimeError('Built Node must contain full ICU')
    # The pinned mobile preparer stages its overlays/patches without creating
    # a commit: HEAD still names upstream Node. Validate the real index tree
    # and its working bytes, not the intentionally unpatched HEAD tree.
    shared.git(source, 'diff', '--quiet', '--no-ext-diff', '--no-textconv')
    if shared.git(source, 'write-tree') != PIN['nodeTree']:
        raise RuntimeError('Patched Node source tree does not match the pin')
    record = json.loads((destination / 'build-info.json').read_text(encoding='utf-8'))
    if any(record.get(key) != PIN[value] for key, value in [('version', 'version'), ('commit', 'nodeCommit'), ('tree', 'nodeTree'), ('target', 'abi')]):
        raise RuntimeError('Built ARM64 source record does not match the pin')
    binary = 'bin/arm64-v8a/libnode.so'
    with (destination / binary).open('rb') as stream:
        header = stream.read(64)
    # Upstream additionally checks every LOAD segment and dynamic export,
    # both before and after stripping. Its complete recipe is pinned above.
    if len(header) < 64 or header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 16)[0] != 3 or struct.unpack_from('<H', header, 18)[0] != 183:
        raise RuntimeError('Produced Node library is not Android ELF64 AArch64')
    if shared.sha(destination / binary) != record['binary_sha256'][binary]:
        raise RuntimeError('Built ARM64 library digest mismatch')
    for path in (source / 'config.gypi', destination / 'include/node/config.gypi'):
        if shared.sha(path) != record['config_sha256']:
            raise RuntimeError('Built ARM64 configuration digest mismatch')
    if str(record.get('versions', {}).get('modules')) != '137' or not record.get('versions', {}).get('icu') or not record.get('versions', {}).get('unicode'):
        raise RuntimeError('ARM64 Node ABI or actual ICU version report is missing')
    return record


def build(mobile, ndk, output, jobs=2, check_only=False):
    provenance = preflight(mobile, ndk)
    if output.exists():
        raise RuntimeError('Refusing to overwrite an existing ARM64 archive')
    if check_only:
        print('ARM64 source and toolchain preflight passed; no binary built')
        return
    stage = mobile / '.build'
    stage.mkdir(exist_ok=True)
    source = stage / 'mbot-source-full-icu-arm64'
    destination = stage / 'mbot-full-icu-arm64'
    if source.exists() or destination.exists():
        raise RuntimeError('ARM64 source or output directory already exists; preserve or move it before retrying')
    versions = stage / 'mbot_full_icu_versions_arm64.py'
    versions.write_text(shared.make_versions((mobile / 'scripts/versions.py').read_text(encoding='utf-8')), encoding='utf-8')
    recipe = stage / 'mbot-build-full-icu-arm64.py'
    text = shared.make_recipe((mobile / 'scripts/build.py').read_text(encoding='utf-8'))
    text = text.replace('from mbot_full_icu_versions import dependency_versions', 'from mbot_full_icu_versions_arm64 import dependency_versions', 1)
    ast.parse(text)
    ast.parse(versions.read_text(encoding='utf-8'))
    recipe.write_text(text, encoding='utf-8')
    env = os.environ.copy()
    env['PYTHONPATH'] = str(mobile / 'scripts')
    env['CCACHE_NAMESPACE'] = 'mbot-node-arm64-' + shared.sha(PIN_FILE)
    print(f"ARM64 build starting; free space {shutil.disk_usage(mobile).free // (1024**3)} GiB; allow ample room for source, objects and cache", flush=True)
    subprocess.run(command(recipe, mobile, ndk, jobs), env=env, check=True)
    record = validate_result(source, destination)
    # Refuse an edit during compilation; metadata describes exact committed
    # producer inputs, not merely the branch name at the end of a long build.
    if inputs() != provenance:
        raise RuntimeError('ARM64 producer inputs or HEAD changed during compilation')
    metadata = {'source': {key: record[key] for key in ('repository', 'version', 'tag', 'commit', 'tree')},
                'slices': [record], 'mbotSourceBuild': {**PIN, 'modifiedRecipeSha256': shared.sha(recipe),
                'versionsScriptSha256': shared.VERSIONS_SCRIPT_SHA256, 'modifiedVersionsScriptSha256': shared.sha(versions)},
                'mbotArm64Producer': provenance}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=5) as archive:
        for path in sorted(destination.rglob('*')):
            if path.is_file() and path.relative_to(destination).parts[0] != 'symbols' and path.name != 'build-info.json':
                archive.write(path, path.relative_to(destination).as_posix())
        archive.writestr('build-info.json', json.dumps(metadata, indent=2) + '\n')
    print('Built unadmitted full-ICU ARM64 candidate', PIN['version'], shared.sha(output))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mobile-source', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--jobs', type=int, default=2)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    build(args.mobile_source.resolve(), args.ndk.resolve(), args.output.resolve(), args.jobs, args.check_only)
