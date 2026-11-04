#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Wzdhehe
"""Find relative file links in Markdown documents that no longer resolve.

The checker is deliberately small: it reads Markdown text, recognises the
inline link forms listed in README.md, resolves each relative target against
the directory of the document that contains it, and reports the ones that are
missing or that escape the repository root.

It never writes to the repository, never follows a link out of the root, and
never resolves a remote URL.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote

REASON_MISSING_TARGET = "missing_target"
REASON_OUTSIDE_REPOSITORY = "outside_repository"

EXIT_OK = 0
EXIT_ISSUES = 1
EXIT_INPUT_ERROR = 2

DEFAULT_TARGETS = ("README.md", "docs")

# A target is either wrapped in angle brackets (spaces allowed) or is a run of
# characters without whitespace or parentheses. Escaped parentheses are
# allowed; unescaped ones are not (see README.md, "Not supported").
_LINK_RE = re.compile(
    r"!?\[(?:[^\[\]\\]|\\.)*\]\(\s*(?:<(?P<angle>[^<>]*)>|(?P<plain>[^()\s]*(?:\\.[^()\s]*)*))\s*\)"
)

# Inline code spans are removed before links are looked for, so that examples
# inside `code` are not reported.
_INLINE_CODE_RE = re.compile(r"(?P<ticks>`+)(?P<code>.+?)(?P=ticks)")

# A fenced code block opener or closer.
_FENCE_RE = re.compile(r"^\s*(?P<marker>`{3,}|~{3,})")

# Anything with a URI scheme is not a file path.
_SCHEME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*:")

_ESCAPE_RE = re.compile(r"\\([()\\])")


@dataclass(frozen=True)
class Issue:
    """One problem found in one source document."""

    source: str  # path of the document, relative to the repository root
    line: int  # 1-based line number, 0 for a whole-file problem
    reason: str  # one of REASON_MISSING_TARGET / REASON_OUTSIDE_REPOSITORY
    target: str  # the link target exactly as written in the document

    def format(self) -> str:
        return f"{self.source}:{self.line}: {self.reason}: {self.target}"


def remove_inline_code(line: str) -> str:
    """Blanks out inline code spans so their contents are not scanned."""
    return _INLINE_CODE_RE.sub(lambda match: " " * len(match.group(0)), line)


def iter_code_lines(text: str):
    """Yields (line number, line, inside_fence) for every line of the text."""
    fence: tuple[str, int] | None = None
    for number, line in enumerate(text.splitlines(), start=1):
        marker = _FENCE_RE.match(line)
        if fence is None:
            if marker:
                token = marker.group("marker")
                fence = (token[0], len(token))
                yield number, line, True
                continue
            yield number, line, False
        else:
            if marker:
                token = marker.group("marker")
                if token[0] == fence[0] and len(token) >= fence[1]:
                    fence = None
            yield number, line, True


def find_targets(line: str) -> list[str]:
    """Returns the raw targets of every supported inline link in the line."""
    targets: list[str] = []
    for match in _LINK_RE.finditer(line):
        angle = match.group("angle")
        target = angle if angle is not None else match.group("plain")
        targets.append(target.strip())
    return targets


def split_target(target: str) -> str:
    """Removes the fragment and the query from a link target."""
    without_fragment = target.split("#", 1)[0]
    return without_fragment.split("?", 1)[0]


def is_ignorable(target: str) -> bool:
    """True for targets that are not repository file paths."""
    if not target:
        return True
    if _SCHEME_RE.match(target):
        return True
    if target.startswith("/"):
        return True
    return False


def is_inside(root: Path, candidate: Path) -> bool:
    """True when candidate is the root itself or lives under it."""
    try:
        candidate.relative_to(root)
    except ValueError:
        return False
    return True


def check_target(root: Path, source: Path, target: str) -> str | None:
    """Returns the reason a target is broken, or None when it resolves."""
    path_part = split_target(target)
    if is_ignorable(path_part):
        return None
    decoded = unquote(path_part)
    decoded = _ESCAPE_RE.sub(r"\1", decoded)
    if not decoded:
        return None
    resolved = source.parent / decoded
    real = Path(os.path.realpath(resolved))
    if not is_inside(root, real):
        return REASON_OUTSIDE_REPOSITORY
    if not real.exists():
        return REASON_MISSING_TARGET
    return None


def check_document(root: Path, source: Path) -> list[Issue]:
    """Returns every issue found in one document."""
    text = source.read_text(encoding="utf-8", errors="replace")
    relative = source.relative_to(root).as_posix()
    issues: list[Issue] = []
    for number, line, inside_fence in iter_code_lines(text):
        if inside_fence:
            continue
        for target in find_targets(remove_inline_code(line)):
            reason = check_target(root, source, target)
            if reason is not None:
                issues.append(Issue(relative, number, reason, target))
    return issues


def _raise_walk_error(error: OSError) -> None:
    """An unreadable subtree must fail the scan, not look like empty docs."""
    raise error


def collect_files(root: Path, targets: list[str]) -> tuple[list[Path], list[Issue]]:
    """Resolves the requested paths into the documents to scan.

    Returns the documents and the whole-file issues found while walking.
    """
    documents: list[Path] = []
    issues: list[Issue] = []
    seen: set[Path] = set()

    for requested in targets:
        candidate = root / requested
        real = Path(os.path.realpath(candidate))
        if not is_inside(root, real):
            raise ValueError(f"path escapes the repository root: {requested}")
        if not candidate.exists():
            raise ValueError(f"path does not exist: {requested}")
        if candidate.is_dir():
            for directory, subdirectories, filenames in os.walk(candidate, onerror=_raise_walk_error):
                kept = []
                for name in sorted(subdirectories):
                    child = Path(directory) / name
                    if is_inside(root, Path(os.path.realpath(child))):
                        kept.append(name)
                    else:
                        issues.append(
                            Issue(
                                child.relative_to(root).as_posix()
                                if is_inside(root, child)
                                else name,
                                0,
                                REASON_OUTSIDE_REPOSITORY,
                                name,
                            )
                        )
                subdirectories[:] = kept
                for name in sorted(filenames):
                    child = Path(directory) / name
                    if name.endswith(".md"):
                        if not is_inside(root, Path(os.path.realpath(child))):
                            issues.append(
                                Issue(
                                    child.relative_to(root).as_posix()
                                    if is_inside(root, child)
                                    else name,
                                    0,
                                    REASON_OUTSIDE_REPOSITORY,
                                    name,
                                )
                            )
                            continue
                        documents.append(child)
        else:
            documents.append(candidate)

    unique: list[Path] = []
    for document in documents:
        key = os.path.realpath(document)
        if key in seen:
            continue
        seen.add(key)
        unique.append(document)
    return unique, issues


def sort_issues(issues: list[Issue]) -> list[Issue]:
    """Orders issues by source document and then by line number."""
    return sorted(issues, key=lambda issue: (issue.source, issue.line, issue.target))


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="check_links.py",
        description="Report relative Markdown links that no longer resolve.",
    )
    parser.add_argument(
        "--root",
        default=".",
        help="repository root the relative paths are resolved against (default: .)",
    )
    parser.add_argument(
        "targets",
        nargs="*",
        help=(
            "Markdown files or directories to scan, relative to --root "
            f"(default: {' '.join(DEFAULT_TARGETS)})"
        ),
    )
    return parser.parse_args(argv)


def run(root: Path, targets: list[str] | None = None) -> tuple[list[Issue], list[str]]:
    """Scans and returns the issues and the documents that were read.

    An empty target list means the default targets: README.md and docs.
    """
    documents, issues = collect_files(root, list(targets) if targets else list(DEFAULT_TARGETS))
    for document in documents:
        issues.extend(check_document(root, document))
    return sort_issues(issues), documents


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    root = Path(os.path.realpath(Path(args.root)))
    if not root.is_dir():
        print(f"input error: --root is not a directory: {args.root}", file=sys.stderr)
        return EXIT_INPUT_ERROR
    targets = args.targets
    try:
        issues, documents = run(root, targets)
    except ValueError as error:
        print(f"input error: {error}", file=sys.stderr)
        return EXIT_INPUT_ERROR
    except OSError as error:
        print(f"input error: cannot read a requested path: {error.strerror}", file=sys.stderr)
        return EXIT_INPUT_ERROR

    for issue in issues:
        print(issue.format())
    print(
        f"checked {len(documents)} document(s), found {len(issues)} issue(s)",
        file=sys.stderr,
    )
    return EXIT_ISSUES if issues else EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
