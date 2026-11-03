#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Authorize an existing same-branch source-build artifact before downloading it."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
SOURCE_WORKFLOW = '.github/workflows/android-node-source.yml'
INPUTS = (SOURCE_WORKFLOW, 'dsh-go/tooling/android-node-probe/node-source-build.json',
          'dsh-go/tooling/android-node-probe/build-source.py')


def verify(run_id, repository, branch, api, root=ROOT):
    if not re.fullmatch(r'[1-9][0-9]{0,19}', run_id):
        raise ValueError('source_run_invalid')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or branch != 'feat/t-plugins':
        raise ValueError('source_run_authority_invalid')
    run = api(f'repos/{repository}/actions/runs/{run_id}')
    sha = run.get('head_sha', '')
    if (str(run.get('id')) != run_id or run.get('head_repository', {}).get('full_name') != repository or
            run.get('head_branch') != branch or run.get('path') != SOURCE_WORKFLOW or
            run.get('event') not in ('push', 'workflow_dispatch') or not re.fullmatch('[0-9a-f]{40}', sha)):
        raise ValueError('source_run_untrusted')
    jobs = api(f'repos/{repository}/actions/runs/{run_id}/jobs?per_page=100').get('jobs', [])
    builds = [job for job in jobs if job.get('name') == 'build']
    # The old verify job may fail or still be running; the producer must have
    # completed successfully, including its artifact upload. This is a test
    # input, never approval to ship it or a claim of device compatibility.
    if len(builds) != 1 or builds[0].get('status') != 'completed' or builds[0].get('conclusion') != 'success':
        raise ValueError('source_build_not_successful')
    for name in INPUTS:
        blob = api(f'repos/{repository}/contents/{name}?ref={sha}')
        try:
            content = base64.b64decode(blob['content'], validate=False)
        except (KeyError, ValueError, TypeError):
            raise ValueError('source_build_inputs_invalid') from None
        if blob.get('encoding') != 'base64' or content != (root / name).read_bytes():
            raise ValueError('source_build_inputs_changed')


def github_api(endpoint):
    # gh uses the workflow's read-only token; never print it or gh transport
    # diagnostics/redirect URLs. Only JSON metadata/contents endpoints here.
    return json.loads(subprocess.check_output(['gh', 'api', endpoint], stderr=subprocess.DEVNULL))


if __name__ == '__main__':
    try:
        verify(sys.argv[1], os.environ.get('GITHUB_REPOSITORY', ''), os.environ.get('GITHUB_REF_NAME', ''), github_api)
    except Exception:
        sys.exit('source_build_artifact_refused')
    print('Verified same-repository source build, branch, producer and exact build inputs')
