# SPDX-License-Identifier: AGPL-3.0-only
"""Source authority checks, not a compiled ARM64 binary or device test."""
import base64
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest


class Arm64RunAuthorityTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('arm64_authority', Path(__file__).with_name('verify-arm64-source-run.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.expected = {}
        for name in self.module.INPUTS:
            content = ('fixed ARM64 source ' + name).encode()
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            self.expected[name] = content
        self.run = dict(id=123, head_repository={'full_name': 'Wzdhehe/minimax-bot-android'},
                        head_branch='feat/t-plugins', path=self.module.SOURCE_WORKFLOW,
                        head_sha='a' * 40, event='push', status='completed', conclusion='success')
        self.jobs = dict(total_count=1, jobs=[dict(name='build', status='completed', conclusion='success')])
        self.artifact = dict(id=456, name=self.module.ARTIFACT_NAME, expired=False, size_in_bytes=45_000_000,
                             digest='sha256:' + 'b' * 64,
                             workflow_run=dict(id=123, head_sha='a' * 40, head_branch='feat/t-plugins'))
        self.artifacts = dict(total_count=1, artifacts=[self.artifact])
        self.calls = []

    def api(self, endpoint):
        self.calls.append(endpoint)
        if '/contents/' in endpoint:
            name = endpoint.split('/contents/', 1)[1].split('?', 1)[0]
            return dict(encoding='base64', content=base64.b64encode(self.expected[name]).decode())
        if '/jobs?' in endpoint:
            return copy.deepcopy(self.jobs)
        if '/artifacts?' in endpoint:
            return copy.deepcopy(self.artifacts)
        return copy.deepcopy(self.run)

    def verify(self, run_id='123', repository='Wzdhehe/minimax-bot-android', branch='feat/t-plugins', api=None):
        return self.module.verify(run_id, repository, branch, api or self.api, self.root)

    def test_same_repository_build_and_exact_inputs_produce_candidate_receipt_only(self):
        result = self.verify()
        self.assertEqual(result['status'], 'source-candidate-only')
        self.assertEqual(result['abi'], 'arm64-v8a')
        self.assertEqual(result['commit'], 'a' * 40)
        self.assertEqual(result['artifactId'], 456)
        self.assertEqual(result['outerArtifactSha256'], 'b' * 64)
        self.assertNotIn('sourceArchiveSha256', result, 'the outer and inner ZIP digests are different')
        self.assertNotIn('androidVerified', result)
        self.assertTrue(all('/contents/' + name + '?ref=' in '\n'.join(self.calls) for name in self.module.INPUTS))
        self.assertFalse(any('/zip' in call or 'download' in call for call in self.calls))

    def test_manual_same_branch_run_is_also_allowed(self):
        self.run['event'] = 'workflow_dispatch'
        self.verify()

    def test_invalid_run_or_authority_never_reaches_the_network(self):
        for run_id in (None, 123, '0', '', '../123', '123?x=1', '1' * 21):
            with self.subTest(run_id=run_id), self.assertRaises(ValueError):
                self.verify(run_id)
        for repo, branch in [('attacker/repo', 'feat/t-plugins'), ('Wzdhehe/minimax-bot-android', 'main')]:
            with self.subTest(repo=repo, branch=branch), self.assertRaises(ValueError):
                self.verify(repository=repo, branch=branch)
        self.assertEqual(self.calls, [])

    def test_x64_or_fork_or_unfinished_runs_cannot_authorize_an_arm64_archive(self):
        original = copy.deepcopy(self.run)
        for key, value in [('path', '.github/workflows/android-node-source.yml'),
                           ('head_repository', {'full_name': 'fork/repo'}), ('head_branch', 'main'),
                           ('event', 'pull_request'), ('id', 124), ('head_sha', 'invalid'),
                           ('status', 'in_progress'), ('conclusion', 'failure')]:
            self.run = {**original, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.verify()

    def test_failed_cancelled_or_duplicate_producer_jobs_are_refused(self):
        original = copy.deepcopy(self.jobs)
        for field, value in [('status', 'in_progress'), ('conclusion', 'failure'), ('conclusion', 'cancelled'),
                             ('conclusion', 'skipped')]:
            self.jobs = copy.deepcopy(original)
            self.jobs['jobs'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.verify()
        self.jobs = dict(total_count=2, jobs=original['jobs'] * 2)
        with self.assertRaises(ValueError):
            self.verify()

    def test_truncated_api_lists_are_not_treated_as_complete_authority(self):
        self.jobs['total_count'] = 101
        with self.assertRaises(ValueError):
            self.verify()
        self.jobs['total_count'] = 1
        self.artifacts['total_count'] = 101
        with self.assertRaises(ValueError):
            self.verify()

    def test_each_changed_recipe_or_workflow_refuses_the_old_artifact(self):
        for name in self.module.INPUTS:
            original = self.expected[name]
            self.expected[name] = b'different producer recipe'
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'inputs_changed'):
                self.verify()
            self.expected[name] = original

    def test_wrong_blob_encoding_or_invalid_base64_is_refused(self):
        for blob in [dict(encoding='utf-8', content='text'), dict(encoding='base64', content='@@@@'), dict(encoding='base64', content=None)]:
            def api(endpoint):
                return blob if '/contents/' in endpoint else self.api(endpoint)
            with self.subTest(blob=blob), self.assertRaises(ValueError):
                self.verify(api=api)

    def test_line_wrapped_base64_preserves_exact_producer_bytes(self):
        def api(endpoint):
            result = self.api(endpoint)
            if '/contents/' in endpoint:
                result['content'] = '\n'.join(result['content'][i:i + 60] for i in range(0, len(result['content']), 60))
            return result
        self.verify(api=api)

    def test_expired_mislabelled_malformed_or_foreign_artifacts_are_refused(self):
        original = copy.deepcopy(self.artifact)
        changes = [('expired', True), ('expired', None), ('id', True), ('size_in_bytes', 100),
                   ('size_in_bytes', 1024 ** 3), ('size_in_bytes', '45000000'), ('digest', None),
                   ('digest', 'sha256:not-a-digest'), ('name', 'node-full-icu-verification-input'),
                   ('workflow_run', dict(id=999, head_sha='a' * 40, head_branch='feat/t-plugins')),
                   ('workflow_run', dict(id=123, head_sha='c' * 40, head_branch='feat/t-plugins')),
                   ('workflow_run', dict(id=123, head_sha='a' * 40, head_branch='main'))]
        for key, value in changes:
            self.artifacts['artifacts'] = [{**original, key: value}]
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.verify()

    def test_missing_or_duplicate_candidate_cannot_be_selected_arbitrarily(self):
        for artifacts in ([], [self.artifact, self.artifact]):
            self.artifacts = dict(total_count=len(artifacts), artifacts=artifacts)
            with self.subTest(count=len(artifacts)), self.assertRaises(ValueError):
                self.verify()


if __name__ == '__main__':
    unittest.main()
