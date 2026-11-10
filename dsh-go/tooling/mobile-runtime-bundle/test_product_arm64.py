# SPDX-License-Identifier: AGPL-3.0-only
"""Product admission tests for the reviewed ARM64 candidate inputs.

Synthetic fixtures only: no real NDK, no real candidate archive, no network
and no dependency on the state of the real repository. These tests cover what
the ARM64 product gate of prepare-product.py admits and refuses; they are
not an Android device result and they do not admit a product themselves.
"""
import argparse
import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
PROBE_DIR = HERE.parent / 'android-node-probe'

spec = importlib.util.spec_from_file_location('prepare_product', HERE / 'prepare-product.py')
product = importlib.util.module_from_spec(spec)
spec.loader.exec_module(product)

consumer_spec = importlib.util.spec_from_file_location('arm64_node_consumer', PROBE_DIR / 'prepare-arm64-node.py')
real_consumer = importlib.util.module_from_spec(consumer_spec)
consumer_spec.loader.exec_module(real_consumer)

helper_spec = importlib.util.spec_from_file_location('arm64_consumer_helpers', PROBE_DIR / 'test_arm64_node_consumer.py')
consumer_helpers = importlib.util.module_from_spec(helper_spec)
helper_spec.loader.exec_module(consumer_helpers)
make_elf = consumer_helpers.make_elf

NDK_REVISION = '27.3.13750724'
NODE_VERSION = '24.22.2'
NODE_MODULES = '137'
DEFAULT_CONFIG = {
    'OS': 'android', 'target_arch': 'arm64', 'node_shared': 'true',
    'node_module_version': 137, 'v8_enable_i18n_support': 1, 'icu_small': 0,
    'v8_enable_pointer_compression': 0, 'v8_enable_sandbox': 0,
    'v8_enable_31bit_smis_on_64bit_arch': 0,
}
# The x86_64 fake of an AArch64 library; it is never accepted.
X86_ELF = make_elf(machine=62, needed=('libc.so',))


def apply_patch(document, changes):
    """Apply a fixture patch; a None value removes the key."""
    for key, value in changes.items():
        if value is None:
            document.pop(key, None)
        else:
            document[key] = value
    return document


class StubConsumer:
    """The reviewed candidate consumer with a fixture record.

    Only the git backed record and producer lookups are replaced. The
    metadata, header and ELF checks are the real ones of the consumer, so
    the product gate is checked against the real rules.
    """

    def __init__(self, fixture):
        self.fixture = fixture

    def load_record(self, repository):
        return self.fixture.record, self.fixture.pin

    def check_producer_inputs(self, repository, record):
        return None

    def check_metadata(self, info, record, pin):
        return real_consumer.check_metadata(info, record, pin)

    def check_headers(self, headers, record):
        return real_consumer.check_headers(headers, record)

    def check_android_elf(self, path, allowed_needed, prebuilt_runtime=False):
        return real_consumer.check_android_elf(path, allowed_needed, prebuilt_runtime)


