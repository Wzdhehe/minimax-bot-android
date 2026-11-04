#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Wzdhehe
"""Tests for the documentation link checker.

Every test builds its own tiny repository in a temporary directory, so the
tests never touch the real checkout and never depend on the working directory
the test happens to run in.
"""

from __future__ import annotations

import hashlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_links  # noqa: E402


class RepoTestCase(unittest.TestCase):
    """Base class that provides a throwaway repository."""

    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary.name).resolve()
        self.addCleanup(self._temporary.cleanup)

    def write(self, relative: str, content: str) -> Path:
        """Creates a file below the repository root."""
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def scan(self, targets: list[str]) -> list[check_links.Issue]:
        """Scans the repository and returns the issues."""
        issues, _documents = check_links.run(self.root, targets)
        return issues

    def run_cli(self, argv: list[str], cwd: Path | None = None) -> tuple[int, str, str]:
        """Runs the command line entry point and captures its output."""
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = check_links.main(argv)
        return code, stdout.getvalue(), stderr.getvalue()


class TestResolvesAgainstDocumentDirectory(RepoTestCase):
    def test_relative_target_resolves_next_to_the_document(self):
        self.write("docs/a.md", "[b](sub/b.md)\n")
        self.write("docs/sub/b.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_target_is_not_resolved_against_the_working_directory(self):
        self.write("docs/a.md", "[b](sub/b.md)\n")
        self.write("docs/sub/b.md", "ok\n")
        # A file with the same relative name next to the working directory
        # must not satisfy a link inside docs/.
        self.write("sub/b.md", "decoy\n")
        elsewhere = self.root / "elsewhere"
        elsewhere.mkdir()
        previous = Path.cwd()
        os.chdir(elsewhere)
        try:
            self.assertEqual(self.scan(["docs/a.md"]), [])
        finally:
            os.chdir(previous)

    def test_same_name_in_two_directories_are_told_apart(self):
        self.write("docs/a.md", "[x](one/target.md)\n")
        self.write("docs/one/target.md", "ok\n")
        self.write("docs/two/target.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])


class TestMissingTarget(RepoTestCase):
    def test_missing_target_reports_line_reason_and_exit_code(self):
        self.write("docs/a.md", "first line\n\n[gone](missing.md)\n")
        issues = self.scan(["docs/a.md"])
        self.assertEqual(len(issues), 1)
        issue = issues[0]
        self.assertEqual(issue.source, "docs/a.md")
        self.assertEqual(issue.line, 3)
        self.assertEqual(issue.reason, check_links.REASON_MISSING_TARGET)
        self.assertEqual(issue.target, "missing.md")

    def test_exit_code_is_one_when_an_issue_is_found(self):
        self.write("docs/a.md", "[gone](missing.md)\n")
        code, stdout, _stderr = self.run_cli(["--root", str(self.root), "docs/a.md"])
        self.assertEqual(code, check_links.EXIT_ISSUES)
        self.assertIn("docs/a.md:1: missing_target: missing.md", stdout)

    def test_exit_code_is_zero_when_everything_resolves(self):
        self.write("docs/a.md", "[b](b.md)\n")
        self.write("docs/b.md", "ok\n")
        code, stdout, _stderr = self.run_cli(["--root", str(self.root), "docs/a.md"])
        self.assertEqual(code, check_links.EXIT_OK)
        self.assertEqual(stdout, "")

    def test_several_issues_on_one_line_are_all_reported(self):
        self.write("docs/a.md", "[a](one.md) [b](two.md)\n")
        issues = self.scan(["docs/a.md"])
        self.assertEqual([issue.line for issue in issues], [1, 1])
        self.assertEqual([issue.target for issue in issues], ["one.md", "two.md"])

    def test_issues_are_sorted_by_document_and_line(self):
        self.write("docs/b.md", "[x](missing-b.md)\n")
        self.write("docs/a.md", "[y](missing-a2.md)\n[z](missing-a1.md)\n")
        issues = self.scan(["docs"])
        self.assertEqual(
            [(issue.source, issue.line) for issue in issues],
            [("docs/a.md", 1), ("docs/a.md", 2), ("docs/b.md", 1)],
        )


class TestIgnoredTargets(RepoTestCase):
    def test_remote_and_other_schemes_are_ignored(self):
        self.write(
            "docs/a.md",
            "[a](https://example.com/x.md)\n"
            "[b](http://example.com/x.md)\n"
            "[c](mailto:someone@example.com)\n"
            "[d](ftp://example.com/x.md)\n",
        )
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_pure_anchor_is_ignored(self):
        self.write("docs/a.md", "[a](#section)\n[b](#)\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_absolute_paths_are_ignored(self):
        self.write("docs/a.md", "[a](/etc/passwd)\n[b](/tmp/x.md)\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_image_links_are_checked_like_file_links(self):
        self.write("docs/a.md", "![logo](images/logo.png)\n")
        issues = self.scan(["docs/a.md"])
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].reason, check_links.REASON_MISSING_TARGET)


class TestTargetShapes(RepoTestCase):
    def test_fragment_is_removed_before_the_file_is_checked(self):
        self.write("docs/a.md", "[a](b.md#section)\n")
        self.write("docs/b.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_query_is_removed_before_the_file_is_checked(self):
        self.write("docs/a.md", "[a](b.md?plain=1)\n")
        self.write("docs/b.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_fragment_and_query_together_are_removed(self):
        self.write("docs/a.md", "[a](b.md?x=1#section)\n")
        self.write("docs/b.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_percent_encoded_path_resolves_to_the_decoded_name(self):
        self.write("docs/a.md", "[a](my%20file.md)\n")
        self.write("docs/my file.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_angle_bracket_target_may_contain_spaces(self):
        self.write("docs/a.md", "[a](<my file.md>)\n")
        self.write("docs/my file.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_angle_bracket_target_with_a_missing_file_is_reported(self):
        self.write("docs/a.md", "[a](<my file.md>)\n")
        issues = self.scan(["docs/a.md"])
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].reason, check_links.REASON_MISSING_TARGET)

    def test_link_to_a_directory_resolves(self):
        self.write("docs/a.md", "[a](sub)\n")
        self.write("docs/sub/keep.md", "ok\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])


class TestCodeIsNotScanned(RepoTestCase):
    def test_fenced_code_block_examples_are_ignored(self):
        self.write(
            "docs/a.md",
            "before\n"
            "```markdown\n"
            "[a](missing-in-fence.md)\n"
            "```\n"
            "after\n",
        )
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_tilde_fenced_code_block_examples_are_ignored(self):
        self.write(
            "docs/a.md",
            "~~~\n"
            "[a](missing-in-fence.md)\n"
            "~~~\n",
        )
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_inline_code_examples_are_ignored(self):
        self.write("docs/a.md", "see `[a](missing-inline.md)` for details\n")
        self.assertEqual(self.scan(["docs/a.md"]), [])

    def test_a_real_link_next_to_inline_code_is_still_found(self):
        self.write("docs/a.md", "see `[a](missing-inline.md)` but [b](missing-real.md)\n")
        issues = self.scan(["docs/a.md"])
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].target, "missing-real.md")

    def test_a_link_after_a_closed_fence_is_found_again(self):
        self.write(
            "docs/a.md",
            "```\n"
            "[a](missing-in-fence.md)\n"
            "```\n"
            "[b](missing-real.md)\n",
        )
        issues = self.scan(["docs/a.md"])
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].target, "missing-real.md")
        self.assertEqual(issues[0].line, 4)


class TestRepositoryBoundary(RepoTestCase):
    def test_relative_path_escaping_the_root_is_refused(self):
        self.write("docs/a.md", "[a](../../outside.md)\n")
        issues = self.scan(["docs/a.md"])
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].reason, check_links.REASON_OUTSIDE_REPOSITORY)

    def test_symlink_escaping_the_root_is_refused(self):
        outside = Path(self._temporary.name).resolve().parent / "outside-target"
        outside.mkdir(exist_ok=True)
        (outside / "secret.md").write_text("secret\n", encoding="utf-8")
        (self.root / "docs").mkdir(parents=True, exist_ok=True)
        os.symlink(outside, self.root / "docs" / "escape")
        self.write("docs/a.md", "[a](escape/secret.md)\n")
        issues = self.scan(["docs/a.md"])
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].reason, check_links.REASON_OUTSIDE_REPOSITORY)

    def test_a_symlinked_file_escaping_the_root_is_not_scanned(self):
        outside = Path(self._temporary.name).resolve().parent / "outside-target.md"
        outside.write_text("[x](also-missing.md)\n", encoding="utf-8")
        (self.root / "docs").mkdir(parents=True, exist_ok=True)
        os.symlink(outside, self.root / "docs" / "linked.md")
        self.write("docs/a.md", "ok\n")
        issues = self.scan(["docs"])
        # The symlink escapes the root: it is reported, but never read. The
        # broken link inside the outside document is not reported, because the
        # document outside the root must not be scanned.
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].reason, check_links.REASON_OUTSIDE_REPOSITORY)
        self.assertEqual(issues[0].source, "docs/linked.md")
        self.assertEqual(issues[0].line, 0)
        self.assertNotIn("also-missing.md", [issue.target for issue in issues])

    def test_a_symlinked_document_inside_the_root_is_scanned(self):
        self.write("docs/real.md", "[x](missing.md)\n")
        os.symlink(self.root / "docs" / "real.md", self.root / "docs" / "alias.md")
        issues = self.scan(["docs"])
        self.assertEqual(len(issues), 1)
        self.assertEqual(issues[0].reason, check_links.REASON_MISSING_TARGET)

    def test_a_requested_path_outside_the_root_is_an_input_error(self):
        code, _stdout, stderr = self.run_cli(["--root", str(self.root), "../elsewhere"])
        self.assertEqual(code, check_links.EXIT_INPUT_ERROR)
        self.assertIn("input error", stderr)

    def test_a_missing_root_is_an_input_error(self):
        code, _stdout, stderr = self.run_cli(["--root", str(self.root / "nope")])
        self.assertEqual(code, check_links.EXIT_INPUT_ERROR)
        self.assertIn("input error", stderr)

    def test_a_missing_requested_path_is_an_input_error(self):
        code, _stdout, stderr = self.run_cli(["--root", str(self.root), "docs/nope.md"])
        self.assertEqual(code, check_links.EXIT_INPUT_ERROR)
        self.assertIn("input error", stderr)


