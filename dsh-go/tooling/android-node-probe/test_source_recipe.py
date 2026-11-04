# SPDX-License-Identifier: AGPL-3.0-only
"""Cache compilers without changing pinned source or losing argv boundaries."""
import importlib.util
from pathlib import Path
import shlex
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

FIXTURE = '''import shlex
from versions import dependency_versions
def set_compiler_commands(env):
    for name in ('CC', 'CXX', 'CC_host', 'CXX_host'):
        env[name] = shlex.quote(env[name])
    env['CC_target'] = env['CC']
    env['CXX_target'] = env['CXX']
flags = ['--cross-compiling', '--shared', '--openssl-no-asm', '--with-intl=none', '--ninja']
def compile_library(source, args, env):
    run(['ninja', '-C', source / 'out/Release', '-j', args.jobs, 'libnode'], env=env)
'''


class SourceRecipeTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('source_build', Path(__file__).with_name('build-source.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_host_and_android_compilers_use_cache_without_losing_spaces_or_flags(self):
        scope = {}
        with patch.dict(sys.modules, {'mbot_full_icu_versions': SimpleNamespace(dependency_versions=lambda *_: {})}):
            exec(self.module.make_recipe(FIXTURE), scope)
        env = {name: '/tool chain/' + name for name in ('CC', 'CXX', 'CC_host', 'CXX_host')}
        scope['set_compiler_commands'](env)
        for name in ('CC', 'CXX', 'CC_host', 'CXX_host'):
            self.assertEqual(shlex.split(env[name]), ['ccache', '/tool chain/' + name])
        self.assertEqual(env['CC_target'], env['CC'])
        self.assertEqual(env['CXX_target'], env['CXX'])
        self.assertEqual(scope['flags'], ['--cross-compiling', '--shared', '--openssl-no-asm', '--with-intl=full-icu', '--ninja'])

    def test_missing_or_duplicated_upstream_contract_is_refused(self):
        for source in [FIXTURE.replace('--with-intl=none', '--changed-intl'),
                       FIXTURE.replace('env[name] = shlex.quote(env[name])', 'pass'),
                       FIXTURE.replace('from versions import dependency_versions', 'from changed import dependency_versions'),
                       FIXTURE.replace("args.jobs, 'libnode'", "args.jobs, 'changed'"), FIXTURE + FIXTURE]:
            with self.subTest(source=source), self.assertRaisesRegex(RuntimeError, 'Unexpected Android configure recipe'):
                self.module.make_recipe(source)


    def test_real_version_reporter_runs_before_any_compilation_and_failure_stops_ninja(self):
        calls = []
        def report(source, variables):
            calls.append('metadata')
            raise RuntimeError('bad full ICU header')
        scope = {'run': lambda *_args, **_kwargs: calls.append('ninja'), 'config': lambda _: {'icu_small': False}}
        with patch.dict(sys.modules, {'mbot_full_icu_versions': SimpleNamespace(dependency_versions=report)}):
            exec(self.module.make_recipe(FIXTURE), scope)
        with self.assertRaisesRegex(RuntimeError, 'bad full ICU header'):
            scope['compile_library'](Path('/configured-source'), SimpleNamespace(jobs=2), {})
        self.assertEqual(calls, ['metadata'])

    def test_full_icu_metadata_reads_header_values_and_keeps_other_checks(self):
        original = """def dependency_versions(source, variables):
    def enabled(name):
        return variables.get(name) in (True, 1, '1', 'true')
    def macro(path, name):
        return source[(path, name)]
    if enabled('v8_enable_i18n_support'):
        raise RuntimeError('Build-time version reporting requires --with-intl=none.')
    if enabled('node_shared_openssl'):
        raise RuntimeError('shared dependency refused')
    headers = {
        'acorn': ('src/acorn_version.h', 'ACORN_VERSION'),
    }
    return {key: macro(*value) for key, value in headers.items()}
"""
        scope = {}
        exec(self.module.make_versions(original), scope)
        report = scope['dependency_versions']
        source = {
            ('src/acorn_version.h', 'ACORN_VERSION'): '8.15.0',
            ('deps/icu-small/source/common/unicode/uvernum.h', 'U_ICU_VERSION'): '77.1',
            ('deps/icu-small/source/common/unicode/uchar.h', 'U_UNICODE_VERSION'): '16.0',
        }
        variables = {'v8_enable_i18n_support': 1, 'icu_small': False}
        self.assertEqual(report(source, variables), {'acorn': '8.15.0', 'icu': '77.1', 'unicode': '16.0'})
        self.assertEqual(variables, {'v8_enable_i18n_support': 1, 'icu_small': False})
        for invalid in [{}, {'v8_enable_i18n_support': 0, 'icu_small': False},
                        {'v8_enable_i18n_support': 1, 'icu_small': True}]:
            with self.subTest(invalid=invalid), self.assertRaisesRegex(RuntimeError, 'actual full-ICU'):
                report(source, invalid)
        with self.assertRaisesRegex(RuntimeError, 'shared dependency refused'):
            report(source, {**variables, 'node_shared_openssl': 1})
        with self.assertRaises(KeyError):
            report({}, variables)
        for changed in [original.replace('--with-intl=none', '--changed'), original + original]:
            with self.assertRaisesRegex(RuntimeError, 'Unexpected dependency version reporting'):
                self.module.make_versions(changed)
