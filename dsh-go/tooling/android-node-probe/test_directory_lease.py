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
        wrapper = '#include "runtime_directory_lease.h"\nextern "C" int take(int fd) { return try_runtime_directory_lease(fd); }\n'
        subprocess.run(['c++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-fPIC', '-shared',
                        '-I', str(source_dir), '-x', 'c++', '-', '-o', cls.library_path],
                       input=wrapper, text=True, capture_output=True, check=True)
        cls.library = ctypes.CDLL(cls.library_path)
        cls.library.take.argtypes = [ctypes.c_int]
        cls.library.take.restype = ctypes.c_int

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


if __name__ == '__main__':
    unittest.main()
