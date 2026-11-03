# SPDX-License-Identifier: AGPL-3.0-only
"""Print SELinux permission/class labels only, never logcat bodies or paths."""
import re
import sys

seen = set()
for line in sys.stdin:
    match = re.search(r"avc: denied \{([^}]+)\}.*scontext=(\S+).*tcontext=(\S+).*tclass=(\S+)", line)
    if not match:
        continue
    permission, source, target, kind = match.groups()
    if "isolated_app" not in source or kind not in {"fd", "tcp_socket", "unix_stream_socket", "file", "dir", "process", "chr_file"}:
        continue
    summary = (permission.strip(), source, target, kind)
    if summary not in seen:
        seen.add(summary)
        print("isolated-denial", *summary)
print("isolated-denial-summary-count", len(seen))
