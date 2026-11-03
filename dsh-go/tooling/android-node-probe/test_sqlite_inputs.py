# SPDX-License-Identifier: AGPL-3.0-only
"""Reject changed SQLite/header inputs before native compilation."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


class SqliteInputTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('sqlite_probe', HERE / 'prepare-sqlite.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.module.ROOT = self.root
        self.ndk = self.root / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = ' + self.module.PIN['ndkVersion'])

    def archive(self, unsafe=None):
        path = self.root / 'sqlite.tgz'
        with tarfile.open(path, 'w:gz') as package:
            data = json.dumps({'name': self.module.PIN['package'], 'version': self.module.PIN['version']}).encode()
            member = tarfile.TarInfo('package/package.json')
            member.size = len(data)
            package.addfile(member, io.BytesIO(data))
            if unsafe:
                member = tarfile.TarInfo(unsafe)
                member.size = 1
                package.addfile(member, io.BytesIO(b'x'))
        self.module.PIN = {**self.module.PIN, 'sha256': self.module.digest(path)}
        return path

    def headers(self, *, sandbox=0):
        headers = self.root / 'build/node-headers'
        headers.mkdir(parents=True)
        (headers / 'node.h').write_text('header fixture')
        (headers / 'node_version.h').write_text('#define NODE_MAJOR_VERSION 24\n#define NODE_MINOR_VERSION 22\n#define NODE_PATCH_VERSION 2\n')
        (headers / 'config.gypi').write_text(repr({'variables': {
            'v8_enable_pointer_compression': 0, 'v8_enable_sandbox': sandbox, 'v8_enable_31bit_smis_on_64bit_arch': 0}}))
        files = {path.name: self.module.digest(path) for path in headers.iterdir()}
        (self.root / 'build/node-headers.json').write_text(json.dumps({'files': files}))
        return headers

    def test_changed_archive_cannot_run_a_compiler(self):
        archive = self.archive()
        archive.write_bytes(archive.read_bytes() + b'changed')
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'archive digest mismatch'):
                self.module.prepare(self.ndk, archive, Path('/unused/esbuild'))
            compiler.assert_not_called()

    def test_tar_traversal_is_rejected_without_writing_outside_the_source(self):
        archive = self.archive('../outside')
        with self.assertRaisesRegex(RuntimeError, 'Unsafe SQLite archive path'):
            self.module.extract(archive, self.root / 'source')
        self.assertFalse((self.root / 'outside').exists())

    def test_modified_node_header_is_rejected_before_compile(self):
        archive = self.archive()
        headers = self.headers()
        (headers / 'node.h').write_text('tampered header')
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'Node headers changed'):
                self.module.prepare(self.ndk, archive, Path('/unused/esbuild'))
            compiler.assert_not_called()

    def test_incompatible_v8_layout_is_rejected_before_compile(self):
        archive = self.archive()
        self.headers(sandbox=1)
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'V8 ABI configuration'):
                self.module.prepare(self.ndk, archive, Path('/unused/esbuild'))
            compiler.assert_not_called()


if __name__ == '__main__':
    unittest.main()
