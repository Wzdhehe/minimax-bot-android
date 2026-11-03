#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Build the pinned Android Node source with full ICU, only for verification."""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
PIN = json.loads((ROOT / 'node-source-build.json').read_text())


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def make_recipe(text):
    needle = "'--shared', '--openssl-no-asm', '--with-intl=none'"
    if text.count(needle) != 1:
        raise RuntimeError('Unexpected Android configure recipe')
    return text.replace(needle, "'--shared', '--openssl-no-asm', '--with-intl=full-icu'", 1)


def build(mobile, ndk, output):
    if git(mobile, 'rev-parse', 'HEAD') != PIN['mobileCommit']:
        raise RuntimeError('Wrong mobile source commit')
    if git(mobile / 'node', 'rev-parse', 'HEAD') != PIN['nodeCommit']:
        raise RuntimeError('Wrong upstream Node submodule commit')
    if git(mobile / 'node', 'rev-parse', 'HEAD^{tree}') != PIN['upstreamTree']:
        raise RuntimeError('Wrong upstream Node source tree')
    if git(mobile, 'status', '--porcelain'):
        raise RuntimeError('Mobile source checkout must be clean')
    if f"Pkg.Revision = {PIN['ndkRevision']}" not in (ndk / 'source.properties').read_text():
        raise RuntimeError('Wrong NDK revision')
    original = mobile / 'scripts/build.py'
    if sha(original) != PIN['buildScriptSha256']:
        raise RuntimeError('Build recipe digest mismatch')
    stage = mobile / '.build'
    stage.mkdir(exist_ok=True)
    recipe = stage / 'mbot-build-full-icu.py'
    recipe.write_text(make_recipe(original.read_text()))
    source = stage / 'mbot-source-full-icu-x64'
    destination = stage / 'mbot-full-icu-x64'
    env = os.environ.copy()
    env['PYTHONPATH'] = str(mobile / 'scripts')
    subprocess.run([sys.executable, str(recipe), 'android', '--ndk', str(ndk), '--arch', 'x64',
                    '--api', str(PIN['androidApi']), '--source', str(source), '--output', str(destination),
                    '--jobs', '2'], env=env, check=True)
    variables = ast.literal_eval((source / 'config.gypi').read_text())['variables']
    if variables.get('v8_enable_i18n_support') not in (1, True, '1', 'true'):
        raise RuntimeError('Intl was not enabled')
    if variables.get('icu_small') not in (0, False, '0', 'false'):
        raise RuntimeError('Expected full ICU data')
    record = json.loads((destination / 'build-info.json').read_text())
    binary = f"bin/{PIN['abi']}/libnode.so"
    if sha(destination / binary) != record['binary_sha256'][binary]:
        raise RuntimeError('Built library digest mismatch')
    metadata = {'source': {key: record[key] for key in ('repository', 'version', 'tag', 'commit', 'tree')},
                'slices': [record], 'mbotSourceBuild': {**PIN, 'modifiedRecipeSha256': sha(recipe)}}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=5) as package:
        for path in sorted(destination.rglob('*')):
            if path.is_file() and path.relative_to(destination).parts[0] != 'symbols' and path.name != 'build-info.json':
                package.write(path, path.relative_to(destination).as_posix())
        package.writestr('build-info.json', json.dumps(metadata, indent=2) + '\n')
    archive_sha = sha(output)
    print('Built verification-only full-ICU Node', PIN['version'], PIN['abi'], archive_sha)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write(f'archive_sha={archive_sha}\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--mobile-source', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.mobile_source.resolve(), args.ndk.resolve(), args.output.resolve())
