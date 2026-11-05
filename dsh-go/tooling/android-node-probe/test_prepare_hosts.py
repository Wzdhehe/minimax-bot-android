# SPDX-License-Identifier: AGPL-3.0-only
"""Host selection tests; Windows compilation/execution is not simulated."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('probe_prepare_hosts', Path(__file__).with_name('prepare-probe.py'))
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class PrepareHostTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='mbot ndk host ')
        self.addCleanup(self.temporary.cleanup)
        self.ndk = Path(self.temporary.name)

    def host(self, name, compiler):
        root = self.ndk / 'toolchains/llvm/prebuilt' / name
        (root / 'bin').mkdir(parents=True)
        (root / 'bin' / compiler).touch()
        runtime = root / 'sysroot/usr/lib/x86_64-linux-android/libc++_shared.so'
        runtime.parent.mkdir(parents=True)
        runtime.touch()
        return root

    def test_linux_keeps_the_existing_android26_wrapper(self):
        root = self.host('linux-x86_64', 'x86_64-linux-android26-clang++')
        toolchain, command = PREPARE.ndk_host_tools(self.ndk, 'Linux')
        self.assertEqual(root, toolchain)
        self.assertEqual([str(root / 'bin/x86_64-linux-android26-clang++')], command)

    def test_windows_runs_the_executable_with_one_explicit_android_target(self):
        root = self.host('windows-x86_64', 'clang++.exe')
        toolchain, command = PREPARE.ndk_host_tools(self.ndk, 'Windows')
        self.assertEqual(root, toolchain)
        self.assertEqual(str(root / 'bin/clang++.exe'), command[0])
        self.assertEqual(['--target=x86_64-linux-android26'], command[1:])
        self.assertIn(' ', command[0])
        self.assertNotIn('"', command[0])

    def test_cmd_wrapper_cannot_substitute_for_the_windows_executable(self):
        self.host('windows-x86_64', 'x86_64-linux-android26-clang++.cmd')
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK'):
            PREPARE.ndk_host_tools(self.ndk, 'Windows')

    def test_compiler_without_the_android_runtime_is_not_a_valid_toolchain(self):
        root = self.host('windows-x86_64', 'clang++.exe')
        (root / 'sysroot/usr/lib/x86_64-linux-android/libc++_shared.so').unlink()
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK'):
            PREPARE.ndk_host_tools(self.ndk, 'Windows')

    def test_unsupported_detected_host_has_no_fallback_to_a_linux_compiler(self):
        with patch.object(PREPARE.platform, 'system', return_value='Darwin'):
            with self.assertRaisesRegex(RuntimeError, 'Linux or Windows'):
                PREPARE.ndk_host_tools(self.ndk)

    def test_detected_windows_host_requires_the_windows_ndk_slice(self):
        self.host('linux-x86_64', 'x86_64-linux-android26-clang++')
        with patch.object(PREPARE.platform, 'system', return_value='Windows'):
            with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK'):
                PREPARE.ndk_host_tools(self.ndk)


if __name__ == '__main__':
    unittest.main()