class Arm64ProductAdmissionTest(unittest.TestCase):
    """The ARM64 product gate of prepare-product.py."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='mbot arm64 product ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / 'repository'
        self.dsh = self.repository / 'dsh-go'
        self.probe = self.dsh / 'tooling/android-node-probe'
        self.bundle = self.dsh / 'tooling/mobile-runtime-bundle'
        self.native = self.dsh / 'runtime/native-node'
        self.prepared = self.probe / 'build'
        self.arm64_prepared = self.prepared / 'abi/arm64-v8a'
        self.mobile = self.root / 'mobile'
        self.ndk = self.root / 'ndk'
        self.commands = []
        self.pin = json.loads((PROBE_DIR / 'node-arm64-source-build.json').read_text(encoding='utf-8'))
        self.sqlite_pin = json.loads((PROBE_DIR / 'sqlite-runtime-pin.json').read_text(encoding='utf-8'))
        # The fixture knobs; every gate of the product module is exercised
        # by changing one of them and rebuilding the prepared inputs.
        self.config_variables = dict(DEFAULT_CONFIG)
        self.node_version = (24, 22, 2)
        self.libnode = make_elf(needed=('libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libc++_shared.so'))
        self.libcxx = make_elf(api=21, needed=('libc.so', 'libm.so', 'libdl.so'))
        self.node_probe = make_elf(needed=('libnode.so', 'libc++_shared.so', 'libc.so', 'libm.so', 'libdl.so', 'liblog.so'))
        self.sqlite_probe = make_elf(needed=('libnode.so', 'libc++_shared.so', 'libc.so'))
        self.backup_library = make_elf(needed=('libc++_shared.so', 'libc.so', 'libm.so', 'libdl.so', 'liblog.so'))
        self.backup_libcxx = self.libcxx
        self.extra_jni = {}
        self.extra_header = {}
        self.record_patch = {}
        self.info_patch = {}
        self.validation_patch = {}
        self.sqlite_patch = {}
        self.missing_wrapper = False
        self.record = {
            '_license': 'SPDX-License-Identifier: AGPL-3.0-only',
            '_scope': 'Fixture review record for the product admission tests only.',
            'kind': 'manual-linux-x86_64-producer-run',
            'admittedForProduct': False,
            'reviewedAt': '2026-11-10',
            'abi': 'arm64-v8a',
            'androidApi': 26,
            'ndkRevision': NDK_REVISION,
            'archiveSha256': 'a' * 64,
            'archiveSize': 4096,
            'librarySha256': '0' * 64,
            'configSha256': '0' * 64,
            'modifiedRecipeSha256': 'b' * 64,
            'versionsScriptSha256': 'c' * 64,
            'modifiedVersionsScriptSha256': 'd' * 64,
            'versions': {'node': NODE_VERSION, 'modules': NODE_MODULES, 'icu': '78.3', 'unicode': '17.0'},
            'producer': {'commit': 'e' * 40, 'inputs': {}},
        }
        self.build_repository()
        self.build()
        self.build_mobile()
        self.build_ndk()
        self.patches = [
            patch.object(product, 'DSH', self.dsh),
            patch.object(product, 'HERE', self.bundle),
            patch.object(product, 'PROBE', self.probe),
            patch.object(product, 'PREPARED', self.prepared),
            patch.object(product, 'ARM64_PREPARED', self.arm64_prepared),
            patch.object(product, 'NATIVE', self.native),
            patch.object(product, 'REPOSITORY', self.repository),
            patch.object(product, 'TARGETS', {
                'x86_64': {'prepared': self.prepared, 'triple': 'x86_64-linux-android26'},
                'arm64-v8a': {'prepared': self.arm64_prepared, 'triple': 'aarch64-linux-android26'}}),
            patch.object(product, 'arm64_consumer', lambda: StubConsumer(self)),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)

    # ── the fixture ──────────────────────────────────────────────────────────

    def build_repository(self):
        """The repository tree the product module reads its sources from."""
        self.probe.mkdir(parents=True)
        self.bundle.mkdir(parents=True)
        for name in ('node-arm64-source-build.json', 'node-source-build.json',
                     'sqlite-runtime-pin.json', 'node-runtime-pin.json', 'prepare-arm64-node.py'):
            shutil.copyfile(PROBE_DIR / name, self.probe / name)
        for name in ('prepare-product.py', 'build.mjs', 'package.json', 'package-lock.json', 'license-overrides.json'):
            shutil.copyfile(HERE / name, self.bundle / name)
        cpp = self.probe / 'src/main/cpp'
        cpp.mkdir(parents=True)
        for name in ('node_probe.cpp', 'node_snapshot_bridge.inc', 'fd_vfs.cpp', 'fd_vfs_node.cpp'):
            (cpp / name).write_text('// %s fixture\n' % name, encoding='utf-8')
        self.native.mkdir(parents=True)
        for name in ('product_entry.inc', 'synchronous_files.inc', 'bootstrap.cjs'):
            (self.native / name).write_text('// %s fixture\n' % name, encoding='utf-8')
        host = self.dsh / 'runtime/minimax-bot-host'
        host.mkdir(parents=True)
        for name in ('mobile-runtime-entry.mjs', 'mobile-filesystem.mjs'):
            (host / name).write_text('// %s fixture\n' % name, encoding='utf-8')
        sqlite = self.dsh / 'runtime/native-sqlite'
        sqlite.mkdir(parents=True)
        (sqlite / 'backup.cpp').write_text('// backup.cpp fixture\n', encoding='utf-8')

    def build(self):
        """Write a complete and mutually consistent ARM64 prepared input set."""
        headers = self.arm64_prepared / 'node-headers'
        jni = self.arm64_prepared / 'verified-jni/arm64-v8a'
        assets = self.arm64_prepared / 'verified-assets'
        third_party = assets / 'third_party'
        for directory in (headers, jni, third_party / 'nodejs-mobile',
                          third_party / 'better-sqlite3', third_party / 'ndk',
                          self.arm64_prepared / 'sqlite-probe'):
            directory.mkdir(parents=True, exist_ok=True)
        config = repr({'variables': self.config_variables}).encode('utf-8')
        (headers / 'config.gypi').write_bytes(config)
        (headers / 'node.h').write_bytes(b'/* node.h fixture */\n')
        (headers / 'node_version.h').write_bytes(''.join(
            '#define NODE_%s_VERSION %d\n' % (part, number)
            for part, number in zip(('MAJOR', 'MINOR', 'PATCH'), self.node_version)).encode('utf-8'))
        (jni / 'libnode.so').write_bytes(self.libnode)
        (jni / 'libc++_shared.so').write_bytes(self.libcxx)
        (jni / 'libmbot_node_probe.so').write_bytes(self.node_probe)
        (jni / 'libmbot_sqlite_probe.so').write_bytes(self.sqlite_probe)
        for name, data in self.extra_jni.items():
            (jni / name).write_bytes(data)
        record = dict(self.record)
        record['librarySha256'] = hashlib.sha256(self.libnode).hexdigest()
        record['configSha256'] = hashlib.sha256(config).hexdigest()
        apply_patch(record, self.record_patch)
        self.record = record
        (self.probe / 'node-arm64-candidate.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
        headers_document = {'nodeArchiveSha256': record['archiveSha256'],
                            'files': {path.relative_to(headers).as_posix(): product.sha(path)
                                      for path in sorted(headers.rglob('*')) if path.is_file()}}
        (self.arm64_prepared / 'node-headers.json').write_text(json.dumps(headers_document, indent=2) + '\n', encoding='utf-8')
        # A header file the manifest does not describe is written after the
        # manifest, so the prepared headers are stale for the product.
        for name, data in self.extra_header.items():
            (headers / name).write_bytes(data)
        info = {'source': {'repository': self.pin['mobileRepository'], 'version': self.pin['version'],
                           'tag': 'v' + self.pin['version'], 'commit': self.pin['nodeCommit'], 'tree': self.pin['nodeTree']},
                'slices': [{'target': 'arm64-v8a', 'version': self.pin['version'], 'commit': self.pin['nodeCommit'],
                            'tree': self.pin['nodeTree'], 'config_sha256': record['configSha256'],
                            'binary_sha256': {'bin/arm64-v8a/libnode.so': record['librarySha256']},
                            'versions': dict(record['versions'])}],
                'mbotSourceBuild': {**self.pin,
                                    'modifiedRecipeSha256': record['modifiedRecipeSha256'],
                                    'versionsScriptSha256': record['versionsScriptSha256'],
                                    'modifiedVersionsScriptSha256': record['modifiedVersionsScriptSha256']},
                'mbotArm64Producer': record['producer']}
        apply_patch(info, self.info_patch)
        (third_party / 'nodejs-mobile/build-info.json').write_text(json.dumps(info, indent=2) + '\n', encoding='utf-8')
        (third_party / 'nodejs-mobile/LICENSE').write_text('nodejs-mobile license fixture\n', encoding='utf-8')
        (third_party / 'ndk/NOTICE').write_text('NDK notice fixture\n', encoding='utf-8')
        sqlite = {'abi': 'arm64-v8a', 'pin': self.sqlite_pin,
                  'librarySha256': hashlib.sha256(self.sqlite_probe).hexdigest(),
                  'nodeHeaders': headers_document, 'defines': ['SQLITE_THREADSAFE=2'],
                  'modifiedAddonSha256': 'f' * 64,
                  'adapters': {'fd_vfs.cpp': product.sha(self.probe / 'src/main/cpp/fd_vfs.cpp'),
                               'fd_vfs_node.cpp': product.sha(self.probe / 'src/main/cpp/fd_vfs_node.cpp')},
                  'scope': 'verification only; explicit non-sensitive owner-granted FD use.'}
        apply_patch(sqlite, self.sqlite_patch)
        (third_party / 'better-sqlite3/build-inputs.json').write_text(json.dumps(sqlite, indent=2) + '\n', encoding='utf-8')
        validation = {'requiresIntl': True, 'requiresSqlite': True, 'requiresFdVfs': True,
                      'sourceArchiveSha256': record['archiveSha256'], 'abi': 'arm64-v8a',
                      'candidateRecord': 'node-arm64-candidate.json', 'admittedForProduct': False}
        apply_patch(validation, self.validation_patch)
        (assets / 'node-validation.json').write_text(json.dumps(validation, indent=2) + '\n', encoding='utf-8')
        if not self.missing_wrapper:
            (self.arm64_prepared / 'sqlite-probe/wrapper.cjs').write_text('module.exports = {};\n', encoding='utf-8')
        else:
            (self.arm64_prepared / 'sqlite-probe/wrapper.cjs').unlink(missing_ok=True)
        backup_root = self.prepared / 'verified-backup'
        (backup_root / 'jniLibs/arm64-v8a').mkdir(parents=True, exist_ok=True)
        (backup_root / 'third_party').mkdir(parents=True, exist_ok=True)
        (backup_root / 'third_party/NOTICE').write_text('backup notice fixture\n', encoding='utf-8')
        (backup_root / 'jniLibs/arm64-v8a/libc++_shared.so').write_bytes(self.backup_libcxx)
        (backup_root / 'jniLibs/arm64-v8a/libmbot_sqlite_backup.so').write_bytes(self.backup_library)
        backup = {'ndkRevision': NDK_REVISION, 'library': 'libmbot_sqlite_backup.so',
                  'targets': {'x86_64': 'x86_64-linux-android26', 'arm64-v8a': 'aarch64-linux-android26'},
                  'archive': {'sha256': self.sqlite_pin['sha256'], 'pinned': self.sqlite_pin['sha256']},
                  'inputs': {'backup.cpp': {'repo_path': 'runtime/native-sqlite/backup.cpp',
                                            'sha256': product.sha(self.dsh / 'runtime/native-sqlite/backup.cpp'),
                                            'bytes': (self.dsh / 'runtime/native-sqlite/backup.cpp').stat().st_size,
                                            'crlf': False, 'git_blob': None, 'git_blob_lf_sha256': None}},
                  'sqlite': {'sqlite3.c': '0' * 64, 'sqlite3.h': '0' * 64, 'defines.gypi': '0' * 64},
                  'defines': ['SQLITE_THREADSAFE=2'],
                  'libraries': {'x86_64': {'libc++_shared.so': '0' * 64, 'libmbot_sqlite_backup.so': '0' * 64},
                                'arm64-v8a': {'libc++_shared.so': hashlib.sha256(self.backup_libcxx).hexdigest(),
                                              'libmbot_sqlite_backup.so': hashlib.sha256(self.backup_library).hexdigest()}},
                  'checks': {}}
        (backup_root / 'backup-inputs.json').write_text(json.dumps(backup, indent=2) + '\n', encoding='utf-8')

    def build_mobile(self):
        """The mobile JS bundle the product assembles its assets from."""
        self.mobile.mkdir(parents=True, exist_ok=True)
        files = {'runtime.mjs': '// runtime fixture\n', 'filesystem.mjs': '// filesystem fixture\n',
                 'resources.json': '{"fixture": true}\n', 'THIRD-PARTY-NOTICES.txt': 'notices fixture\n'}
        for name, data in files.items():
            (self.mobile / name).write_text(data, encoding='utf-8')
        manifest = {'upstream': {'repository': 'https://github.com/mocode-webui/minimax-code',
                                 'commit': product.PIN_COMMIT},
                    'dependencyLockSha256': product.sha(self.bundle / 'package-lock.json'),
                    'runtimeSha256': product.sha(self.mobile / 'runtime.mjs'),
                    'filesystemSha256': product.sha(self.mobile / 'filesystem.mjs'),
                    'resourcesSha256': product.sha(self.mobile / 'resources.json'),
                    'noticesSha256': product.sha(self.mobile / 'THIRD-PARTY-NOTICES.txt'),
                    'inputs': {}}
        (self.mobile / 'mobile-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')

    def build_ndk(self):
        """A pinned NDK shape without a real compiler."""
        toolchain = self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        toolchain.mkdir(parents=True, exist_ok=True)
        (toolchain / 'clang++').write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
        (self.ndk / 'source.properties').write_text(
            'Pkg.Desc = Android NDK\nPkg.Revision = %s\n' % NDK_REVISION, encoding='utf-8')

    def fake_run(self, command, *args, **kwargs):
        """Stand in for the compiler and write a synthetic AArch64 library."""
        command = list(command)
        self.commands.append(command)
        library = Path(command[command.index('-o') + 1])
        library.parent.mkdir(parents=True, exist_ok=True)
        library.write_bytes(make_elf(needed=('libnode.so', 'libc++_shared.so', 'libc.so',
                                             'libm.so', 'libdl.so', 'liblog.so')))
        return subprocess.CompletedProcess(command, 0)

    def args(self, output, abi='arm64-v8a'):
        return argparse.Namespace(abi=abi, ndk=self.ndk, mobile_bundle=self.mobile,
                                  source_build_sha=self.record['archiveSha256'], output=output)

    def prepare_product(self, abi='arm64-v8a'):
        """Run the product preparation with the fake compiler."""
        output = self.root / ('product-' + abi)
        with patch.object(product.subprocess, 'run', side_effect=self.fake_run), \
                patch.object(product.platform, 'system', return_value='Linux'):
            product.prepare(self.args(output, abi))
        return output

    def rewrite_summary(self, output, changes):
        """Update the recorded products of a staged product, consistently."""
        path = output / 'product-inputs.json'
        summary = json.loads(path.read_text(encoding='utf-8'))
        summary['products'].update(changes)
        path.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')

    # ── the admitted candidate ───────────────────────────────────────────────

    def test_the_reviewed_candidate_is_admitted_without_running_a_command(self):
        with patch.object(product.subprocess, 'run') as compiler:
            ndk_revision, headers, backup = product.admitted_native_arm64(self.record['archiveSha256'])
        compiler.assert_not_called()
        self.assertEqual(NDK_REVISION, ndk_revision)
        self.assertEqual(self.record['archiveSha256'], headers['nodeArchiveSha256'])
        self.assertIn('arm64-v8a', backup['libraries'])
        self.assertEqual(product.sha(self.arm64_prepared / 'verified-jni/arm64-v8a/libc++_shared.so'),
                         backup['libraries']['arm64-v8a']['libc++_shared.so'])

    def test_the_arm64_product_is_prepared_from_the_reviewed_inputs(self):
        output = self.prepare_product()
        jni = output / 'jniLibs/arm64-v8a'
        for name in ('libnode.so', 'libmbot_sqlite_probe.so', 'libc++_shared.so',
                     'libmbot_sqlite_backup.so', 'libmbot_node_runtime.so'):
            self.assertTrue((jni / name).is_file(), name)
        command = self.commands[0]
        self.assertIn('--target=aarch64-linux-android26', command)
        self.assertIn('-I', command)
        self.assertIn(str(self.arm64_prepared / 'node-headers'), command)
        for argument in command:
            self.assertNotIn('x86_64-linux-android26', str(argument))
        summary = json.loads((output / 'product-inputs.json').read_text(encoding='utf-8'))
        self.assertEqual('arm64-v8a', summary['abi'])
        self.assertTrue(any(name.endswith('node-arm64-candidate.json') for name in summary['sources']))
        self.assertFalse(any(name.endswith('node-runtime-pin.json') for name in summary['sources']))
        # The x86_64 prepared inputs are never read by an ARM64 product.
        self.assertFalse((self.prepared / 'verified-jni/x86_64').exists())
        product.verify(output, 'arm64-v8a')

    # ── the archive digest ───────────────────────────────────────────────────

    def test_an_archive_digest_that_is_not_the_reviewed_one_is_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'Provide the reviewed ARM64 candidate archive SHA256'):
            product.admitted_native_arm64('0' * 64)

    def test_an_archive_digest_that_is_not_hexadecimal_is_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'Provide the reviewed ARM64 candidate archive SHA256'):
            product.admitted_native_arm64('not-a-digest')

    # ── the prepared validation record ───────────────────────────────────────

    def assert_validation_refused(self, changes):
        self.validation_patch = changes
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'full-ICU'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_a_build_without_intl_is_refused(self):
        self.assert_validation_refused({'requiresIntl': False})

    def test_a_build_without_sqlite_is_refused(self):
        self.assert_validation_refused({'requiresSqlite': None})

    def test_a_build_without_fd_vfs_is_refused(self):
        self.assert_validation_refused({'requiresFdVfs': None})

    def test_a_record_that_claims_the_product_is_admitted_is_refused(self):
        self.assert_validation_refused({'admittedForProduct': True})

    def test_a_validation_record_for_another_abi_is_refused(self):
        self.assert_validation_refused({'abi': 'x86_64'})

    def test_a_validation_record_of_another_archive_is_refused(self):
        self.assert_validation_refused({'sourceArchiveSha256': '0' * 64})

    # ── the mixed architectures ──────────────────────────────────────────────

    def test_a_stray_x86_64_library_in_the_arm64_inputs_is_refused(self):
        self.extra_jni = {'libfoo.so': X86_ELF}
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'Unexpected native library in prepared ARM64 inputs'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_an_x86_64_node_library_is_refused_as_a_stale_input(self):
        (self.arm64_prepared / 'verified-jni/arm64-v8a/libnode.so').write_bytes(X86_ELF)
        with self.assertRaisesRegex(RuntimeError, 'stale'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_an_x86_64_sqlite_library_is_refused_by_the_elf_check(self):
        self.sqlite_probe = X86_ELF
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'AArch64'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    # ── the prepared headers ─────────────────────────────────────────────────

    def test_an_extra_header_file_is_refused(self):
        self.extra_header = {'extra.h': b'/* not in the manifest */\n'}
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'Prepared ARM64 Node headers are stale'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_relabelled_x64_headers_are_refused_even_with_a_consistent_record(self):
        self.config_variables = dict(DEFAULT_CONFIG, target_arch='x64')
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'do not match Android arm64'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    # ── the prepared metadata ────────────────────────────────────────────────

    def test_a_tampered_source_build_recipe_is_refused(self):
        self.info_patch = {'mbotSourceBuild': {**self.pin, 'abi': 'x86_64'}}
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'Unexpected ARM64 source build recipe'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    # ── the prepared SQLite addon ────────────────────────────────────────────

    def test_a_sqlite_addon_of_another_abi_is_refused(self):
        self.sqlite_patch = {'abi': 'x86_64'}
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'Prepared ARM64 SQLite inputs use another ABI'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_a_sqlite_addon_of_other_node_headers_is_refused(self):
        self.sqlite_patch = {'nodeHeaders': {'nodeArchiveSha256': '0' * 64, 'files': {}}}
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'compiled against different Node headers'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_a_stale_sqlite_library_digest_is_refused(self):
        self.sqlite_patch = {'librarySha256': '0' * 64}
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'stale'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_a_missing_sqlite_wrapper_is_refused(self):
        self.missing_wrapper = True
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'Prepared ARM64 SQLite wrapper is missing'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    # ── the prepared backup libraries ────────────────────────────────────────

    def test_a_stale_backup_library_digest_is_refused(self):
        path = self.prepared / 'verified-backup/backup-inputs.json'
        backup = json.loads(path.read_text(encoding='utf-8'))
        backup['libraries']['arm64-v8a']['libmbot_sqlite_backup.so'] = '0' * 64
        path.write_text(json.dumps(backup, indent=2) + '\n', encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'stale'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_an_x86_64_backup_library_is_refused_by_the_elf_check(self):
        self.backup_library = X86_ELF
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'AArch64'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_an_extra_backup_library_is_refused(self):
        path = self.prepared / 'verified-backup/backup-inputs.json'
        backup = json.loads(path.read_text(encoding='utf-8'))
        backup['libraries']['arm64-v8a']['libextra.so'] = '0' * 64
        path.write_text(json.dumps(backup, indent=2) + '\n', encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'Unexpected native library in prepared ARM64 inputs'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    def test_a_backup_runtime_that_differs_from_the_prepared_one_is_refused(self):
        self.backup_libcxx = make_elf(api=21, needed=('libc.so',))
        self.build()
        with self.assertRaisesRegex(RuntimeError, 'different C\\+\\+ runtime'):
            product.admitted_native_arm64(self.record['archiveSha256'])

    # ── the staged product ───────────────────────────────────────────────────

    def test_a_stray_x86_64_library_in_an_arm64_product_is_refused(self):
        output = self.prepare_product()
        stray = output / 'jniLibs/x86_64/x.so'
        stray.parent.mkdir(parents=True)
        stray.write_bytes(X86_ELF)
        self.rewrite_summary(output, {'jniLibs/x86_64/x.so': product.sha(stray)})
        with self.assertRaisesRegex(RuntimeError, 'Prepared product assets or native libraries are stale'):
            product.verify(output, 'arm64-v8a')

    def test_an_arm64_product_library_with_a_small_page_size_is_refused(self):
        output = self.prepare_product()
        library = output / 'jniLibs/arm64-v8a/libmbot_node_runtime.so'
        library.write_bytes(make_elf(align=0x1000, needed=('libnode.so', 'libc.so')))
        self.rewrite_summary(output, {'jniLibs/arm64-v8a/libmbot_node_runtime.so': product.sha(library)})
        with self.assertRaisesRegex(RuntimeError, 'alignment'):
            product.verify(output, 'arm64-v8a')

    # ── the ABI of the product ───────────────────────────────────────────────

    def test_an_unknown_abi_is_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'Unknown product ABI'):
            product.prepare(self.args(self.root / 'product', abi='mips64'))

    def test_the_x86_64_product_still_uses_the_x86_64_gate(self):
        sentinel = RuntimeError('the x86_64 gate was used')
        arm64_sentinel = RuntimeError('the ARM64 gate was used')
        with patch.object(product, 'admitted_native', side_effect=sentinel), \
                patch.object(product, 'admitted_native_arm64', side_effect=arm64_sentinel):
            with self.assertRaises(RuntimeError) as raised:
                product.prepare(self.args(self.root / 'product', abi='x86_64'))
        self.assertIs(sentinel, raised.exception)


if __name__ == '__main__':
    unittest.main()
