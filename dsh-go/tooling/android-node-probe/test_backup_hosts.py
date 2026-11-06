# SPDX-License-Identifier: AGPL-3.0-only
"""Host selection and product checks for the Android backup JNI preparation.

These run on Linux and Windows alike. They cover which host compiler the
preparation picks, how it is invoked, and which inputs and exports the
product must have. They do not simulate a Windows compilation.
"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
DSH = HERE.parent.parent
KOTLIN_BINDINGS = (DSH / 'app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app'
                   '/isolation/NativeSqliteBackupBindings.kt')


def load_module():
    spec = importlib.util.spec_from_file_location('prepare_backup_hosts', HERE / 'prepare-backup.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BackupHostTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory(prefix='mbot backup host ')
        self.addCleanup(self.temporary.cleanup)
        # A path with spaces: the compiler must be invoked with it as one
        # argument, without quoting and without a shell.
        self.ndk = Path(self.temporary.name) / 'ndk with spaces'

    def host(self, name, *compilers):
        root = self.ndk / 'toolchains/llvm/prebuilt' / name
        (root / 'bin').mkdir(parents=True)
        for compiler in compilers:
            (root / 'bin' / compiler).touch()
        runtime = root / 'sysroot/usr/lib/x86_64-linux-android/libc++_shared.so'
        runtime.parent.mkdir(parents=True)
        runtime.touch()
        return root

    def test_linux_keeps_the_existing_android26_wrappers(self):
        root = self.host('linux-x86_64', 'x86_64-linux-android26-clang', 'x86_64-linux-android26-clang++')
        toolchain, compiler, cxx = self.module.ndk_host_tools(self.ndk, 'Linux')
        self.assertEqual(root, toolchain)
        self.assertEqual([str(root / 'bin/x86_64-linux-android26-clang')], compiler)
        self.assertEqual([str(root / 'bin/x86_64-linux-android26-clang++')], cxx)

    def test_windows_uses_the_executables_without_a_command_wrapper(self):
        root = self.host('windows-x86_64', 'clang.exe', 'clang++.exe')
        toolchain, compiler, cxx = self.module.ndk_host_tools(self.ndk, 'Windows')
        self.assertEqual(root, toolchain)
        self.assertEqual(str(root / 'bin/clang.exe'), compiler[0])
        self.assertEqual(str(root / 'bin/clang++.exe'), cxx[0])
        for command in (compiler, cxx):
            self.assertIn(' ', command[0])
            self.assertNotIn('"', command[0])
            self.assertNotIn('.cmd', str(command[0]))

    def test_cmd_wrapper_cannot_substitute_for_the_windows_executable(self):
        self.host('windows-x86_64', 'clang.cmd', 'clang++.cmd')
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK'):
            self.module.ndk_host_tools(self.ndk, 'Windows')

    def test_compiler_without_the_android_runtime_is_not_a_valid_toolchain(self):
        root = self.host('windows-x86_64', 'clang.exe', 'clang++.exe')
        (root / 'sysroot/usr/lib/x86_64-linux-android/libc++_shared.so').unlink()
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK'):
            self.module.ndk_host_tools(self.ndk, 'Windows')

    def test_unsupported_detected_host_has_no_fallback_to_a_linux_compiler(self):
        self.host('linux-x86_64', 'x86_64-linux-android26-clang', 'x86_64-linux-android26-clang++')
        with patch.object(self.module.platform, 'system', return_value='Darwin'):
            with self.assertRaisesRegex(RuntimeError, 'Linux or Windows'):
                self.module.ndk_host_tools(self.ndk)

    def test_detected_windows_host_requires_the_windows_ndk_slice(self):
        self.host('linux-x86_64', 'x86_64-linux-android26-clang', 'x86_64-linux-android26-clang++')
        with patch.object(self.module.platform, 'system', return_value='Windows'):
            with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK'):
                self.module.ndk_host_tools(self.ndk)

    def test_the_readelf_and_nm_of_the_same_ndk_are_required(self):
        self.host('windows-x86_64', 'clang.exe', 'clang++.exe')
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK readelf or nm'):
            self.module.ndk_tools(self.ndk, 'Windows')

    def test_the_compile_definitions_are_validated_like_the_sqlite_preparation(self):
        root = Path(self.temporary.name)
        (root / 'deps').mkdir()
        (root / 'deps/defines.gypi').write_text(repr({'defines': ['NDEBUG=1', 'SQLITE_OMIT_PROGRESS_CALLBACK']}),
                                                encoding='utf-8')
        self.assertEqual(['NDEBUG=1', 'SQLITE_OMIT_PROGRESS_CALLBACK'], self.module.defines_from(root))
        (root / 'deps/defines.gypi').write_text(repr({'defines': ['BAD VALUE']}), encoding='utf-8')
        with self.assertRaisesRegex(RuntimeError, 'Unexpected SQLite compile definitions'):
            self.module.defines_from(root)

    def test_the_jni_exports_match_the_kotlin_bindings_class(self):
        source = KOTLIN_BINDINGS.read_text(encoding='utf-8')
        self.assertIn('package io.github.ccch1mneyyy.dsh_app.isolation', source)
        self.assertIn('object NativeSqliteBackupBindings', source)
        self.assertIn('System.loadLibrary("mbot_sqlite_backup")', source)
        # The exported names are the JNI mangling of that class.
        expected = {
            'Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_run',
            'Java_io_github_ccch1mneyyy_dsh_1app_isolation_NativeSqliteBackupBindings_cancel',
        }
        self.assertEqual(expected, set(self.module.JNI_EXPORTS))
        self.assertEqual(self.module.LIBRARY, 'libmbot_sqlite_backup.so')

    def test_the_snapshot_records_the_bytes_that_are_actually_compiled(self):
        snapshot, recorded = self.module.snapshot_inputs()
        for name, entry in recorded.items():
            source = Path(entry['repo_path'])
            if not source.is_absolute():
                source = DSH / source
            self.assertTrue(source.is_file(), name)
            # The recorded digest is the working tree file, not the Git blob.
            self.assertEqual(self.module.digest_file(source), entry['sha256'])
            self.assertEqual(entry['bytes'], (snapshot / name).stat().st_size)
            if entry['crlf'] and entry['git_blob_lf_sha256'] is not None:
                # The compiled bytes are the CRLF working tree file; the Git
                # blob is the LF version and is only recorded for comparison.
                self.assertNotEqual(entry['git_blob_lf_sha256'], entry['sha256'])

    def test_the_preparation_refuses_a_changed_sqlite_archive_before_compiling(self):
        archive = Path(self.temporary.name) / 'better-sqlite3.tgz'
        archive.write_bytes(b'not the pinned archive')
        with patch.object(self.module.subprocess, 'run') as compiler:
            with self.assertRaisesRegex(RuntimeError, 'digest mismatch'):
                self.module.prepare_sqlite_source(archive)
            compiler.assert_not_called()


if __name__ == '__main__':
    unittest.main()
