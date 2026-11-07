# SPDX-License-Identifier: AGPL-3.0-only
"""Host-only HTTP check of the genuine bundled product, with owner-fixture FDs.

Requires the smoke-product.mjs host prerequisites, not Android libraries. No
real provider or Key is used. Neither descriptors nor session tokens are logged.
"""
import argparse
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import time


def check(args, scenario, directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    peer, child = socket.socketpair()
    peer.settimeout(60)
    log_path = directory / f"http-{scenario}.log"
    script = Path(__file__).with_name("smoke-product.mjs")
    with peer, child, log_path.open("wb") as log:
        process = subprocess.Popen(
            [args.node, str(script), args.bundle, args.addon, args.wrapper,
             str(directory), scenario, str(child.fileno())],
            pass_fds=(child.fileno(),), stdin=subprocess.PIPE,
            stdout=log, stderr=subprocess.STDOUT,
        )
        child.close()
        try:
            descriptor = directory / "descriptor.json"
            # A reused directory contains the previous run's descriptor; only
            # use the replacement written by this newly started child.
            deadline = time.monotonic() + 10
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"product fixture exited; inspect {log_path}")
                if descriptor.exists() and descriptor.stat().st_mtime_ns >= args.started_ns:
                    try:
                        session = json.loads(descriptor.read_text(encoding="utf-8"))
                        break
                    except json.JSONDecodeError:
                        pass  # Child is still writing its private fixture.
                if time.monotonic() > deadline:
                    raise TimeoutError("private fixture descriptor not produced")
                time.sleep(0.02)
            request = ("GET /api/models HTTP/1.1\r\n"
                       f"Host: 127.0.0.1:{session['port']}\r\n"
                       f"Authorization: Bearer {session['token']}\r\n"
                       "Connection: close\r\n\r\n")
            peer.sendall(request.encode("ascii"))
            response = http.client.HTTPResponse(peer)
            response.begin()
            assert response.status == 200, f"{scenario}: HTTP {response.status}"
            body = json.loads(response.read())
            assert body["supported"] is True
            rows = body["models"]
            if scenario in ("empty", "disabled"):
                assert rows == [], f"{scenario}: unadmitted models exposed"
            else:
                assert len(rows) == 1
                assert rows[0]["providerId"] == "custom_provider:4a931f3d-8b37-4c71-a000-111111111111"
                assert rows[0]["modelId"] == "fixture-model"
                if scenario in ("advanced", "no-default"):
                    assert rows[0]["thinking"]["efforts"] == ["high", "medium"]
            if scenario in ("basic", "advanced"):
                assert body["default"]["modelId"] == "fixture-model"
            else:
                assert body.get("default") is None, "must not guess a default"
            process.stdin.write(b"done\n")
            process.stdin.flush()
            assert process.wait(timeout=15) == 0, f"fixture teardown failed; inspect {log_path}"
            print(f"PASS: {scenario}: genuine product GET /api/models HTTP 200, "
                  f"{len(rows)} admitted model(s), explicit default preserved; host only")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            process.stdin.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("node", "bundle", "addon", "wrapper", "directory"):
        parser.add_argument(f"--{name}", required=True)
    parser.add_argument("--scenarios", nargs="+", default=["empty", "basic", "disabled", "no-default", "advanced"],
                        choices=["empty", "basic", "advanced", "disabled", "no-default"])
    args = parser.parse_args()
    if os.name != "posix":
        parser.error("owner-fixture socket FD transfer requires a POSIX host")
    for name in ("node", "bundle", "addon", "wrapper", "directory"):
        setattr(args, name, str(Path(getattr(args, name)).resolve()))
    # All cases intentionally reuse the same SQLite and persistent filesystem:
    # disabling and reenabling must not resurrect a stale selected default.
    for scenario in args.scenarios:
        args.started_ns = time.time_ns()
        check(args, scenario, Path(args.directory))


if __name__ == "__main__":
    main()
