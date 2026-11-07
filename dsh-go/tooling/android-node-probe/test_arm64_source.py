# SPDX-License-Identifier: AGPL-3.0-only
"""Producer admission tests, not compilation or ARM64 device evidence."""
import ast
import copy
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('arm64_source', Path(__file__).with_name('build-arm64-source.py'))
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class Arm64SourceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='mbot arm64 producer ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source'
        self.destination = self.root / 'output'
        self.source.mkdir()
        (self.destination / 'include/node').mkdir(parents=True)
        (self.destination / 'bin/arm64-v8a').mkdir(parents=True)
        self.config = {'OS': 'android', 'target_arch': 'arm64', 'node_shared': 'true',
                       'v8_enable_i18n_support': 1, 'icu_small': 0}
        self.write_config()
        header = bytearray(64)
        header[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<HH', header, 16, 3, 183)
        self.binary = self.destination / 'bin/arm64-v8a/libnode.so'
        self.binary.write_bytes(header)
        self.record = {'version': producer.PIN['version'], 'commit': producer.PIN['nodeCommit'],
                       'tree': producer.PIN['nodeTree'], 'target': 'arm64-v8a',
                       'config_sha256': producer.shared.sha(self.source / 'config.gypi'),
                       'binary_sha256': {'bin/arm64-v8a/libnode.so': producer.shared.sha(self.binary)},
                       'versions': {'modules': '137', 'icu': '77.1', 'unicode': '16.0'}}
        self.write_record()
        self.git = patch.object(producer.shared, 'git', return_value=producer.PIN['nodeTree'])
        self.git.start()
        self.addCleanup(self.git.stop)

    def write_config(self):
        for path in (self.source / 'config.gypi', self.destination / 'include/node/config.gypi'):
            path.write_text(repr({'variables': self.config}), encoding='utf-8')

    def write_record(self):
        (self.destination / 'build-info.json').write_text(json.dumps(self.record), encoding='utf-8')

    def test_pin_and_target_selection_never_modify_x64_authority(self):
        before = copy.deepcopy(producer.shared.PIN)
        producer.check_pins()
        argv = producer.command(self.root / 'recipe with spaces.py', self.root, self.root / 'NDK with spaces', 2)
        self.assertEqual(argv[argv.index('--arch') + 1], 'arm64')
        self.assertEqual(argv[argv.index('--ndk') + 1], str(self.root / 'NDK with spaces'))
        self.assertTrue(argv[argv.index('--source') + 1].endswith('mbot-source-full-icu-arm64'))
        self.assertTrue(argv[argv.index('--output') + 1].endswith('mbot-full-icu-arm64'))
        self.assertEqual(producer.shared.PIN, before)
        for jobs in (0, 33, '2'):
            with self.assertRaises(RuntimeError):
                producer.command(self.root, self.root, self.root, jobs)

    def test_correct_fixture_is_accepted_without_becoming_device_evidence(self):
        self.assertEqual(producer.validate_result(self.source, self.destination), self.record)

    def test_staged_mobile_patches_are_checked_instead_of_unpatched_head(self):
        self.git.stop()
        def git(*args):
            return subprocess.check_output(['git', '-C', str(self.source), *args], stderr=subprocess.PIPE).decode().strip()
        git('init', '--quiet')
        git('config', 'user.name', 'Build fixture')
        git('config', 'user.email', 'fixture@example.invalid')
        hooks = self.root / 'empty-hooks'
        hooks.mkdir()
        git('config', 'core.hooksPath', str(hooks))
        tracked = self.source / 'tracked.cpp'
        tracked.write_text('upstream\n', encoding='utf-8')
        git('add', 'tracked.cpp')
        git('commit', '--quiet', '-m', 'upstream fixture')
        tracked.write_text('staged mobile patch\n', encoding='utf-8')
        git('add', 'tracked.cpp')
        tree = git('write-tree')
        self.assertNotEqual(tree, git('rev-parse', 'HEAD^{tree}'))
        self.record['tree'] = tree
        self.write_record()
        with patch.dict(producer.PIN, {'nodeTree': tree}):
            producer.validate_result(self.source, self.destination)
            tracked.write_text('unstaged source drift\n', encoding='utf-8')
            with self.assertRaises(subprocess.CalledProcessError):
                producer.validate_result(self.source, self.destination)

    def test_x64_configuration_cannot_be_relabelled_arm64(self):
        self.config['target_arch'] = 'x64'
        self.write_config()
        with self.assertRaisesRegex(RuntimeError, 'not shared Android ARM64'):
            producer.validate_result(self.source, self.destination)

    def test_no_intl_and_small_icu_are_refused(self):
        for intl, small in ((0, 0), (1, 1)):
            self.config.update(v8_enable_i18n_support=intl, icu_small=small)
            self.write_config()
            with self.assertRaisesRegex(RuntimeError, 'full ICU'):
                producer.validate_result(self.source, self.destination)

    def test_wrong_elf_architecture_and_executable_are_refused(self):
        for kind, machine in ((3, 62), (2, 183)):
            header = bytearray(self.binary.read_bytes())
            struct.pack_into('<HH', header, 16, kind, machine)
            self.binary.write_bytes(header)
            with self.assertRaisesRegex(RuntimeError, 'ELF64 AArch64'):
                producer.validate_result(self.source, self.destination)

    def test_replaced_library_is_refused(self):
        self.binary.write_bytes(self.binary.read_bytes() + b'tampered')
        with self.assertRaisesRegex(RuntimeError, 'library digest'):
            producer.validate_result(self.source, self.destination)

    def test_replaced_packaged_configuration_is_refused(self):
        (self.destination / 'include/node/config.gypi').write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'configuration digest'):
            producer.validate_result(self.source, self.destination)

    def test_missing_icu_report_or_wrong_node_abi_is_refused(self):
        for versions in ({'modules': '137'}, {'modules': '136', 'icu': '77.1', 'unicode': '16.0'}):
            self.record['versions'] = versions
            self.write_record()
            with self.assertRaisesRegex(RuntimeError, 'ABI or actual ICU'):
                producer.validate_result(self.source, self.destination)


if __name__ == '__main__':
    unittest.main()
