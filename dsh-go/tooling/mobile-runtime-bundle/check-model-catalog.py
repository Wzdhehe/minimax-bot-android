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
from contextlib import ExitStack


def check(args, scenario, directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    peer, child = socket.socketpair()
    peer.settimeout(60)
    log_path = directory / f"http-{scenario}.log"
    script = Path(__file__).with_name("smoke-product.mjs")
    anthropic = scenario.startswith("anthropic")
    with ExitStack() as stack, peer, child, log_path.open("wb") as log:
        model_peers = []
        model_children = []
        if anthropic:
            frames = [
                {"type": "message_start", "message": {"id": "fixture", "type": "message", "role": "assistant", "model": "fixture-model", "content": [], "usage": {"input_tokens": 1, "output_tokens": 0}}},
                {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}},
                {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "Fixture reply"}},
                {"type": "content_block_stop", "index": 0},
                {"type": "message_delta", "delta": {"stop_reason": "end_turn", "stop_sequence": None}, "usage": {"output_tokens": 2}},
                {"type": "message_stop"},
            ]
            if scenario == "anthropic-fail":
                frames = [{"error": "provider_rejected"}]
            wire = ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nX-Mbot-Broker-Protocol: 1\r\n\r\n" +
                    "".join("data: " + json.dumps(event) + "\n\n" for event in frames) + "data: [DONE]\n\n").encode()
            for _ in range(2):
                owner, guest = socket.socketpair()
                stack.enter_context(owner)
                stack.enter_context(guest)
                owner.sendall(wire)
                owner.shutdown(socket.SHUT_WR)
                model_peers.append(owner)
                model_children.append(guest)
        extra = [",".join(str(fd.fileno()) for fd in model_children)] if anthropic else []
        process = subprocess.Popen(
            [args.node, str(script), args.bundle, args.addon, args.wrapper,
             str(directory), scenario, str(child.fileno()), *extra],
            pass_fds=(child.fileno(), *(fd.fileno() for fd in model_children)), stdin=subprocess.PIPE,
            stdout=log, stderr=subprocess.STDOUT,
        )
        child.close()
        for fd in model_children:
            fd.close()
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
            def request(method, path, payload=None):
                data = b"" if payload is None else json.dumps(payload).encode()
                wire = (f"{method} {path} HTTP/1.1\r\n"
                        f"Host: 127.0.0.1:{session['port']}\r\n"
                        f"Authorization: Bearer {session['token']}\r\n"
                        f"Content-Length: {len(data)}\r\nContent-Type: application/json\r\n"
                        f"Connection: {'keep-alive' if anthropic else 'close'}\r\n\r\n")
                peer.sendall(wire.encode("ascii") + data)
                response = http.client.HTTPResponse(peer)
                response.begin()
                assert response.status == 200, f"{scenario}: HTTP {response.status}"
                return json.loads(response.read())
            body = request("GET", "/api/models")
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
            if scenario in ("basic", "advanced") or anthropic:
                assert body["default"]["modelId"] == "fixture-model"
            else:
                assert body.get("default") is None, "must not guess a default"
            if anthropic:
                result = request("POST", "/api/providers/test", {
                    "providerId": "4a931f3d-8b37-4c71-a000-111111111111", "modelId": "fixture-model"})
                assert result["ok"] is (scenario == "anthropic"), "product probe is not wired to native FD"
                bot = request("GET", "/api/state")["bots"][0]["id"]
                path = f"/api/chats/bot:{bot}/messages"
                sent = request("POST", path, {"text": "Reply briefly.", "clientMessageId": f"fixture-{time.time_ns()}"})
                user_id = sent["message"]["id"]
                deadline = time.monotonic() + (65 if scenario == "anthropic-fail" else 20)
                while True:
                    messages = request("GET", path)["messages"]
                    index = next(i for i, row in enumerate(messages) if row["id"] == user_id)
                    replies = [row for row in messages[index + 1:] if row["role"] == "bot"]
                    if replies and replies[-1].get("status") not in ("streaming", "pending"):
                        reply = replies[-1]
                        break
                    assert time.monotonic() < deadline, "genuine Bot turn did not settle"
                    time.sleep(0.05)
                if scenario == "anthropic":
                    assert reply["text"] == "Fixture reply", "genuine runtime did not consume native model text"
                    assert reply["status"] == "done"
                else:
                    assert reply["status"] == "error", "a failed provider must not look like empty success"
                captured = json.loads((directory / "model-requests.json").read_text(encoding="utf-8"))
                assert len(captured) == 2, "probe and actual model turn must each open their own native grant"
                assert set(captured[0]) == {"providerId", "modelId", "messages"}
                assert set(captured[1]) == {"providerId", "modelId", "request"}
                sdk = captured[1]["request"]
                assert sdk["model"] == "fixture-model" and sdk["stream"] is True
                assert sdk["messages"] and sdk["max_tokens"] > 0
                assert "headers" not in sdk and "apiKey" not in sdk and "baseUrl" not in sdk
                print(f"PASS: {scenario}: genuine product probe and Bot turn use native FD; safe failure/visible reply checked; fixture provider, host only", flush=True)
                peer.shutdown(socket.SHUT_RDWR)
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
                        choices=["empty", "basic", "advanced", "disabled", "no-default", "anthropic", "anthropic-fail"])
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
