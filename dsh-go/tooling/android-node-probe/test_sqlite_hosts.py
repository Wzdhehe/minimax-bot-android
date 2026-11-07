# SPDX-License-Identifier: AGPL-3.0-only
"""Host selection and path handling tests for the SQLite preparation.

These run on Linux and Windows alike. They cover which host compiler the
preparation picks and how it is invoked; they do not simulate a Windows
compilation or a device run.
"""
import ast
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

    def host(self, name, *compilers, abi='x86_64'):
        root = self.ndk / 'toolchains/llvm/prebuilt' / name
        (root / 'bin').mkdir(parents=True)
        for compiler in compilers:
            (root / 'bin' / compiler).touch()
        runtime = root / 'sysroot/usr/lib' / self.module.target(abi)[0] / 'libc++_shared.so'
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

    def compiles(self, runner):
        """The compiler commands the preparation ran, as argv lists."""
        calls = [call.args[0] for call in runner.call_args_list]
        return [command for command in calls if command and 'clang' in str(command[0])]

    def fake_run(self, command, *args, **kwargs):
        """Stand in for a real command; the bundler and the linker write files."""
        if command and 'esbuild' in str(command[0]):
            wrapper = Path(next(arg.removeprefix('--outfile=') for arg in command if arg.startswith('--outfile=')))
            wrapper.parent.mkdir(parents=True, exist_ok=True)
            wrapper.write_text('// bundled wrapper\n', encoding='utf-8')
        if command and '-shared' in command:
            library = Path(command[command.index('-o') + 1])
            library.parent.mkdir(parents=True, exist_ok=True)
            library.write_bytes(self.elf(self.abi))

    def elf(self, abi):
        header = bytearray(64)
        header[:7] = b'\x7fELF\x02\x01\x01'
        header[16:18] = (3).to_bytes(2, 'little')
        header[18:20] = self.module.target(abi)[2].to_bytes(2, 'little')
        return bytes(header)

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
        self.fixture('Windows')
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run', side_effect=self.fake_run) as runner, \
             patch.object(self.module.platform, 'system', return_value='Windows'):
            self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'))
        compiles = self.compiles(runner)
        self.assertTrue(compiles, 'the preparation did not run a compiler')
        for command in compiles:
            self.assertIsInstance(command, list)
            self.assertIn(' ', command[0])
            self.assertNotIn('"', command[0])
            self.assertNotIn('.cmd', str(command[0]))
            self.assertIn('clang', str(command[0]))
            targets = [part for part in command if part.startswith('--target=')]
            self.assertEqual(['--target=x86_64-linux-android26'], targets)
        for call in runner.call_args_list:
            self.assertNotIn('shell', call.kwargs)

    def test_the_compiler_runs_with_the_linux_android26_wrapper_on_a_linux_host(self):
        self.fixture('Linux')
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run', side_effect=self.fake_run) as runner, \
             patch.object(self.module.platform, 'system', return_value='Linux'):
            self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'))
        compiles = self.compiles(runner)
        self.assertTrue(compiles, 'the preparation did not run a compiler')
        for command in compiles:
            self.assertIsInstance(command, list)
            self.assertIn(' ', command[0])
            self.assertNotIn('"', command[0])
            # The Linux wrapper already names the Android target.
            self.assertRegex(str(command[0]), r'x86_64-linux-android26-clang(\+\+)?$')
            self.assertEqual([], [part for part in command if part.startswith('--target=')])
        for call in runner.call_args_list:
            self.assertNotIn('shell', call.kwargs)

    def run_preparation(self, host, abi):
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run', side_effect=self.fake_run) as runner, \
             patch.object(self.module.platform, 'system', return_value=host):
            self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'), abi)
        return runner

    def test_arm64_windows_compiles_to_its_own_directory_without_touching_x64(self):
        self.fixture('Windows', 'arm64-v8a')
        legacy = self.module.ROOT / 'build/verified-jni/x86_64/libmbot_sqlite_probe.so'
        legacy.parent.mkdir(parents=True)
        legacy.write_bytes(b'x64 must stay unchanged')
        x64_assets = self.module.ROOT / 'build/verified-assets/node-validation.json'
        x64_assets.parent.mkdir(parents=True)
        x64_assets.write_text('x64 flags must stay unchanged', encoding='utf-8')
        runner = self.run_preparation('Windows', 'arm64-v8a')
        commands = self.compiles(runner)
        self.assertEqual(5, len(commands))
        for command in commands:
            self.assertEqual(['--target=aarch64-linux-android26'],
                             [arg for arg in command if arg.startswith('--target=')])
            self.assertNotIn('"', command[0])
        for call in runner.call_args_list:
            self.assertNotIn('shell', call.kwargs)
        link = next(command for command in commands if '-shared' in command)
        prepared = self.module.prepared_root('arm64-v8a')
        self.assertEqual(str(prepared / 'verified-jni/arm64-v8a/libmbot_sqlite_probe.so'), link[-1])
        record = json.loads((prepared / 'verified-assets/third_party/better-sqlite3/build-inputs.json').read_text())
        self.assertEqual('arm64-v8a', record['abi'])
        self.assertEqual(self.module.digest(Path(link[-1])), record['librarySha256'])
        self.assertEqual(b'x64 must stay unchanged', legacy.read_bytes())
        self.assertEqual('x64 flags must stay unchanged', x64_assets.read_text())

    def test_arm64_linux_uses_the_aarch64_android26_wrappers(self):
        self.fixture('Linux', 'arm64-v8a')
        runner = self.run_preparation('Linux', 'arm64-v8a')
        commands = self.compiles(runner)
        self.assertEqual(5, len(commands))
        for command in commands:
            self.assertRegex(command[0], r'aarch64-linux-android26-clang(\+\+)?$')
            self.assertEqual([], [arg for arg in command if arg.startswith('--target=')])

    def test_arm64_cannot_reuse_the_x64_runtime_in_the_windows_ndk(self):
        self.host('windows-x86_64', 'clang.exe', 'clang++.exe')
        with self.assertRaisesRegex(RuntimeError, 'Missing pinned NDK'):
            self.module.ndk_host_tools(self.ndk, 'Windows', 'arm64-v8a')

    def test_unknown_abi_is_refused_before_io_or_running_any_tool(self):
        with patch.object(self.module.subprocess, 'check_output') as version, \
             patch.object(self.module.subprocess, 'run') as runner:
            with self.assertRaisesRegex(RuntimeError, 'Unsupported Android SQLite ABI'):
                self.module.prepare(self.ndk, Path('/unused/archive'), Path('/unused/esbuild'), '../../x86_64')
            version.assert_not_called()
            runner.assert_not_called()
        self.assertFalse(self.ndk.exists())

    def rewrite_config(self, **values):
        prepared = self.module.prepared_root(self.abi)
        config = prepared / 'node-headers/config.gypi'
        data = ast.literal_eval(config.read_text())
        data['variables'].update(values)
        config.write_text(repr(data), encoding='utf-8')
        manifest = prepared / 'node-headers.json'
        data = json.loads(manifest.read_text())
        data['files']['config.gypi'] = self.module.digest(config)
        manifest.write_text(json.dumps(data), encoding='utf-8')

    def assert_refused_before_compile(self, expected, abi=None):
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run') as runner:
            with self.assertRaisesRegex(RuntimeError, expected):
                self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'), abi or self.abi)
            runner.assert_not_called()

    def test_x64_headers_cannot_be_relabelled_arm64_even_with_a_matching_manifest(self):
        self.fixture('Windows', 'arm64-v8a')
        self.rewrite_config(target_arch='x64')
        self.assert_refused_before_compile('headers do not match Android ABI')

    def test_linux_headers_are_not_accepted_as_android_headers(self):
        self.fixture()
        self.rewrite_config(OS='linux')
        self.assert_refused_before_compile('headers do not match Android ABI')

    def test_a_relabelled_x64_node_library_is_refused_before_arm64_compilation(self):
        self.fixture('Windows', 'arm64-v8a')
        node = self.module.prepared_root(self.abi) / 'verified-jni' / self.abi / 'libnode.so'
        node.write_bytes(self.elf('x86_64'))
        self.assert_refused_before_compile('Expected shared ELF64')

    def test_missing_arm64_inputs_cannot_fall_back_to_the_existing_x64_inputs(self):
        self.fixture()
        legacy = self.module.ROOT / 'build/node-headers.json'
        original = legacy.read_bytes()
        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run') as runner:
            with self.assertRaises(FileNotFoundError):
                self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'), 'arm64-v8a')
            runner.assert_not_called()
        self.assertEqual(original, legacy.read_bytes())

    def test_wrong_linker_output_does_not_enable_sqlite_or_replace_the_probe(self):
        self.fixture('Windows', 'arm64-v8a')
        prepared = self.module.prepared_root(self.abi)
        script = prepared / 'verified-assets/node-fd-probe.cjs'
        original = script.read_bytes()

        def wrong_link(command, *args, **kwargs):
            self.fake_run(command, *args, **kwargs)
            if '-shared' in command:
                Path(command[command.index('-o') + 1]).write_bytes(self.elf('x86_64'))

        with patch.object(self.module.subprocess, 'check_output', return_value=self.module.PIN['esbuildVersion']), \
             patch.object(self.module.subprocess, 'run', side_effect=wrong_link), \
             patch.object(self.module.platform, 'system', return_value='Windows'):
            with self.assertRaisesRegex(RuntimeError, 'Expected shared ELF64'):
                self.module.prepare(self.ndk, self.archive, Path('/unused/esbuild'), self.abi)
        self.assertEqual(original, script.read_bytes())
        self.assertFalse(json.loads((prepared / 'verified-assets/node-validation.json').read_text())['requiresSqlite'])
        self.assertFalse((prepared / 'verified-assets/third_party/better-sqlite3/build-inputs.json').exists())

    def test_elf_guard_refuses_executables_elf32_big_endian_and_truncated_headers(self):
        library = Path(self.temporary.name) / 'invalid.so'
        cases = [b'', self.elf('arm64-v8a')[:20]]
        for offset, value in ((4, 1), (5, 2), (16, 2)):
            header = bytearray(self.elf('arm64-v8a'))
            header[offset] = value
            cases.append(bytes(header))
        for content in cases:
            with self.subTest(content=content):
                library.write_bytes(content)
                with self.assertRaisesRegex(RuntimeError, 'Expected shared ELF64'):
                    self.module.require_target_elf(library, 'arm64-v8a')

    # ── the fixture ──────────────────────────────────────────────────────────

    def fixture(self, host='Windows', abi='x86_64'):
        """A prepared host: pinned NDK, pinned headers, pinned archive."""
        module = self.module
        self.abi = abi
        self.root = Path(self.temporary.name) / 'root'
        module.ROOT = self.root / 'app'
        (module.ROOT / 'src/main/cpp').mkdir(parents=True)
        (module.ROOT / 'src/main/cpp/fd_vfs.cpp').write_text('// adapter', encoding='utf-8')
        (module.ROOT / 'src/main/cpp/fd_vfs_node.cpp').write_text('// adapter', encoding='utf-8')
        (Path(self.temporary.name) / 'LICENSE').write_text('AGPL fixture', encoding='utf-8')
        self.ndk.mkdir(parents=True, exist_ok=True)
        (self.ndk / 'source.properties').write_text('Pkg.Revision = ' + module.PIN['ndkVersion'], encoding='utf-8')
        if host == 'Windows':
            self.host('windows-x86_64', 'clang.exe', 'clang++.exe', abi=abi)
        else:
            triple = module.target(abi)[0]
            self.host('linux-x86_64', triple + '26-clang', triple + '26-clang++', abi=abi)

        prepared = module.prepared_root(abi)
        headers = prepared / 'node-headers'
        (headers / 'include/node').mkdir(parents=True)
        (headers / 'node.h').write_text('header fixture', encoding='utf-8')
        (headers / 'include/node/node.h').write_text('header fixture', encoding='utf-8')
        (headers / 'node_version.h').write_text(
            '#define NODE_MAJOR_VERSION 24\n#define NODE_MINOR_VERSION 22\n#define NODE_PATCH_VERSION 2\n',
            encoding='utf-8')
        (headers / 'config.gypi').write_text(repr({'variables': {
            'OS': 'android', 'target_arch': module.target(abi)[1],
            'v8_enable_pointer_compression': 0, 'v8_enable_sandbox': 0,
            'v8_enable_31bit_smis_on_64bit_arch': 0}}), encoding='utf-8')
        files = {path.relative_to(headers).as_posix(): module.digest(path)
                 for path in headers.rglob('*') if path.is_file()}
        (prepared / 'node-headers.json').write_text(json.dumps({'files': files}), encoding='utf-8')
        jni = prepared / 'verified-jni' / abi
        jni.mkdir(parents=True)
        (jni / 'libnode.so').write_bytes(self.elf(abi))

        assets = prepared / 'verified-assets'
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
