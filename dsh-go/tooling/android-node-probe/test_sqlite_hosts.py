# SPDX-License-Identifier: AGPL-3.0-only
"""Host selection and path handling tests for the SQLite preparation.

These run on Linux and Windows alike. They cover which host compiler the
preparation picks and how it is invoked; they do not simulate a Windows
compilation or a device run.
"""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent


def load_module():
    spec = importlib.util.spec_from_file_location('sqlite_probe_hosts', HERE / 'prepare-sqlite.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SqliteHostTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        self.temporary = tempfile.TemporaryDirectory(prefix='mbot sqlite host ')
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

    def test_windows_uses_the_executables_with_one_explicit_android_target(self):
        root = self.host('windows-x86_64', 'clang.exe', 'clang++.exe')
        toolchain, compiler, cxx = self.module.ndk_host_tools(self.ndk, 'Windows')
        self.assertEqual(root, toolchain)
        self.assertEqual(str(root / 'bin/clang.exe'), compiler[0])
        self.assertEqual(str(root / 'bin/clang++.exe'), cxx[0])
        for command in (compiler, cxx):
            self.assertEqual(['--target=x86_64-linux-android26'], command[1:])
            # The path keeps its spaces as one argument and is not quoted.
            self.assertIn(' ', command[0])
            self.assertNotIn('"', command[0])

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

    def test_an_esbuild_command_wrapper_is_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'real esbuild executable'):
            self.module.esbuild_executable(Path('C:/npm/.bin/esbuild.cmd'))

    def test_the_real_esbuild_executable_is_accepted(self):
        esbuild = Path('C:/npm/@esbuild/win32-x64/esbuild.exe')
        self.assertEqual(esbuild, self.module.esbuild_executable(esbuild))

    def test_the_header_inventory_uses_forward_slashes(self):
        headers = Path(self.temporary.name) / 'build/node-headers'
        (headers / 'include/node').mkdir(parents=True)
        (headers / 'include/node/node.h').write_text('header fixture')
        (headers / 'node.h').write_text('header fixture')
        inventory = self.module.header_inventory(headers)
        self.assertEqual({'node.h', 'include/node/node.h'}, inventory)
        for name in inventory:
            self.assertNotIn('\\', name)

    def fake_run(self, command, *args, **kwargs):
        """Stand in for a real command; the bundler and the linker write files."""
        if command and 'esbuild' in str(command[0]):
            wrapper = self.module.ROOT / 'build/sqlite-probe/wrapper.cjs'
            wrapper.parent.mkdir(parents=True, exist_ok=True)
            wrapper.write_text('// bundled wrapper\n', encoding='utf-8')
        if command and '-shared' in command:
            library = self.module.ROOT / 'build/verified-jni/x86_64/libmbot_sqlite_probe.so'
            library.parent.mkdir(parents=True, exist_ok=True)
            library.write_bytes(b'\x7fELF fixture')

    def test_a_tampered_nested_header_is_rejected_before_any_compiler_runs(self):
        self.fixture()
        headers = self.module.ROOT / 'build/node-headers'
        (headers / 'include/node/node.h').write_text('tampered header')
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run', side_effect=self.fake_run) as runner:
            with self.assertRaisesRegex(RuntimeError, 'Node headers changed'):
                self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'))
            runner.assert_not_called()

    def test_the_compiler_runs_with_a_fixed_target_and_no_shell(self):
        self.fixture()
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run', side_effect=self.fake_run) as runner:
            self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'))
        calls = [call.args[0] for call in runner.call_args_list]
        compiles = [command for command in calls if command and 'clang' in str(command[0])]
        self.assertTrue(compiles, 'the preparation did not run a compiler')
        for command in compiles:
            self.assertIsInstance(command, list)
            self.assertIn(' ', command[0])
            self.assertNotIn('"', command[0])
            self.assertNotIn('.cmd', str(command[0]))
            targets = [part for part in command if part.startswith('--target=')]
            self.assertEqual(['--target=x86_64-linux-android26'], targets)
        for call in runner.call_args_list:
            self.assertNotIn('shell', call.kwargs)

    # ── the fixture ──────────────────────────────────────────────────────────

    def fixture(self):
        """A prepared host: pinned NDK, pinned headers, pinned archive."""
        module = self.module
        self.root = Path(self.temporary.name) / 'root'
        module.ROOT = self.root / 'app'
        (module.ROOT / 'src/main/cpp').mkdir(parents=True)
        (module.ROOT / 'src/main/cpp/fd_vfs.cpp').write_text('// adapter')
        (module.ROOT / 'src/main/cpp/fd_vfs_node.cpp').write_text('// adapter')
        (Path(self.temporary.name) / 'LICENSE').write_text('AGPL fixture')
        self.ndk.mkdir(parents=True, exist_ok=True)
        (self.ndk / 'source.properties').write_text('Pkg.Revision = ' + module.PIN['ndkVersion'])
        self.host('windows-x86_64', 'clang.exe', 'clang++.exe')

        headers = module.ROOT / 'build/node-headers'
        (headers / 'include/node').mkdir(parents=True)
        (headers / 'node.h').write_text('header fixture')
        (headers / 'include/node/node.h').write_text('header fixture')
        (headers / 'node_version.h').write_text(
            '#define NODE_MAJOR_VERSION 24\n#define NODE_MINOR_VERSION 22\n#define NODE_PATCH_VERSION 2\n')
        (headers / 'config.gypi').write_text(repr({'variables': {
            'v8_enable_pointer_compression': 0, 'v8_enable_sandbox': 0,
            'v8_enable_31bit_smis_on_64bit_arch': 0}}))
        files = {path.relative_to(headers).as_posix(): module.digest(path)
                 for path in headers.rglob('*') if path.is_file()}
        (module.ROOT / 'build/node-headers.json').write_text(json.dumps({'files': files}), encoding='utf-8')

        assets = module.ROOT / 'build/verified-assets'
        assets.mkdir(parents=True)
        (assets / 'node-fd-probe.cjs').write_text('const w = "__MBOT_SQLITE_WRAPPER__";\n', encoding='utf-8')
        (assets / 'node-validation.json').write_text(json.dumps({
            'requiresIntl': False, 'sourceArchiveSha256': None, 'requiresSqlite': False}), encoding='utf-8')

        self.archive = self.root / 'sqlite.tgz'
        members = {
            'package/package.json': json.dumps({
                'name': module.PIN['package'], 'version': module.PIN['version']}),
            'package/LICENSE': 'MIT fixture',
            'package/deps/defines.gypi': repr({'defines': ['NDEBUG=1']}),
            'package/deps/sqlite3/sqlite3.h': '#define SQLITE_VERSION "3.50.1"\n',
            'package/deps/sqlite3/sqlite3.c': '\n'.join('/* sqlite line */' for _ in range(30)),
            'package/src/better_sqlite3.cpp':
                'NODE_MODULE_INIT(/* exports, context */) {\n}\n',
            'package/lib/index.js': 'module.exports = {};\n',
        }
        with tarfile.open(self.archive, 'w:gz') as package:
            for name, text in members.items():
                data = text.encode('utf-8')
                member = tarfile.TarInfo(name)
                member.size = len(data)
                package.addfile(member, io.BytesIO(data))
        module.PIN = {**module.PIN, 'sha256': module.digest(self.archive)}


if __name__ == '__main__':
    unittest.main()
