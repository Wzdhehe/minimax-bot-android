#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Print counts and finite failure identifiers, never Android reports/env/body."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

PHASE = r'bootstrap|sqlite_load|sqlite_memory|vfs_register|vfs_open|vfs_wal|vfs_verify|vfs_write|vfs_reopen|model_streams|model_dynamic|sdk_route|unknown'
CODE = r'SQLITE_CANTOPEN|SQLITE_IOERR_SHMOPEN|SQLITE_IOERR|SQLITE_BUSY|SQLITE_READONLY|SQLITE_ERROR|SQLITE_PERM|SQLITE_MISUSE|SQLITE_NOTFOUND|fd_vfs_unavailable|fd_vfs_input_invalid|unknown'
FAILURE = re.compile(r'node_probe_failed_at_(' + PHASE + r')_code_(' + CODE + r')(?:\b|$)')
LOCATION = re.compile(r'IsolatedNodeModelTest\.kt:([0-9]{1,5})\b')


def report(directory):
    totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
    for path in sorted(Path(directory).rglob('*.xml')):
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, '0'))
        for case in suite.findall('testcase'):
            for failure in [*case.findall('failure'), *case.findall('error')]:
                # Android's XmlTestRunListener can put the stack trace in text
                # without a message attribute. Both must be inspected privately.
                text = failure.get('message', '') + '\n' + (failure.text or '')
                match = FAILURE.search(text)
                if match:
                    print('::error::Node probe phase=' + match[1] + ' code=' + match[2])
                for line in sorted(set(LOCATION.findall(text)))[:5]:
                    print('::error::Node probe failure location=IsolatedNodeModelTest.kt:' + line)
    print(totals)
    return 0 if totals['tests'] >= 1 and totals['failures'] == totals['errors'] == totals['skipped'] == 0 else 1


if __name__ == '__main__':
    sys.exit(report(sys.argv[1]))
