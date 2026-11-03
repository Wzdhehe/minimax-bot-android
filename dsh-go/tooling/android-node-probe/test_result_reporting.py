# SPDX-License-Identifier: AGPL-3.0-only
"""Android XML body/attribute diagnostics must not become a credential log."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET


class ResultReportingTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('result_reporting', Path(__file__).with_name('report-results.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def run_report(self, message, *, attribute=False):
        suite = ET.Element('testsuite', tests='1', failures='1', errors='0', skipped='0')
        case = ET.SubElement(suite, 'testcase')
        failure = ET.SubElement(case, 'failure')
        if attribute:
            failure.set('message', message)
        else:
            failure.text = message
        ET.ElementTree(suite).write(self.root / 'results.xml')
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = self.module.report(self.root)
        return code, output.getvalue()

    def test_android_body_and_junit_attribute_both_emit_only_finite_identifiers(self):
        secret = 'generated-test-marker-not-a-real-key'
        for attribute in (False, True):
            with self.subTest(attribute=attribute):
                code, output = self.run_report(secret + '\nnode_probe_failed_at_vfs_open_code_SQLITE_CANTOPEN\n'
                    'at x(IsolatedNodeModelTest.kt:153)\nresponse=' + secret, attribute=attribute)
                self.assertEqual(code, 1)
                self.assertIn('::error::Node probe phase=vfs_open code=SQLITE_CANTOPEN', output)
                self.assertIn('IsolatedNodeModelTest.kt:153', output)
                self.assertNotIn(secret, output)
                self.assertNotIn('response=', output)

    def test_unknown_exception_and_secret_fields_never_print(self):
        code, output = self.run_report('private-value node_probe_failed_at_injected_code_private_value')
        self.assertEqual(code, 1)
        self.assertNotIn('private', output)
        self.assertNotIn('phase=', output)
        self.assertIn('Node probe counts tests=1 failures=1', output)

    def test_no_results_is_failure(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(self.module.report(self.root), 1)

    def test_non_skipped_success_is_required(self):
        for skipped, expected in [(0, 0), (1, 1)]:
            ET.ElementTree(ET.Element('testsuite', tests='1', failures='0', errors='0', skipped=str(skipped))).write(self.root / 'results.xml')
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(self.module.report(self.root), expected)

    def test_namespace_and_suite_level_nested_stack_are_reported_without_raw_text(self):
        suite = ET.Element('{urn:android-test}testsuite', tests='1', failures='1', errors='0', skipped='0')
        failure = ET.SubElement(suite, '{urn:android-test}failure')
        ET.SubElement(failure, '{urn:android-test}stack').text = (
            'private-sentinel java.util.concurrent.TimeoutException at x(IsolatedNodeModelTest.kt:134)')
        ET.ElementTree(suite).write(self.root / 'results.xml')
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(self.module.report(self.root), 1)
        self.assertIn('failure kind=timeout', output.getvalue())
        self.assertIn('IsolatedNodeModelTest.kt:134', output.getvalue())
        self.assertNotIn('private-sentinel', output.getvalue())

    def test_artifact_metadata_and_action_output_contain_only_fixed_status(self):
        self.run_report('private-sentinel node_probe_failed_at_vfs_open_code_SQLITE_CANTOPEN at x(IsolatedNodeModelTest.kt:153)')
        status, outputs = self.root / 'status.txt', self.root / 'outputs.txt'
        with contextlib.redirect_stdout(io.StringIO()):
            self.module.report(self.root, status, outputs)
        self.assertEqual(status.read_text(), 't1-f1-e0-s0-vfs_open-SQLITE_CANTOPEN-l153\n')
        self.assertEqual(outputs.read_text(), 'result_id=' + status.read_text())
        self.assertNotIn('private-sentinel', outputs.read_text())
