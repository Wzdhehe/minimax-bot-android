# SPDX-License-Identifier: AGPL-3.0-only
"""Linux checks for the exact C++ lease primitive used by Android JNI.

These tests exercise kernel locking and process death, not Android Binder or
device support. Independent subprocesses open their own directory descriptors.
"""
import ctypes
import errno
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


CHILD = r"""
import ctypes, os, sys
library = ctypes.CDLL(sys.argv[1])
library.take.argtypes = [ctypes.c_int]
library.take.restype = ctypes.c_int
fd = os.open(sys.argv[2], os.O_RDONLY | os.O_DIRECTORY)
result = library.take(fd)
print(result, flush=True)
if result == 0 and sys.argv[3] == 'hold':
    sys.stdin.read(1)
os.close(fd)
"""


@unittest.skipUnless(sys.platform.startswith('linux') and shutil.which('c++'), 'requires Linux and a C++ compiler')
class DirectoryLeaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix='mbot-lease-build-')
        cls.library_path = str(Path(cls.build.name, 'lease.so'))
        source_dir = Path(__file__).resolve().parents[2] / 'app/android/app/src/main/cpp'
        wrapper = ('#include "runtime_directory_lease.h"\n#include "runtime_stage_cleanup.h"\n'
                   'extern "C" int take(int fd) { return try_runtime_directory_lease(fd); }\n'
                   'extern "C" int sweep(int fd) { return mbot_stage_cleanup::sweep(fd); }\n'
                   'extern "C" int limited(int fd, size_t entries, size_t batch) '
                   '{ return mbot_stage_cleanup::sweep(fd, entries, batch); }\n')
        subprocess.run(['c++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-fPIC', '-shared',
                        '-I', str(source_dir), '-x', 'c++', '-', '-o', cls.library_path],
                       input=wrapper, text=True, capture_output=True, check=True)
        cls.library = ctypes.CDLL(cls.library_path)
        cls.library.take.argtypes = [ctypes.c_int]
        cls.library.take.restype = ctypes.c_int
        cls.library.sweep.argtypes = [ctypes.c_int]
        cls.library.sweep.restype = ctypes.c_int
        cls.library.limited.argtypes = [ctypes.c_int, ctypes.c_size_t, ctypes.c_size_t]
        cls.library.limited.restype = ctypes.c_int

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        self.root = tempfile.TemporaryDirectory(prefix='mbot-lease-')
        self.addCleanup(self.root.cleanup)

    def open_directory(self):
        return os.open(self.root.name, os.O_RDONLY | os.O_DIRECTORY)

    def child(self, mode='try'):
        return subprocess.Popen([sys.executable, '-c', CHILD, self.library_path, self.root.name, mode],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def test_invalid_and_non_private_descriptors_are_refused(self):
        self.assertEqual(errno.EINVAL, self.library.take(-1))
        filename = Path(self.root.name, 'data')
        filename.write_bytes(b'unchanged')
        fd = os.open(filename, os.O_RDONLY)
        try:
            self.assertEqual(errno.ENOTDIR, self.library.take(fd))
        finally:
            os.close(fd)
        os.chmod(self.root.name, 0o755)
        fd = self.open_directory()
        try:
            self.assertEqual(errno.EACCES, self.library.take(fd))
        finally:
            os.close(fd)
        self.assertEqual(b'unchanged', filename.read_bytes())

    def test_duplicates_hold_the_lease_until_the_last_descriptor_closes(self):
        first = self.open_directory()
        second = self.open_directory()
        duplicate = -1
        try:
            self.assertEqual(0, self.library.take(first))
            duplicate = os.dup(first)
            os.close(first)
            first = -1
            self.assertEqual(errno.EAGAIN, self.library.take(second))
            os.close(duplicate)
            duplicate = -1
            self.assertEqual(0, self.library.take(second))
        finally:
            for fd in (first, second, duplicate):
                if fd >= 0:
                    os.close(fd)

    def test_cross_process_contention_and_process_death_release(self):
        parent_fd = self.open_directory()
        try:
            self.assertEqual(0, self.library.take(parent_fd))
            contender = self.child()
            out, err = contender.communicate(timeout=5)
            self.assertEqual(0, contender.returncode)
            self.assertEqual('', err)
            self.assertEqual(errno.EAGAIN, int(out.strip()))
        finally:
            os.close(parent_fd)

        owner = self.child('hold')
        try:
            self.assertEqual('0', owner.stdout.readline().strip())
            contender_fd = self.open_directory()
            try:
                self.assertEqual(errno.EAGAIN, self.library.take(contender_fd))
                owner.kill()
                owner.communicate(timeout=5)
                self.assertEqual(0, self.library.take(contender_fd))
            finally:
                os.close(contender_fd)
        finally:
            if owner.poll() is None:
                owner.kill()
                owner.communicate(timeout=5)

    def write_private(self, name, data=b'test'):
        path = Path(self.root.name, name)
        path.write_bytes(data)
        os.chmod(path, 0o600)
        return path

    def test_cleanup_only_removes_exact_reserved_stage_names(self):
        memory = self.write_private('bot-memory-stage-' + 'a' * 64 + '.bin')
        native = self.write_private('.native-publish-123e4567-e89b-12d3-a456-426614174000.tmp')
        legacy = self.write_private('bot-memory-' + 'b' * 64 + '.bin', b'legacy-or-durable')
        near = self.write_private('bot-memory-stage-' + 'c' * 63 + 'Z.bin', b'not-a-stage')
        ordinary = self.write_private('bot-state.json', b'keep-state')
        fd = self.open_directory()
        try:
            self.assertEqual(0, self.library.take(fd))
            self.assertEqual(0, self.library.sweep(fd))
            self.assertFalse(memory.exists())
            self.assertFalse(native.exists())
            self.assertEqual(b'legacy-or-durable', legacy.read_bytes())
            self.assertEqual(b'not-a-stage', near.read_bytes())
            self.assertEqual(b'keep-state', ordinary.read_bytes())
            self.assertEqual(0, self.library.sweep(fd))
        finally:
            os.close(fd)

    def test_cleanup_refuses_symlinks_hardlinks_and_public_files_before_deleting_a_batch(self):
        valid = self.write_private('bot-memory-stage-' + 'a' * 64 + '.bin')
        target = self.write_private('bot-state.json', b'unchanged')
        suspect = Path(self.root.name, 'bot-memory-stage-' + 'b' * 64 + '.bin')
        fd = self.open_directory()
        try:
            self.assertEqual(0, self.library.take(fd))
            suspect.symlink_to(target)
            self.assertEqual(errno.EACCES, self.library.sweep(fd))
            self.assertTrue(valid.exists())
            self.assertEqual(b'unchanged', target.read_bytes())
            suspect.unlink()
            os.link(target, suspect)
            self.assertEqual(errno.EACCES, self.library.sweep(fd))
            self.assertTrue(valid.exists())
            suspect.unlink()
            suspect.write_bytes(b'public')
            os.chmod(suspect, 0o644)
            self.assertEqual(errno.EACCES, self.library.sweep(fd))
            self.assertTrue(valid.exists())
            self.assertEqual(b'unchanged', target.read_bytes())
        finally:
            os.close(fd)

    def test_cleanup_is_bounded_and_can_resume_after_a_partial_batch(self):
        stages = [self.write_private('bot-memory-stage-' + digit * 64 + '.bin') for digit in 'abc']
        fd = self.open_directory()
        try:
            self.assertEqual(0, self.library.take(fd))
            self.assertEqual(errno.E2BIG, self.library.limited(fd, 2, 1))
            self.assertTrue(all(path.exists() for path in stages))
            self.assertEqual(errno.EAGAIN, self.library.limited(fd, 100, 1))
            self.assertEqual(2, sum(path.exists() for path in stages))
            self.assertEqual(errno.EAGAIN, self.library.limited(fd, 100, 1))
            self.assertEqual(1, sum(path.exists() for path in stages))
            self.assertEqual(0, self.library.limited(fd, 100, 1))
            self.assertTrue(all(not path.exists() for path in stages))
        finally:
            os.close(fd)


if __name__ == '__main__':
    unittest.main()
