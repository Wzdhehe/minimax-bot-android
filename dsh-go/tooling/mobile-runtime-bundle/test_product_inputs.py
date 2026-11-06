# SPDX-License-Identifier: AGPL-3.0-only
"""Build gate tests; these do not claim to boot Android or load an engine."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('prepare_product', ROOT / 'prepare-product.py')
product = importlib.util.module_from_spec(spec)
spec.loader.exec_module(product)


class ProductInputsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / 'product'
        (self.output / 'assets/mbot').mkdir(parents=True)
        (self.output / 'jniLibs/x86_64').mkdir(parents=True)
        self.source = self.root / 'native.cpp'
        self.source.write_text('source before preparation', encoding='utf-8')
        (self.output / 'assets/mbot/runtime.mjs').write_text('bundled product', encoding='utf-8')
        (self.output / 'jniLibs/x86_64/libnode.so').write_bytes(b'native fixture, never loaded')
        self.summary = {'schemaVersion': 1, 'abi': 'x86_64', 'pinCommit': product.PIN_COMMIT,
                        'sources': {'native.cpp': product.sha(self.source)},
                        'products': {'assets/mbot/runtime.mjs': product.sha(self.output / 'assets/mbot/runtime.mjs'),
                                     'jniLibs/x86_64/libnode.so': product.sha(self.output / 'jniLibs/x86_64/libnode.so')}}
        self.write()
        self.scope = patch.object(product, 'DSH', self.root)
        self.scope.start()
        self.addCleanup(self.scope.stop)

    def write(self):
        (self.output / 'product-inputs.json').write_text(json.dumps(self.summary), encoding='utf-8')

    def test_exact_inventory_is_accepted(self):
        product.verify(self.output, 'x86_64')

    def test_changed_compiled_source_is_refused(self):
        self.source.write_text('different native source', encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'stale'):
            product.verify(self.output, 'x86_64')

    def test_replaced_library_is_refused(self):
        (self.output / 'jniLibs/x86_64/libnode.so').write_bytes(b'wrong library')
        with self.assertRaisesRegex(RuntimeError, 'stale'):
            product.verify(self.output, 'x86_64')

    def test_extra_native_payload_is_refused(self):
        (self.output / 'jniLibs/x86_64/unapproved.node').write_bytes(b'extra desktop addon')
        with self.assertRaisesRegex(RuntimeError, 'stale'):
            product.verify(self.output, 'x86_64')

    def test_another_abi_cannot_use_the_x64_product(self):
        with self.assertRaisesRegex(RuntimeError, 'ABI'):
            product.verify(self.output, 'arm64-v8a')

    def test_source_inventory_cannot_escape_repository(self):
        self.summary['sources'] = {'../outside.cpp': '0' * 64}
        self.write()
        with self.assertRaisesRegex(RuntimeError, 'source path'):
            product.verify(self.output, 'x86_64')

    def test_no_intl_input_is_refused_before_copy_or_compilation(self):
        with patch.object(product, 'document', side_effect=[{}, {'requiresIntl': False}]), \
                patch.object(product.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'full-ICU'):
                product.admitted_native('a' * 64)
            compiler.assert_not_called()


if __name__ == '__main__':
    unittest.main()
