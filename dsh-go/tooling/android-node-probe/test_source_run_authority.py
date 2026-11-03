# SPDX-License-Identifier: AGPL-3.0-only
"""An arbitrary Actions artifact must not become the embedded native runtime."""
import base64
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest


class SourceRunAuthorityTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('source_run', Path(__file__).with_name('verify-source-run.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in self.module.INPUTS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(('fixed source ' + name).encode())
        self.run_metadata = dict(id=123, head_repository={'full_name': 'owner/repo'}, head_branch='feat/t-plugins',
                        path=self.module.SOURCE_WORKFLOW, event='push', head_sha='a' * 40)
        self.build = dict(name='build', status='completed', conclusion='success')
        self.calls = []

    def api(self, endpoint):
        self.calls.append(endpoint)
        if '/contents/' in endpoint:
            name = endpoint.split('/contents/', 1)[1].split('?', 1)[0]
            return dict(encoding='base64', content=base64.b64encode((self.root / name).read_bytes()).decode())
        if '/jobs?' in endpoint:
            return dict(jobs=[self.build, dict(name='verify (26)', status='in_progress')])
        return copy.deepcopy(self.run_metadata)

    def verify(self, run_id='123'):
        self.module.verify(run_id, 'owner/repo', 'feat/t-plugins', self.api, self.root)

    def test_completed_producer_with_identical_inputs_can_be_retested(self):
        self.verify()
        self.assertEqual(len(self.calls), 5)

    def test_invalid_id_never_reaches_network(self):
        for value in ['../123', '123?x=1', '0', '', '1' * 21]:
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'source_run_invalid'):
                self.verify(value)
        self.assertEqual(self.calls, [])

    def test_fork_wrong_branch_workflow_and_event_are_refused(self):
        original = copy.deepcopy(self.run_metadata)
        for key, value in [('head_repository', {'full_name': 'attacker/repo'}), ('head_branch', 'main'),
                           ('path', '.github/workflows/unrelated.yml'), ('event', 'pull_request'),
                           ('head_sha', 'not-a-commit'), ('id', 456)]:
            self.run_metadata = {**original, key: value}
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'source_run_untrusted'):
                self.verify()

    def test_unsuccessful_producer_is_refused_even_with_an_artifact(self):
        for status, conclusion in [('in_progress', None), ('completed', 'failure'), ('completed', 'cancelled')]:
            self.build.update(status=status, conclusion=conclusion)
            with self.subTest(status=status, conclusion=conclusion), self.assertRaisesRegex(ValueError, 'source_build_not_successful'):
                self.verify()

    def test_changed_recipe_or_workflow_cannot_reuse_old_binary(self):
        original_api = self.api
        for name in self.module.INPUTS:
            def api(endpoint):
                blob = original_api(endpoint)
                if '/contents/' + name + '?' in endpoint:
                    blob['content'] = base64.b64encode(b'different producer input').decode()
                return blob
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'source_build_inputs_changed'):
                self.module.verify('123', 'owner/repo', 'feat/t-plugins', api, self.root)
