#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Authorize one independent ARM64 Actions producer before downloading it.

This does not admit the archive into prepare-product.py, download binaries or
assert Android execution. The returned digest describes GitHub's outer artifact
ZIP, not the inner node-full-icu-arm64.zip or libnode.so.
"""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
SOURCE_WORKFLOW = '.github/workflows/android-node-arm64-source.yml'
ARTIFACT_NAME = 'node-full-icu-arm64-candidate'
INPUTS = (SOURCE_WORKFLOW,
          'dsh-go/tooling/android-node-probe/node-arm64-source-build.json',
          'dsh-go/tooling/android-node-probe/build-arm64-source.py',
          'dsh-go/tooling/android-node-probe/build-source.py',
          'dsh-go/tooling/android-node-probe/node-source-build.json',
          'dsh-go/tooling/android-node-probe/test_arm64_source.py',
          'dsh-go/tooling/android-node-probe/test_source_recipe.py')


def verify(run_id, repository, branch, api, root=ROOT):
    if not isinstance(run_id, str) or not re.fullmatch(r'[1-9][0-9]{0,19}', run_id):
        raise ValueError('arm64_source_run_invalid')
    if repository != 'Wzdhehe/minimax-bot-android' or branch != 'feat/t-plugins':
        raise ValueError('arm64_source_authority_invalid')
    base = f'repos/{repository}/actions/runs/{run_id}'
    run = api(base)
    commit = run.get('head_sha', '')
    if (str(run.get('id')) != run_id or run.get('head_repository', {}).get('full_name') != repository or
            run.get('head_branch') != branch or run.get('path') != SOURCE_WORKFLOW or
            run.get('event') not in ('push', 'workflow_dispatch') or not isinstance(commit, str) or not re.fullmatch('[0-9a-f]{40}', commit) or
            run.get('status') != 'completed' or run.get('conclusion') != 'success'):
        raise ValueError('arm64_source_run_untrusted')
    jobs = api(base + '/jobs?per_page=100')
    rows = jobs.get('jobs', [])
    builds = [job for job in rows if job.get('name') == 'build']
    if jobs.get('total_count') != len(rows) or len(builds) != 1 or builds[0].get('status') != 'completed' or builds[0].get('conclusion') != 'success':
        raise ValueError('arm64_source_build_not_successful')
    for name in INPUTS:
        blob = api(f'repos/{repository}/contents/{name}?ref={commit}')
        if blob.get('encoding') != 'base64' or not isinstance(blob.get('content'), str):
            raise ValueError('arm64_source_inputs_invalid')
        try:
            content = base64.b64decode(''.join(blob['content'].split()), validate=True)
        except (KeyError, ValueError, TypeError):
            raise ValueError('arm64_source_inputs_invalid') from None
        if content != (root / name).read_bytes():
            raise ValueError('arm64_source_inputs_changed')
    record = api(base + '/artifacts?per_page=100')
    artifacts = record.get('artifacts', [])
    if record.get('total_count') != len(artifacts):
        raise ValueError('arm64_source_artifacts_incomplete')
    candidates = [item for item in artifacts if item.get('name') == ARTIFACT_NAME]
    if len(candidates) != 1:
        raise ValueError('arm64_source_artifact_invalid')
    candidate = candidates[0]
    identity = candidate.get('workflow_run', {})
    size = candidate.get('size_in_bytes')
    digest = candidate.get('digest', '')
    if (type(candidate.get('id')) is not int or candidate['id'] <= 0 or candidate.get('expired') is not False or
            identity.get('id') != int(run_id) or identity.get('head_sha') != commit or identity.get('head_branch') != branch or
            type(size) is not int or not 1024 * 1024 <= size <= 512 * 1024 * 1024 or
            not isinstance(digest, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest)):
        raise ValueError('arm64_source_artifact_invalid')
    return {'schemaVersion': 1, 'abi': 'arm64-v8a', 'repository': repository,
            'runId': int(run_id), 'commit': commit, 'artifactId': candidate['id'],
            'artifactName': ARTIFACT_NAME, 'outerArtifactSha256': digest.removeprefix('sha256:'),
            'status': 'source-candidate-only'}


def github_api(endpoint):
    return json.loads(subprocess.check_output(['gh', 'api', endpoint], stderr=subprocess.DEVNULL))


if __name__ == '__main__':
    try:
        receipt = verify(sys.argv[1], os.environ.get('GITHUB_REPOSITORY', ''),
                         os.environ.get('GITHUB_REF_NAME', ''), github_api)
    except Exception:
        sys.exit('arm64_source_build_artifact_refused')
    # No credentials, signed URLs, environment, compiler logs or archive data.
    print(json.dumps(receipt, sort_keys=True))
