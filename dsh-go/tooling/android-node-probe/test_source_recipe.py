# SPDX-License-Identifier: AGPL-3.0-only
"""Cache compilers without changing pinned source or losing argv boundaries."""
import importlib.util
from pathlib import Path
import shlex
import unittest

FIXTURE = '''import shlex
def set_compiler_commands(env):
    for name in ('CC', 'CXX', 'CC_host', 'CXX_host'):
        env[name] = shlex.quote(env[name])
    env['CC_target'] = env['CC']
    env['CXX_target'] = env['CXX']
flags = ['--cross-compiling', '--shared', '--openssl-no-asm', '--with-intl=none', '--ninja']
'''


class SourceRecipeTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('source_build', Path(__file__).with_name('build-source.py'))
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_host_and_android_compilers_use_cache_without_losing_spaces_or_flags(self):
        scope = {}
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
                       FIXTURE.replace('env[name] = shlex.quote(env[name])', 'pass'), FIXTURE + FIXTURE]:
            with self.subTest(source=source), self.assertRaisesRegex(RuntimeError, 'Unexpected Android configure recipe'):
                self.module.make_recipe(source)
