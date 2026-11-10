# SPDX-License-Identifier: AGPL-3.0-only
"""Consumer admission tests for the reviewed ARM64 Node candidate.

Synthetic fixtures only: no real NDK, no real candidate archive, no network
and no dependency on the state of the real repository. These tests cover what
the consumer refuses and what it prepares; they are not an Android device
result and they do not admit the product.
"""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import contextlib
import shutil
import stat
import struct
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
PROBE = 'dsh-go/tooling/android-node-probe'
PRODUCER_FILES = ('node-arm64-source-build.json', 'build-arm64-source.py',
                  'build-source.py', 'node-source-build.json')
DEFAULT_CONFIG = {
    'OS': 'android', 'target_arch': 'arm64', 'node_shared': 'true',
    'node_module_version': 137, 'v8_enable_i18n_support': 1, 'icu_small': 0,
    'v8_enable_pointer_compression': 0, 'v8_enable_sandbox': 0,
    'v8_enable_31bit_smis_on_64bit_arch': 0,
}


def load_module():
    spec = importlib.util.spec_from_file_location('arm64_node_consumer', HERE / 'prepare-arm64-node.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make_elf(machine=183, align=0x4000, api=26, needed=('libc.so',), dynamic=True, note_name='Android'):
    """A minimal AArch64 ELF64 shared library with real program headers.

    One PT_LOAD segment covers the file, an optional PT_NOTE segment carries
    the Android API tag and an optional PT_DYNAMIC segment carries the
    DT_NEEDED entries and the string table.
    """
    header = bytearray(64)
    header[:16] = b'\x7fELF' + bytes([2, 1, 1, 0]) + bytes(8)
    struct.pack_into('<HH', header, 16, 3, machine)
    struct.pack_into('<I', header, 20, 1)
    struct.pack_into('<Q', header, 32, 64)          # e_phoff
    struct.pack_into('<H', header, 52, 64)          # e_ehsize
    struct.pack_into('<H', header, 54, 56)          # e_phentsize
    phnum = 1 + (1 if api is not None else 0) + (1 if dynamic else 0)
    struct.pack_into('<H', header, 56, phnum)

    note = b''
    if api is not None:
        label = note_name.encode('ascii') + b'\x00'
        note = struct.pack('<III', len(label), 4, 1) + label + struct.pack('<I', api)
    strings = b'\x00' + b''.join(name.encode('ascii') + b'\x00' for name in needed)
    offsets = []
    cursor = 1
    for name in needed:
        offsets.append(cursor)
        cursor += len(name) + 1
    entries = [(1, offset) for offset in offsets]
    if dynamic:
        entries.append((5, 0))                       # DT_STRTAB, patched below
        entries.append((0, 0))                       # DT_NULL

    ph_end = 64 + phnum * 56
    note_offset = ph_end
    dynamic_offset = note_offset + len(note)
    table_offset = dynamic_offset + len(entries) * 16
    total = table_offset + len(strings)
    if dynamic:
        entries[-2] = (5, table_offset)

    program = bytearray(phnum * 56)

    def segment(index, p_type, p_flags, p_offset, p_vaddr, p_filesz, p_align):
        base = index * 56
        struct.pack_into('<II', program, base, p_type, p_flags)
        struct.pack_into('<QQ', program, base + 8, p_offset, p_vaddr)
        struct.pack_into('<QQ', program, base + 24, p_vaddr, p_vaddr)
        struct.pack_into('<QQ', program, base + 32, p_filesz, p_filesz)
        struct.pack_into('<Q', program, base + 48, p_align)

    segment(0, 1, 5, 0, 0, total, align)            # PT_LOAD
    index = 1
    if api is not None:
        segment(index, 4, 4, note_offset, note_offset, len(note), 4)
        index += 1
    if dynamic:
        segment(index, 2, 6, dynamic_offset, dynamic_offset, len(entries) * 16, 8)

    data = bytearray(total)
    data[0:64] = header
    data[64:64 + len(program)] = program
    data[note_offset:note_offset + len(note)] = note
    for position, (tag, value) in enumerate(entries):
        struct.pack_into('<qQ', data, dynamic_offset + position * 16, tag, value)
    data[table_offset:table_offset + len(strings)] = strings
    return bytes(data)


def snapshot(base, skip=()):
    """The names and bytes of a tree, for isolation comparisons."""
    result = {}
    for path in sorted(base.rglob('*')):
        relative = path.relative_to(base).as_posix()
        if any(relative == item or relative.startswith(item + '/') for item in skip):
            continue
        result[relative] = path.read_bytes() if path.is_file() else 'directory'
    return result


class StubProbe:
    """The read-only x86_64 bundler, replaced by a small fixture writer."""

    def __init__(self):
        self.calls = []

    def bundle(self, output_dir):
        output = Path(output_dir)
        output.mkdir(parents=True, exist_ok=True)
        self.calls.append(output)
        (output / 'node-fd-probe.cjs').write_text(
            'const wrapper = "__MBOT_SQLITE_WRAPPER__";\n', encoding='utf-8')


class Arm64NodeConsumerTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.real_run = subprocess.run
        self.temporary = tempfile.TemporaryDirectory(prefix='mbot arm64 consumer ')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.commands = []
        self.compiler_error = False
        self.repository = self.base / 'repository'
        self.repository.mkdir()
        self.git('init', '--quiet')
        self.git('config', 'user.name', 'ARM64 consumer fixture')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        hooks = self.base / 'empty-hooks'
        hooks.mkdir()
        self.git('config', 'core.hooksPath', str(hooks))
        probe = self.repository / PROBE
        probe.mkdir(parents=True)
        for name in PRODUCER_FILES:
            shutil.copyfile(HERE / name, probe / name)
        self.git('add', '-A')
        self.git('commit', '--quiet', '-m', 'producer inputs')
        self.producer_commit = self.git('rev-parse', 'HEAD').decode('utf-8').strip()
        self.archive = self.base / 'candidate.zip'
        self.output = self.base / 'prepared/arm64-v8a'
        self.ndk = self.make_ndk()
        self.publish()

    # ── the fixture ──────────────────────────────────────────────────────────

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repository), *args], stderr=subprocess.PIPE)

    def make_ndk(self, host='linux-x86_64', compiler='aarch64-linux-android26-clang++',
                 runtime=True, notice=True):
        """A pinned NDK shape with synthetic arm64-v8a content."""
        ndk = self.base / 'ndk'
        ndk.mkdir(parents=True, exist_ok=True)
        (ndk / 'source.properties').write_text(
            'Pkg.Desc = Android NDK\nPkg.Revision = 27.3.13750724\n', encoding='utf-8')
        toolchain = ndk / 'toolchains/llvm/prebuilt' / host
        (toolchain / 'bin').mkdir(parents=True, exist_ok=True)
        (toolchain / 'bin' / compiler).write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
        if runtime:
            library = toolchain / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'
            library.parent.mkdir(parents=True)
            # The real NDK27d libc++_shared carries the API-neutral level 21.
            library.write_bytes(make_elf(api=21, needed=('libc.so', 'libm.so', 'libdl.so')))
        if notice:
            (ndk / 'NOTICE').write_text('NDK notice fixture\n', encoding='utf-8')
        return ndk

    def producer_inputs(self):
        result = {}
        for name in PRODUCER_FILES:
            path = PROBE + '/' + name
            result[path] = hashlib.sha256(self.git('show', 'HEAD:' + path)).hexdigest()
        return result

    def make_record(self, library_sha, config_sha, archive_sha, archive_size, producer):
        return {
            '_license': 'SPDX-License-Identifier: AGPL-3.0-only',
            '_scope': 'Fixture review record for the consumer tests only.',
            'kind': 'manual-linux-x86_64-producer-run',
            'admittedForProduct': False,
            'reviewedAt': '2026-11-10',
            'abi': 'arm64-v8a',
            'androidApi': 26,
            'ndkRevision': '27.3.13750724',
            'archiveSha256': archive_sha,
            'archiveSize': archive_size,
            'librarySha256': library_sha,
            'configSha256': config_sha,
            'modifiedRecipeSha256': 'a' * 64,
            'versionsScriptSha256': 'b' * 64,
            'modifiedVersionsScriptSha256': 'c' * 64,
            'versions': {'node': '24.22.2', 'modules': '137', 'icu': '78.3', 'unicode': '17.0'},
            'producer': producer,
        }

    def make_metadata(self, record, config_sha, library_sha):
        pin = json.loads((self.repository / PROBE / 'node-arm64-source-build.json').read_text(encoding='utf-8'))
        return {
            'source': {'repository': pin['mobileRepository'], 'version': pin['version'],
                       'tag': 'v' + pin['version'], 'commit': pin['nodeCommit'], 'tree': pin['nodeTree']},
            'slices': [{'target': 'arm64-v8a', 'version': pin['version'], 'commit': pin['nodeCommit'],
                        'tree': pin['nodeTree'], 'config_sha256': config_sha,
                        'binary_sha256': {'bin/arm64-v8a/libnode.so': library_sha},
                        'versions': dict(record['versions'])}],
            'mbotSourceBuild': {**pin, 'modifiedRecipeSha256': record['modifiedRecipeSha256'],
                                'versionsScriptSha256': record['versionsScriptSha256'],
                                'modifiedVersionsScriptSha256': record['modifiedVersionsScriptSha256']},
            'mbotArm64Producer': record['producer'],
        }

    def write_archive(self, members, extra_members=(), big_member=False):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with zipfile.ZipFile(self.archive, 'w', compression=zipfile.ZIP_DEFLATED) as package:
                for name, data in members.items():
                    package.writestr(name, data)
                for name, data in extra_members:
                    if isinstance(data, zipfile.ZipInfo):
                        package.writestr(data, b'link target')
                    else:
                        package.writestr(name, data)
                if big_member:
                    info = zipfile.ZipInfo('include/node/oversized.bin')
                    info.compress_type = zipfile.ZIP_DEFLATED
                    with package.open(info, 'w') as stream:
                        chunk = bytes(1024 * 1024)
                        for _ in range(160):
                            stream.write(chunk)
                        stream.write(b'x')

    def write_record(self, record, commit=True):
        path = self.repository / PROBE / 'node-arm64-candidate.json'
        path.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
        if commit:
            self.git('add', '-A')
            self.git('commit', '--quiet', '-m', 'candidate record')

    def publish(self, libnode=None, config=None, node_version=(24, 22, 2), metadata_patch=None,
                extra_members=(), omit=(), record_patch=None, big_member=False):
        """Write a candidate archive and its committed review record."""
        if libnode is None:
            libnode = make_elf(needed=('libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libc++_shared.so'))
        variables = dict(DEFAULT_CONFIG)
        variables.update(config or {})
        config_bytes = repr({'variables': variables}).encode('utf-8')
        config_sha = hashlib.sha256(config_bytes).hexdigest()
        library_sha = hashlib.sha256(libnode).hexdigest()
        version_bytes = ''.join('#define NODE_%s_VERSION %d\n' % (part, number)
                                for part, number in zip(('MAJOR', 'MINOR', 'PATCH'), node_version)).encode('utf-8')
        producer = {'commit': self.producer_commit, 'inputs': self.producer_inputs()}
        record = self.make_record(library_sha, config_sha, '', 0, producer)
        metadata = self.make_metadata(record, config_sha, library_sha)
        if metadata_patch is not None:
            metadata = metadata_patch(metadata)
        members = {
            'LICENSE': b'nodejs-mobile license fixture\n',
            'LICENSE-nodejs-mobile': b'nodejs-mobile license fixture\n',
            'build-info.json': (json.dumps(metadata, indent=2) + '\n').encode('utf-8'),
            'bin/arm64-v8a/libnode.so': libnode,
            'include/node/config.gypi': config_bytes,
            'include/node/node.h': b'/* node.h fixture */\n',
            'include/node/node_version.h': version_bytes,
        }
        for name in omit:
            members.pop(name, None)
        self.write_archive(members, extra_members, big_member)
        record['archiveSha256'] = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        record['archiveSize'] = self.archive.stat().st_size
        if record_patch is not None:
            record = record_patch(record)
        self.write_record(record)

    def fake_run(self, command, *args, **kwargs):
        """Stand in for the compiler; git and node calls run for real."""
        if not isinstance(command, (list, tuple)):
            return self.real_run(command, *args, **kwargs)
        command = list(command)
        if command and 'clang' in str(command[0]):
            self.commands.append(command)
            if self.compiler_error:
                raise subprocess.CalledProcessError(1, command)
            library = Path(command[command.index('-o') + 1])
            library.parent.mkdir(parents=True, exist_ok=True)
            library.write_bytes(make_elf(needed=('libnode.so', 'libc++_shared.so', 'libc.so')))
            return subprocess.CompletedProcess(command, 0)
        return self.real_run(command, *args, **kwargs)

    def run_prepare(self, output=None, system='Linux', ndk=None):
        """Run the consumer with the fixture bundler and a mocked compiler."""
        stub = StubProbe()
        self.printed = io.StringIO()
        with patch.object(self.module, 'load_probe_module', return_value=stub), \
             patch.object(self.module.subprocess, 'run', side_effect=self.fake_run), \
             contextlib.redirect_stdout(self.printed):
            self.module.prepare(ndk or self.ndk, self.archive, output or self.output, self.repository, system)
        return stub

    def assert_refused(self, expected, output=None, ndk=None, system='Linux'):
        with self.assertRaisesRegex(RuntimeError, expected):
            self.run_prepare(output=output, system=system, ndk=ndk)
        self.assertFalse((output or self.output).exists())
        self.assertEqual([], list((output or self.output).parent.glob('*.partial-*')))

    # ── the accepted candidate ───────────────────────────────────────────────

    def test_the_reviewed_candidate_produces_the_arm64_prepared_inputs(self):
        stub = self.run_prepare()
        for relative in ('node-headers/node.h', 'node-headers/config.gypi',
                         'node-headers/node_version.h', 'node-headers.json',
                         'verified-jni/arm64-v8a/libnode.so', 'verified-jni/arm64-v8a/libc++_shared.so',
                         'verified-jni/arm64-v8a/libmbot_node_probe.so', 'node-bridge-inputs.json',
                         'verified-assets/node-fd-probe.cjs', 'verified-assets/node-validation.json',
                         'verified-assets/third_party/nodejs-mobile/LICENSE',
                         'verified-assets/third_party/nodejs-mobile/LICENSE-nodejs-mobile',
                         'verified-assets/third_party/nodejs-mobile/build-info.json',
                         'verified-assets/third_party/ndk/NOTICE'):
            self.assertTrue((self.output / relative).is_file(), relative)
        manifest = json.loads((self.output / 'node-headers.json').read_text(encoding='utf-8'))
        self.assertEqual(hashlib.sha256(self.archive.read_bytes()).hexdigest(), manifest['nodeArchiveSha256'])
        self.assertEqual({'config.gypi', 'node.h', 'node_version.h'}, set(manifest['files']))
        validation = json.loads((self.output / 'verified-assets/node-validation.json').read_text(encoding='utf-8'))
        self.assertTrue(validation['requiresIntl'])
        self.assertFalse(validation['admittedForProduct'])
        self.assertFalse(validation['requiresSqlite'])
        self.assertEqual('arm64-v8a', validation['abi'])
        self.assertEqual('node-arm64-candidate.json', validation['candidateRecord'])
        bridge = json.loads((self.output / 'node-bridge-inputs.json').read_text(encoding='utf-8'))
        self.assertEqual({'node_probe.cpp', 'node_snapshot_bridge.inc'}, set(bridge['inputs']))
        # The shared bundler writes into the staging directory, which is
        # renamed into place after every check passed.
        self.assertEqual(1, len(stub.calls))
        self.assertEqual('verified-assets', stub.calls[0].name)
        self.assertTrue(stub.calls[0].parent.name.startswith('arm64-v8a.partial-'))
        self.assertIn('verification only, product not admitted', self.printed.getvalue())
        self.assertTrue(self.commands, 'the preparation did not run a compiler')
        for command in self.commands:
            self.assertIsInstance(command, list)
            self.assertEqual('aarch64-linux-android26-clang++', os.path.basename(str(command[0])))
            for argument in command:
                # 'linux-x86_64' names the NDK host slice, never an Android target.
                self.assertNotIn('x86_64-linux-android', str(argument))
                self.assertNotIn('-lnode_x86_64', str(argument))
        self.assertEqual([], list(self.output.parent.glob('*.partial-*')))

    def test_the_prepared_library_is_the_reviewed_arm64_one(self):
        self.run_prepare()
        library = self.output / 'verified-jni/arm64-v8a/libnode.so'
        self.assertEqual(hashlib.sha256(library.read_bytes()).hexdigest(),
                         json.loads((self.repository / PROBE / 'node-arm64-candidate.json')
                                    .read_text(encoding='utf-8'))['librarySha256'])
        facts = self.module.check_android_elf(library, {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libc++_shared.so'})
        self.assertEqual(26, facts['api'])
        self.assertEqual(0x4000, facts['loadAlign'])
        self.assertEqual(['libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libc++_shared.so'], facts['needed'])

    def test_the_prebuilt_runtime_may_target_a_lower_api_but_never_a_higher_one(self):
        library = self.base / 'runtime-check.so'
        for api in (21, 26):
            library.write_bytes(make_elf(api=api, needed=('libc.so',)))
            self.assertEqual(api, self.module.check_android_elf(library, {'libc.so'}, prebuilt_runtime=True)['api'])
        for api in (27, None):
            library.write_bytes(make_elf(api=api, needed=('libc.so',)))
            with self.assertRaisesRegex(RuntimeError, 'API26 ELF note'):
                self.module.check_android_elf(library, {'libc.so'}, prebuilt_runtime=True)

    def test_a_runtime_above_the_app_minimum_fails_preparation(self):
        runtime = (self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
                   / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so')
        runtime.write_bytes(make_elf(api=28, needed=('libc.so',)))
        with self.assertRaisesRegex(RuntimeError, 'API26 ELF note'):
            self.run_prepare()
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.glob('*.partial-*')))

    # ── the host selection ───────────────────────────────────────────────────

    def test_the_windows_host_uses_the_executable_with_one_explicit_target(self):
        ndk = self.make_ndk(host='windows-x86_64', compiler='clang++.exe')
        toolchain, command = self.module.arm64_ndk_tools(ndk, 'Windows')
        self.assertEqual(str(toolchain / 'bin/clang++.exe'), command[0])
        self.assertEqual(['--target=aarch64-linux-android26'], command[1:])
        self.assertNotIn('"', command[0])

    def test_a_command_wrapper_cannot_substitute_for_the_windows_executable(self):
        ndk = self.make_ndk(host='windows-x86_64', compiler='clang++.cmd')
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK compiler'):
            self.module.arm64_ndk_tools(ndk, 'Windows')

    def test_an_unsupported_host_has_no_fallback_compiler(self):
        with self.assertRaisesRegex(RuntimeError, 'Linux or Windows hosts only'):
            self.module.arm64_ndk_tools(self.ndk, 'Darwin')

    def test_a_toolchain_without_the_arm64_runtime_is_refused(self):
        runtime = (self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64'
                   / 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so')
        runtime.unlink()
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK compiler'):
            self.module.arm64_ndk_tools(self.ndk, 'Linux')

    def test_the_linux_host_never_falls_back_to_the_x86_64_target(self):
        toolchain, command = self.module.arm64_ndk_tools(self.ndk, 'Linux')
        self.assertEqual(str(toolchain / 'bin/aarch64-linux-android26-clang++'), command[0])
        self.assertEqual([], [part for part in command if part.startswith('--target=')])

    # ── the archive digest ───────────────────────────────────────────────────

    def test_a_tampered_archive_is_refused_before_any_extraction(self):
        self.archive.write_bytes(self.archive.read_bytes() + b'tampered')
        self.assert_refused('ARM64 candidate archive (size|digest) mismatch')

    def test_an_unchanged_size_with_a_changed_byte_is_refused_by_the_digest(self):
        data = bytearray(self.archive.read_bytes())
        data[len(data) // 2] ^= 0x01
        self.archive.write_bytes(bytes(data))
        self.assert_refused('ARM64 candidate archive digest mismatch')

    def test_a_missing_archive_is_refused(self):
        self.archive.unlink()
        self.assert_refused('ARM64 candidate archive is missing')

    # ── the committed record and the producer inputs ─────────────────────────

    def test_an_uncommitted_record_is_refused(self):
        path = self.repository / PROBE / 'node-arm64-candidate.json'
        path.write_text(path.read_text(encoding='utf-8') + '\n', encoding='utf-8')
        self.assert_refused('Commit the reviewed ARM64 candidate record')

    def test_a_record_that_claims_the_product_is_admitted_is_refused(self):
        self.publish(record_patch=lambda record: {**record, 'admittedForProduct': True})
        self.assert_refused('does not admit the product')

    def test_a_record_for_another_abi_is_refused(self):
        self.publish(record_patch=lambda record: {**record, 'abi': 'x86_64'})
        self.assert_refused('names another ABI')

    def test_changed_producer_inputs_are_refused(self):
        path = self.repository / PROBE / 'build-source.py'
        path.write_text(path.read_text(encoding='utf-8') + '\n# changed\n', encoding='utf-8')
        self.git('add', '-A')
        self.git('commit', '--quiet', '-m', 'changed producer input')
        self.assert_refused('ARM64 producer inputs changed since the reviewed candidate')

    def test_a_producer_commit_outside_the_history_is_refused(self):
        self.publish(record_patch=lambda record: {**record, 'producer': {**record['producer'], 'commit': '1' * 40}})
        self.assert_refused('is not an ancestor of HEAD')

    # ── the archive metadata against the committed record ────────────────────

    def test_an_x86_64_source_recipe_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'mbotSourceBuild': {**meta['mbotSourceBuild'], 'abi': 'x86_64'}})
        self.assert_refused('Unexpected ARM64 source build recipe')

    def test_an_extra_source_recipe_key_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'mbotSourceBuild': {**meta['mbotSourceBuild'], 'extra': 'x'}})
        self.assert_refused('Unexpected ARM64 source build recipe')

    def test_a_different_modified_recipe_digest_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'mbotSourceBuild': {**meta['mbotSourceBuild'], 'modifiedRecipeSha256': '0' * 64}})
        self.assert_refused('Unexpected ARM64 source build recipe')

    def test_a_different_producer_commit_inside_the_archive_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'mbotArm64Producer': {**meta['mbotArm64Producer'], 'commit': '1' * 40}})
        self.assert_refused('Unexpected ARM64 producer provenance')

    def test_two_slices_are_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'slices': meta['slices'] * 2})
        self.assert_refused('Unexpected ARM64 candidate slice count')

    def test_an_x86_64_slice_target_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'slices': [{**meta['slices'][0], 'target': 'x86_64'}]})
        self.assert_refused('Unexpected ARM64 candidate slice target')

    def test_a_wrong_node_module_version_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'slices': [{**meta['slices'][0], 'versions': {**meta['slices'][0]['versions'], 'modules': '127'}}]})
        self.assert_refused('Unexpected ARM64 candidate Node module version')

    def test_a_wrong_icu_version_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'slices': [{**meta['slices'][0], 'versions': {**meta['slices'][0]['versions'], 'icu': '77.1'}}]})
        self.assert_refused('Unexpected ARM64 candidate ICU version')

    def test_a_wrong_configuration_digest_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'slices': [{**meta['slices'][0], 'config_sha256': '0' * 64}]})
        self.assert_refused('Unexpected ARM64 candidate configuration digest')

    def test_a_wrong_binary_digest_is_refused(self):
        self.publish(metadata_patch=lambda meta: {**meta, 'slices': [{**meta['slices'][0], 'binary_sha256': {'bin/arm64-v8a/libnode.so': '0' * 64}}]})
        self.assert_refused('Unexpected ARM64 candidate binary digest')

    # ── the prepared headers ─────────────────────────────────────────────────

    def test_relabelled_x64_headers_are_refused_even_with_a_consistent_record(self):
        self.publish(config={'target_arch': 'x64'})
        self.assert_refused('ARM64 candidate headers do not match Android arm64')

    def test_a_small_icu_build_is_refused(self):
        self.publish(config={'icu_small': 'true'})
        self.assert_refused('ARM64 candidate must include full ICU')

    def test_a_build_without_intl_is_refused(self):
        self.publish(config={'v8_enable_i18n_support': 0})
        self.assert_refused('ARM64 candidate must include full ICU')

    def test_a_pointer_compressed_build_is_refused(self):
        self.publish(config={'v8_enable_pointer_compression': 1})
        self.assert_refused('Unsupported ARM64 candidate V8 ABI configuration')

    def test_a_wrong_header_version_is_refused(self):
        self.publish(node_version=(24, 22, 3))
        self.assert_refused('ARM64 candidate header version mismatch')

    # ── the arm64 library itself ─────────────────────────────────────────────

    def test_an_x86_64_library_is_refused(self):
        self.publish(libnode=make_elf(machine=62, needed=('libc.so',)))
        self.assert_refused('Expected an AArch64 ARM64 candidate library')

    def test_a_small_page_alignment_is_refused(self):
        self.publish(libnode=make_elf(align=0x1000, needed=('libc.so',)))
        self.assert_refused('Unexpected ARM64 candidate ELF segment alignment')

    def test_an_older_android_api_is_refused(self):
        self.publish(libnode=make_elf(api=21, needed=('libc.so',)))
        self.assert_refused('Expected Android API26 ELF note')

    def test_a_missing_android_note_is_refused(self):
        self.publish(libnode=make_elf(api=None, needed=('libc.so',)))
        self.assert_refused('Expected Android API26 ELF note')

    def test_a_glibc_dependency_is_refused(self):
        self.publish(libnode=make_elf(needed=('libc.so.6', 'libpthread.so.0')))
        self.assert_refused('Unexpected native dependency')

    def test_a_library_without_a_dynamic_section_is_refused(self):
        self.publish(libnode=make_elf(dynamic=False, needed=()))
        self.assert_refused('Expected a dynamic section')

    # ── the archive members ──────────────────────────────────────────────────

    def test_a_traversing_member_is_refused(self):
        self.publish(extra_members=[('../evil', b'x')])
        self.assert_refused('Unsafe ARM64 candidate archive member path')

    def test_an_absolute_member_is_refused(self):
        self.publish(extra_members=[('/abs', b'x')])
        self.assert_refused('Unsafe ARM64 candidate archive member path')

    def test_a_backslash_member_is_refused(self):
        self.publish(extra_members=[('a\\b', b'x')])
        self.assert_refused('Unsafe ARM64 candidate archive member name')

    def test_a_duplicate_member_is_refused(self):
        self.publish(extra_members=[('LICENSE', b'again')])
        self.assert_refused('Duplicate ARM64 candidate archive member')

    def test_a_link_member_is_refused(self):
        link = zipfile.ZipInfo('include/node/link.h')
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.publish(extra_members=[('include/node/link.h', link)])
        self.assert_refused('Unsafe ARM64 candidate archive member type')

    def test_an_unexpected_member_is_refused(self):
        self.publish(extra_members=[('bin/x86_64/libnode.so', b'x')])
        self.assert_refused('Unexpected ARM64 candidate member')

    def test_a_missing_header_is_refused(self):
        self.publish(omit=('include/node/node.h',))
        self.assert_refused('ARM64 candidate archive is missing')

    def test_an_oversized_member_is_refused(self):
        self.publish(big_member=True)
        self.assert_refused('ARM64 candidate archive member budget exceeded')

    # ── the output location ──────────────────────────────────────────────────

    def test_an_existing_output_is_refused_and_kept(self):
        self.output.mkdir(parents=True)
        sentinel = self.output / 'sentinel.txt'
        sentinel.write_text('keep me', encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'ARM64 Node output already exists'):
            self.run_prepare()
        self.assertEqual('keep me', sentinel.read_text(encoding='utf-8'))
        self.assertEqual([], list(self.output.parent.glob('*.partial-*')))

    def test_the_x86_64_prepared_locations_are_never_a_target(self):
        target = self.module.ROOT / 'build/verified-jni/x'
        self.assert_refused('Refusing to write x86_64 prepared inputs', output=target)
        self.assertFalse(target.exists())

    def test_the_preparation_writes_nothing_outside_its_own_output(self):
        before = snapshot(self.base, skip=('repository',))
        self.run_prepare()
        after = snapshot(self.base, skip=('repository',))
        for name, content in before.items():
            self.assertEqual(content, after.get(name), name)
        for name in after:
            if name not in before:
                self.assertTrue(name in ('prepared', 'prepared/arm64-v8a')
                                or name.startswith('prepared/arm64-v8a/'), name)
        legacy = self.module.ROOT / 'build/verified-jni/x86_64'
        if legacy.exists():
            self.assertEqual(snapshot(legacy), snapshot(legacy))

    def test_a_failing_compiler_leaves_no_output_and_no_staging(self):
        self.compiler_error = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_prepare()
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.glob('*.partial-*')))

    @unittest.skipUnless(shutil.which('node'), 'node is not installed')
    def test_the_real_probe_bundler_still_produces_the_probe_asset(self):
        with patch.object(self.module.subprocess, 'run', side_effect=self.fake_run), \
             contextlib.redirect_stdout(io.StringIO()):
            self.module.prepare(self.ndk, self.archive, self.output, self.repository, 'Linux')
        script = (self.output / 'verified-assets/node-fd-probe.cjs').read_text(encoding='utf-8')
        self.assertIn('data:text/javascript;base64,', script)
        self.assertNotIn('"__MBOT_FD_MODULE_URL__"', script)


if __name__ == '__main__':
    unittest.main()
