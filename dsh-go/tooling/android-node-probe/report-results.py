#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Print counts and finite failure identifiers, never Android reports/env/body."""
from pathlib import Path
import os
import re
import sys
import xml.etree.ElementTree as ET

PHASE = r'bootstrap|sqlite_load|sqlite_memory|vfs_register|vfs_open|vfs_wal|vfs_verify|vfs_write|vfs_reopen|model_streams|model_dynamic|sdk_route|unknown'
CODE = r'SQLITE_CANTOPEN|SQLITE_IOERR_SHMOPEN|SQLITE_IOERR|SQLITE_BUSY|SQLITE_READONLY|SQLITE_ERROR|SQLITE_PERM|SQLITE_MISUSE|SQLITE_NOTFOUND|fd_vfs_unavailable|fd_vfs_input_invalid|unknown'
FAILURE = re.compile(r'node_probe_failed_at_(' + PHASE + r')_code_(' + CODE + r')(?:\b|$)')
LOCATION = re.compile(r'IsolatedNodeModelTest\.kt:([0-9]{1,5})\b')
KINDS = {
    'java.lang.AssertionError': 'assertion', 'java.util.concurrent.TimeoutException': 'timeout',
    'java.util.concurrent.ExecutionException': 'future_failed', 'java.lang.UnsatisfiedLinkError': 'native_link',
    'org.json.JSONException': 'json', 'java.lang.NoClassDefFoundError': 'class_load',
    'java.lang.ClassNotFoundException': 'class_load', 'android.os.RemoteException': 'ipc',
    'android.os.DeadObjectException': 'ipc', 'java.io.IOException': 'io',
    'java.lang.SecurityException': 'permission', 'Process crashed': 'process_crashed',
    'INSTALL_FAILED': 'installation',
}


def tag(element):
    return element.tag.rsplit('}', 1)[-1]


def report(directory, status_file=None, github_output=None):
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    identifier, source_line = 'unclassified', '0'
    for path in sorted(Path(directory).rglob('*.xml')):
        root = ET.parse(path).getroot()
        for suite in root.iter():
            if tag(suite) != 'testsuite' or any(tag(child) == 'testsuite' for child in suite):
                continue
            for key in totals:
                value = int(suite.get(key, '0'))
                if not 0 <= value <= 100_000:
                    raise ValueError('invalid_test_count')
                totals[key] += value
        for failure in root.iter():
            if tag(failure) not in ('failure', 'error'):
                continue
            # Handle namespace, suite-level failures and nested stack elements
            # as well as Android's plain-text/attribute variants, all privately.
            text = failure.get('message', '') + '\n' + ''.join(failure.itertext())
            match = FAILURE.search(text)
            if match:
                print('::error::Node probe phase=' + match[1] + ' code=' + match[2])
                identifier = match[1] + '-' + match[2]
            for line in sorted(set(LOCATION.findall(text)))[:5]:
                print('::error::Node probe failure location=IsolatedNodeModelTest.kt:' + line)
                source_line = line
            for kind in sorted({kind for needle, kind in KINDS.items() if needle in text}):
                print('::error::Node probe failure kind=' + kind)
                if identifier == 'unclassified':
                    identifier = kind
    print(totals)
    success = totals['tests'] >= 1 and totals['failures'] == totals['errors'] == totals['skipped'] == 0
    print(('::notice::' if success else '::error::') + 'Node probe counts ' + ' '.join(f'{key}={value}' for key, value in totals.items()))
    # The artifact name carries only this finite status, so metadata can still
    # be read when the environment cannot download Azure-backed logs/XML.
    result_id = '-'.join(f'{key[0]}{value}' for key, value in totals.items()) + '-' + ('passed' if success else identifier) + '-l' + source_line
    if status_file is not None:
        status = Path(status_file)
        status.parent.mkdir(parents=True, exist_ok=True)
        status.write_text(result_id + '\n')
    if github_output is not None:
        with open(github_output, 'a') as output:
            output.write('result_id=' + result_id + '\n')
    return 0 if success else 1


if __name__ == '__main__':
    try:
        directory = Path(sys.argv[1])
        result = report(directory, directory.parent / 'node-probe-status.txt', os.environ.get('GITHUB_OUTPUT'))
    except Exception:
        sys.exit('::error::Node probe result_format_invalid')
    sys.exit(result)
