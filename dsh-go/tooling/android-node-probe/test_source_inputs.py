# SPDX-License-Identifier: AGPL-3.0-only
"""Refuse untrusted/mislabeled build inputs before any native compiler runs."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent


class SourceInputTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('node_prepare_test', HERE / 'prepare-probe.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module.ROOT = self.root / 'probe'
        self.module.ROOT.mkdir()
        self.source_pin = json.loads((HERE / 'node-source-build.json').read_text())
        (self.module.ROOT / 'node-source-build.json').write_text(json.dumps(self.source_pin))
        self.ndk = self.root / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = ' + self.module.PIN['ndkRevision'] + '\n')

    def archive(self, *, bad_recipe=False, unsafe=False):
        binary = b'not-an-executable-test-fixture'
        config = b"{'variables': {'v8_enable_i18n_support': 0, 'icu_small': False}}"
        build = dict(self.source_pin)
        if bad_recipe:
            build['mobileCommit'] = '0' * 40
        name = f"bin/{self.module.PIN['abi']}/libnode.so"
        metadata = {
            'source': {'commit': self.module.PIN['nodeCommit'], 'tree': self.module.PIN['nodeTree'], 'version': self.module.PIN['version']},
            'mbotSourceBuild': build,
            'slices': [{'target': self.module.PIN['abi'], 'binary_sha256': {name: hashlib.sha256(binary).hexdigest()},
                        'config_sha256': hashlib.sha256(config).hexdigest()}],
        }
        path = self.root / 'source.zip'
        with zipfile.ZipFile(path, 'w') as package:
            package.writestr('build-info.json', json.dumps(metadata))
            if unsafe:
                package.writestr('../outside-marker', 'must not be written')
            package.writestr(name, binary)
            package.writestr('include/node/config.gypi', config)
            package.writestr('LICENSE', 'fixture notice')
            package.writestr('LICENSE-nodejs-mobile', 'fixture notice')
        return path, self.module.digest(path)

    def test_archive_sha_is_required_before_extracting_or_compiling(self):
        archive, _ = self.archive()
        with patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'digest mismatch'):
                self.module.prepare(self.ndk, archive, '0' * 64)
            compiler.assert_not_called()
        self.assertFalse((self.module.ROOT / 'build').exists())

    def test_a_different_source_recipe_cannot_masquerade_as_the_pinned_build(self):
        archive, sha = self.archive(bad_recipe=True)
        with patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'source build recipe'):
                self.module.prepare(self.ndk, archive, sha)
            compiler.assert_not_called()

    def test_zip_members_cannot_escape_the_generated_directories(self):
        archive, sha = self.archive(unsafe=True)
        with patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'Unsafe archive member'):
                self.module.prepare(self.ndk, archive, sha)
            compiler.assert_not_called()
        self.assertFalse((self.root / 'outside-marker').exists())

    def test_a_library_without_icu_cannot_pass_the_full_icu_path(self):
        archive, sha = self.archive()
        with patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'include full ICU'):
                self.module.prepare(self.ndk, archive, sha)
            compiler.assert_not_called()


if __name__ == '__main__':
    unittest.main()