class TestScanning(RepoTestCase):
    def test_a_directory_is_scanned_recursively_for_markdown(self):
        self.write("docs/a.md", "[x](missing-a.md)\n")
        self.write("docs/deep/b.md", "[x](missing-b.md)\n")
        self.write("docs/deep/notes.txt", "[x](missing-c.md)\n")
        issues = self.scan(["docs"])
        self.assertEqual([issue.source for issue in issues], ["docs/a.md", "docs/deep/b.md"])

    def test_the_default_targets_are_readme_and_docs(self):
        self.write("README.md", "[x](missing-readme.md)\n")
        self.write("docs/a.md", "[x](missing-doc.md)\n")
        self.write("other/b.md", "[x](missing-other.md)\n")
        issues = self.scan([])
        self.assertEqual([issue.source for issue in issues], ["README.md", "docs/a.md"])

    def test_the_same_document_is_only_scanned_once(self):
        self.write("docs/a.md", "[x](missing.md)\n")
        issues = self.scan(["docs/a.md", "docs/a.md", "docs"])
        self.assertEqual(len(issues), 1)


class TestReadOnly(RepoTestCase):
    def _fingerprint(self) -> dict[str, str]:
        digests = {}
        for path in sorted(self.root.rglob("*")):
            if path.is_file() and not path.is_symlink():
                digests[path.relative_to(self.root).as_posix()] = hashlib.sha256(
                    path.read_bytes()
                ).hexdigest()
        return digests

    def test_scanning_does_not_change_any_file(self):
        self.write("docs/a.md", "[x](missing.md)\n")
        self.write("docs/b.md", "[y](b.md)\n")
        before = self._fingerprint()
        self.scan(["docs"])
        self.assertEqual(self._fingerprint(), before)

    def test_running_the_command_does_not_change_any_file(self):
        self.write("docs/a.md", "[x](missing.md)\n")
        before = self._fingerprint()
        self.run_cli(["--root", str(self.root), "docs"])
        self.assertEqual(self._fingerprint(), before)


class TestCommandLine(RepoTestCase):
    def test_the_script_runs_as_a_program(self):
        self.write("docs/a.md", "[x](missing.md)\n")
        script = Path(check_links.__file__).resolve()
        result = subprocess.run(
            [sys.executable, str(script), "--root", str(self.root), "docs/a.md"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, check_links.EXIT_ISSUES)
        self.assertIn("docs/a.md:1: missing_target: missing.md", result.stdout)

    def test_the_help_text_names_the_root_option(self):
        script = Path(check_links.__file__).resolve()
        result = subprocess.run(
            [sys.executable, str(script), "--help"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("--root", result.stdout)


if __name__ == "__main__":
    unittest.main()
