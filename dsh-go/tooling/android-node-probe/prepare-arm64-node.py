#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Prepare Node inputs from a reviewed ARM64 candidate, for verification only.

This prepares the Node headers, the arm64-v8a native library and the shared
probe assets from an ARM64 candidate whose provenance was reviewed and
committed. It does NOT admit the product: the candidate stays unadmitted, the
prepared inputs live in their own ABI directory and the existing x86_64
prepared inputs are never written, replaced or removed.

Trust comes only from the committed review record
(dsh-go/tooling/android-node-probe/node-arm64-candidate.json). No option of
this script supplies a trusted digest, and the archive never authorizes
itself: the metadata inside the archive is compared against that record.
"""
import argparse
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
REPOSITORY = ROOT.parents[2]

PROBE = 'dsh-go/tooling/android-node-probe'
CANDIDATE_RECORD = PROBE + '/node-arm64-candidate.json'
SOURCE_BUILD_PIN = PROBE + '/node-arm64-source-build.json'
PRODUCER_INPUTS = {
    PROBE + '/node-arm64-source-build.json',
    PROBE + '/build-arm64-source.py',
    PROBE + '/build-source.py',
    PROBE + '/node-source-build.json',
}
ABI = 'arm64-v8a'
ANDROID_API = 26
NDK_REVISION = '27.3.13750724'
NODE_MODULES = '137'
NODE_LIBRARY = 'bin/' + ABI + '/libnode.so'
NODE_RUNTIME = 'sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'
# The only regular files a candidate archive may carry.
ALLOWED_MEMBERS = {'LICENSE', 'LICENSE-nodejs-mobile', 'build-info.json', NODE_LIBRARY}
REQUIRED_MEMBERS = {
    'build-info.json',
    NODE_LIBRARY,
    'include/node/config.gypi',
    'include/node/node.h',
    'include/node/node_version.h',
    'LICENSE',
    'LICENSE-nodejs-mobile',
}
MEMBER_LIMIT = 4000
MAX_MEMBER_SIZE = 160 * 1024 * 1024
MAX_TOTAL_SIZE = 256 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200
MIN_COMPRESSION_FLOOR = 1024 * 1024
CHUNK = 1024 * 1024
# The x86_64 prepared inputs of the existing probe; never a target.
X64_LOCATIONS = ('build/verified-jni', 'build/node-headers', 'build/verified-assets')


def digest(path):
    """The SHA256 of a file, as lowercase hexadecimal."""
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def git(repository, *args):
    """One git command, as an argv list, without a shell."""
    return subprocess.check_output(['git', '-C', str(repository), *args])


def committed_bytes(repository, path):
    """The committed bytes of a repository path.

    A worktree that differs from HEAD is refused: the reviewed record and the
    pinned producer inputs are the only accepted source of trust.
    """
    repository = Path(repository)
    try:
        committed = git(repository, 'show', 'HEAD:' + path)
    except (subprocess.CalledProcessError, OSError) as error:
        raise RuntimeError('Commit the reviewed ARM64 candidate record') from error
    worktree = repository / path
    if not worktree.is_file() or worktree.read_bytes() != committed:
        raise RuntimeError('Commit the reviewed ARM64 candidate record')
    return committed


def load_record(repository):
    """The committed review record and the ARM64 source build pin."""
    record = json.loads(committed_bytes(repository, CANDIDATE_RECORD).decode('utf-8'))
    pin = json.loads(committed_bytes(repository, SOURCE_BUILD_PIN).decode('utf-8'))
    if record.get('admittedForProduct') is not False:
        raise RuntimeError('The reviewed ARM64 candidate record does not admit the product')
    if record.get('abi') != ABI:
        raise RuntimeError('The reviewed ARM64 candidate record names another ABI')
    if record.get('androidApi') != ANDROID_API:
        raise RuntimeError('The reviewed ARM64 candidate record names another Android API level')
    return record, pin


def check_producer_inputs(repository, record):
    """The producer inputs and commit of the reviewed candidate must still hold."""
    producer = record.get('producer') or {}
    commit = producer.get('commit')
    if not isinstance(commit, str) or not re.fullmatch('[0-9a-f]{40}', commit):
        raise RuntimeError('The reviewed ARM64 producer commit is not a full commit id')
    inputs = producer.get('inputs') or {}
    if set(inputs) != PRODUCER_INPUTS:
        raise RuntimeError('ARM64 producer inputs changed since the reviewed candidate')
    for path, expected in inputs.items():
        try:
            committed = git(repository, 'show', 'HEAD:' + path)
        except (subprocess.CalledProcessError, OSError) as error:
            raise RuntimeError('ARM64 producer inputs changed since the reviewed candidate') from error
        if hashlib.sha256(committed).hexdigest() != expected:
            raise RuntimeError('ARM64 producer inputs changed since the reviewed candidate')
    ancestor = subprocess.run(['git', '-C', str(repository), 'merge-base', '--is-ancestor', commit, 'HEAD'],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if ancestor.returncode != 0:
        raise RuntimeError('The reviewed ARM64 producer commit is not an ancestor of HEAD')


def check_ndk(ndk):
    """The pinned NDK revision, never a different one."""
    properties = Path(ndk) / 'source.properties'
    if not properties.is_file() or f'Pkg.Revision = {NDK_REVISION}' not in properties.read_text(encoding='utf-8'):
        raise RuntimeError('Use the pinned NDK revision ' + NDK_REVISION)


def check_archive(archive, record):
    """The candidate archive is the reviewed one, byte for byte."""
    archive = Path(archive)
    if not archive.is_file():
        raise RuntimeError('ARM64 candidate archive is missing')
    if archive.stat().st_size != record['archiveSize']:
        raise RuntimeError('ARM64 candidate archive size mismatch')
    if digest(archive) != record['archiveSha256']:
        raise RuntimeError('ARM64 candidate archive digest mismatch')


def check_member_name(name, seen):
    """Reject unsafe, aliased or duplicated archive member names."""
    if not name or '\\' in name:
        raise RuntimeError('Unsafe ARM64 candidate archive member name')
    key = name.lower()
    if key in seen:
        raise RuntimeError('Duplicate ARM64 candidate archive member')
    seen.add(key)
    if PurePosixPath(name).is_absolute() or (len(name) > 1 and name[1] == ':'):
        raise RuntimeError('Unsafe ARM64 candidate archive member path')
    parts = name.rstrip('/').split('/')
    if not parts or any(part == '' for part in parts):
        raise RuntimeError('Unsafe ARM64 candidate archive member path')
    if '..' in parts:
        raise RuntimeError('Unsafe ARM64 candidate archive member path')
    if ':' in parts[0]:
        raise RuntimeError('Unsafe ARM64 candidate archive member path')


def safe_members(package):
    """The allowed regular files of the candidate archive.

    Only the reviewed file list is accepted. Duplicates, aliased names, links,
    encrypted entries and oversized members are refused before anything is
    written to disk.
    """
    infos = package.infolist()
    if len(infos) > MEMBER_LIMIT:
        raise RuntimeError('Too many ARM64 candidate archive members')
    seen = set()
    members = {}
    total = 0
    for info in infos:
        check_member_name(info.filename, seen)
        mode = info.external_attr >> 16
        kind = stat.S_IFMT(mode)
        if kind not in (0, stat.S_IFREG, stat.S_IFDIR):
            raise RuntimeError('Unsafe ARM64 candidate archive member type')
        if info.flag_bits & 1:
            raise RuntimeError('Encrypted ARM64 candidate archive member')
        if kind == stat.S_IFDIR or info.is_dir():
            continue
        if info.filename not in ALLOWED_MEMBERS and not info.filename.startswith('include/node/'):
            raise RuntimeError('Unexpected ARM64 candidate member')
        if info.file_size > MAX_MEMBER_SIZE:
            raise RuntimeError('ARM64 candidate archive member budget exceeded')
        total += info.file_size
        if total > MAX_TOTAL_SIZE:
            raise RuntimeError('ARM64 candidate archive extraction budget exceeded')
        if info.file_size > max(info.compress_size * MAX_COMPRESSION_RATIO, MIN_COMPRESSION_FLOOR):
            raise RuntimeError('Suspicious ARM64 candidate archive compression ratio')
        members[info.filename] = info
    missing = REQUIRED_MEMBERS - set(members)
    if missing:
        raise RuntimeError('ARM64 candidate archive is missing ' + ', '.join(sorted(missing)))
    return members


def check_metadata(meta, record, pin):
    """Compare the archive metadata with the committed review record.

    The archive never authorizes itself: every fact it claims about its own
    provenance is compared with the record that was reviewed and committed.
    """
    source = meta.get('source') or {}
    for key, expected in (('commit', pin['nodeCommit']), ('tree', pin['nodeTree']), ('version', pin['version'])):
        if source.get(key) != expected:
            raise RuntimeError('Unexpected ARM64 candidate source metadata')
    slices = meta.get('slices')
    if not isinstance(slices, list) or len(slices) != 1:
        raise RuntimeError('Unexpected ARM64 candidate slice count')
    entry = slices[0]
    if entry.get('target') != ABI:
        raise RuntimeError('Unexpected ARM64 candidate slice target')
    for key, expected in (('commit', pin['nodeCommit']), ('tree', pin['nodeTree']), ('version', pin['version'])):
        if entry.get(key) != expected:
            raise RuntimeError('Unexpected ARM64 candidate slice metadata')
    versions = entry.get('versions') or {}
    if versions.get('node') != record['versions']['node']:
        raise RuntimeError('Unexpected ARM64 candidate Node version')
    if str(versions.get('modules')) != NODE_MODULES:
        raise RuntimeError('Unexpected ARM64 candidate Node module version')
    if versions.get('icu') != record['versions']['icu'] or versions.get('unicode') != record['versions']['unicode']:
        raise RuntimeError('Unexpected ARM64 candidate ICU version')
    if entry.get('config_sha256') != record['configSha256']:
        raise RuntimeError('Unexpected ARM64 candidate configuration digest')
    if entry.get('binary_sha256') != {NODE_LIBRARY: record['librarySha256']}:
        raise RuntimeError('Unexpected ARM64 candidate binary digest')
    expected_recipe = {**pin,
                       'modifiedRecipeSha256': record['modifiedRecipeSha256'],
                       'versionsScriptSha256': record['versionsScriptSha256'],
                       'modifiedVersionsScriptSha256': record['modifiedVersionsScriptSha256']}
    if meta.get('mbotSourceBuild') != expected_recipe:
        raise RuntimeError('Unexpected ARM64 source build recipe')
    if meta.get('mbotArm64Producer') != record['producer']:
        raise RuntimeError('Unexpected ARM64 producer provenance')


def extract(package, members, staging):
    """Write the allowed members into the fresh staging directory."""
    headers = staging / 'node-headers'
    jni = staging / 'verified-jni' / ABI
    notices = staging / 'verified-assets/third_party/nodejs-mobile'
    for name, info in members.items():
        if name.startswith('include/node/'):
            destination = headers.joinpath(*PurePosixPath(name).parts[2:])
        elif name == NODE_LIBRARY:
            destination = jni / 'libnode.so'
        else:
            destination = notices / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        written = 0
        with package.open(info) as source, destination.open('wb') as output:
            while True:
                chunk = source.read(CHUNK)
                if not chunk:
                    break
                written += len(chunk)
                if written > info.file_size:
                    raise RuntimeError('ARM64 candidate archive member exceeded its declared size')
                output.write(chunk)


def check_headers(headers, record):
    """The prepared headers must describe this exact Android arm64 build."""
    config = headers / 'config.gypi'
    if digest(config) != record['configSha256']:
        raise RuntimeError('ARM64 candidate configuration digest mismatch')
    variables = ast.literal_eval(config.read_text(encoding='utf-8'))['variables']
    if variables.get('OS') != 'android' or variables.get('target_arch') != 'arm64':
        raise RuntimeError('ARM64 candidate headers do not match Android arm64')
    if variables.get('node_shared') not in ('true', True, 1):
        raise RuntimeError('ARM64 candidate headers are not a shared Node build')
    if str(variables.get('node_module_version')) != NODE_MODULES:
        raise RuntimeError('Unexpected ARM64 candidate Node module version')
    if variables.get('v8_enable_i18n_support') not in (1, True, '1', 'true') \
            or variables.get('icu_small') not in (0, False, '0', 'false'):
        raise RuntimeError('ARM64 candidate must include full ICU')
    for key in ('v8_enable_pointer_compression', 'v8_enable_sandbox', 'v8_enable_31bit_smis_on_64bit_arch'):
        if variables.get(key) != 0:
            raise RuntimeError('Unsupported ARM64 candidate V8 ABI configuration')
    version = (headers / 'node_version.h').read_text(encoding='utf-8')
    numbers = []
    for part in ('MAJOR', 'MINOR', 'PATCH'):
        match = re.search(r'#define NODE_' + part + r'_VERSION\s+(\d+)', version)
        if not match:
            raise RuntimeError('ARM64 candidate headers have no Node version')
        numbers.append(match.group(1))
    if '.'.join(numbers) != record['versions']['node']:
        raise RuntimeError('ARM64 candidate header version mismatch')


def read_string(data, offset):
    """One NUL terminated string of the dynamic string table."""
    if offset < 0 or offset >= len(data):
        raise RuntimeError('Malformed ARM64 candidate dynamic string table')
    end = data.find(b'\x00', offset)
    if end < 0:
        raise RuntimeError('Malformed ARM64 candidate dynamic string table')
    return data[offset:end].decode('ascii', 'replace')


def vaddr_to_offset(address, loads):
    """The file offset of a virtual address, through the load segments."""
    for p_offset, p_vaddr, p_filesz in loads:
        if p_vaddr <= address < p_vaddr + p_filesz:
            return p_offset + (address - p_vaddr)
    raise RuntimeError('Unmapped ARM64 candidate ELF address')


def elf_notes(data, offset, size):
    """The notes of one PT_NOTE segment."""
    end = offset + size
    position = offset
    while position + 12 <= end:
        namesz, descsz, kind = struct.unpack_from('<III', data, position)
        position += 12
        if position + namesz > end:
            break
        name = data[position:position + namesz].rstrip(b'\x00').decode('ascii', 'replace')
        position += (namesz + 3) & ~3
        if position + descsz > end:
            break
        desc = data[position:position + descsz]
        position += (descsz + 3) & ~3
        yield name, kind, desc
        if namesz == 0 and descsz == 0:
            break


def dynamic_needed(data, dynamic, loads):
    """The DT_NEEDED entries of the dynamic section."""
    p_offset, p_filesz = dynamic
    string_table = None
    offsets = []
    for position in range(p_offset, p_offset + p_filesz - 15, 16):
        tag, value = struct.unpack_from('<qQ', data, position)
        if tag == 0:
            break
        if tag == 1:
            offsets.append(value)
        elif tag == 5:
            string_table = value
    if string_table is None:
        raise RuntimeError('Expected a dynamic string table in the ARM64 candidate library')
    base = vaddr_to_offset(string_table, loads)
    return [read_string(data, base + offset) for offset in offsets]


def check_android_elf(path, allowed_needed, prebuilt_runtime=False):
    """Check one Android arm64-v8a ELF64 shared library.

    The parser is deliberately small: the header, the program headers, the
    Android API note and the dynamic dependencies. It proves the architecture,
    the page alignment and the API level of the file, not its origin.
    Libraries built here must carry exactly API26. The NDK's prebuilt
    libc++_shared is API-neutral and is accepted with any API at or below 26
    (prebuilt_runtime=True), never above the app minimum.
    """
    data = Path(path).read_bytes()
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        raise RuntimeError('Expected a little-endian ELF64 Android arm64-v8a library')
    e_type, e_machine = struct.unpack_from('<HH', data, 16)
    if e_type != 3:
        raise RuntimeError('Expected a shared ARM64 candidate library')
    if e_machine != 183:
        raise RuntimeError('Expected an AArch64 ARM64 candidate library')
    e_phoff = struct.unpack_from('<Q', data, 32)[0]
    e_phentsize = struct.unpack_from('<H', data, 54)[0]
    e_phnum = struct.unpack_from('<H', data, 56)[0]
    if e_phentsize < 56 or e_phoff + e_phnum * e_phentsize > len(data):
        raise RuntimeError('Malformed ARM64 candidate program headers')
    loads = []
    aligns = []
    notes = []
    dynamic = None
    for index in range(e_phnum):
        base = e_phoff + index * e_phentsize
        p_type = struct.unpack_from('<I', data, base)[0]
        p_offset, p_vaddr = struct.unpack_from('<QQ', data, base + 8)
        p_filesz = struct.unpack_from('<Q', data, base + 32)[0]
        p_align = struct.unpack_from('<Q', data, base + 48)[0]
        if p_type == 1:
            if p_align < 16384 or p_align & (p_align - 1) or p_offset % p_align != p_vaddr % p_align:
                raise RuntimeError('Unexpected ARM64 candidate ELF segment alignment')
            loads.append((p_offset, p_vaddr, p_filesz))
            aligns.append(p_align)
        elif p_type == 4:
            notes.append((p_offset, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
    if not loads:
        raise RuntimeError('Expected a loadable ARM64 candidate library')
    api = None
    for p_offset, p_filesz in notes:
        for name, kind, desc in elf_notes(data, p_offset, p_filesz):
            if name == 'Android' and kind == 1 and len(desc) >= 4:
                api = int.from_bytes(desc[:4], 'little')
    if api is None or (api > ANDROID_API if prebuilt_runtime else api != ANDROID_API):
        raise RuntimeError('Expected Android API26 ELF note')
    if dynamic is None:
        raise RuntimeError('Expected a dynamic section in the ARM64 candidate library')
    needed = dynamic_needed(data, dynamic, loads)
    for name in needed:
        if name not in allowed_needed:
            raise RuntimeError('Unexpected native dependency')
    return {'needed': needed, 'api': api, 'loadAlign': min(aligns)}


def arm64_ndk_tools(ndk, system=None):
    """Select the pinned NDK's arm64-v8a host compiler, never the x86_64 one.

    Use the Windows executable directly: .cmd wrappers require shell handling
    and cannot safely be treated as an executable with arbitrary path quoting.
    No shell is involved, and no caller-selected Android target is accepted.
    """
    system = platform.system() if system is None else system
    if system == 'Linux':
        toolchain = Path(ndk) / 'toolchains/llvm/prebuilt/linux-x86_64'
        command = [str(toolchain / 'bin/aarch64-linux-android26-clang++')]
    elif system == 'Windows':
        toolchain = Path(ndk) / 'toolchains/llvm/prebuilt/windows-x86_64'
        command = [str(toolchain / 'bin/clang++.exe'), '--target=aarch64-linux-android26']
    else:
        raise RuntimeError('Verification preparation supports Linux or Windows hosts only')
    runtime = toolchain / NODE_RUNTIME
    if not Path(command[0]).is_file() or not runtime.is_file():
        raise RuntimeError('Missing pinned NDK compiler or Android arm64-v8a runtime')
    return toolchain, command


def compile_glue(compiler, headers, sources, jni):
    """Build the JNI glue against the prepared arm64-v8a Node library."""
    command = compiler + ['-std=c++20', '-fPIC', '-shared',
                          '-fvisibility=hidden', '-O2',
                          '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
                          '-I', str(headers), str(sources / 'node_probe.cpp'),
                          '-L', str(jni), '-lnode', '-o', str(jni / 'libmbot_node_probe.so')]
    subprocess.run(command, check=True)
    return command


def load_probe_module():
    """Import the read-only x86_64 probe preparer, for its asset bundler."""
    spec = importlib.util.spec_from_file_location('readonly_x64_probe', ROOT / 'prepare-probe.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def base_probe_assets(assets):
    """Bundle the shared verification probe assets into the given directory.

    Only the pure bundler of the existing probe is reused; its own
    preparation is never called, so the x86_64 prepared inputs stay untouched.
    """
    load_probe_module().bundle(assets)


def guard_output_root(output):
    """The x86_64 prepared input locations are never a target."""
    for location in X64_LOCATIONS:
        guarded = (ROOT / location).resolve()
        if output == guarded or guarded in output.parents:
            raise RuntimeError('Refusing to write x86_64 prepared inputs')


def prepare(ndk, archive, output_root=None, repository=REPOSITORY, system=None):
    """Prepare the reviewed ARM64 candidate inputs, for verification only."""
    ndk = Path(ndk)
    archive = Path(archive)
    repository = Path(repository)
    output = Path(output_root) if output_root is not None else ROOT / 'build/abi' / ABI
    output = output.resolve()
    guard_output_root(output)
    if output.exists():
        raise RuntimeError('ARM64 Node output already exists; move it aside')
    record, pin = load_record(repository)
    check_producer_inputs(repository, record)
    check_ndk(ndk)
    check_archive(archive, record)
    staging = output.with_name(output.name + '.partial-' + str(os.getpid()))
    if staging.exists():
        raise RuntimeError('ARM64 Node staging directory already exists; move it aside')
    try:
        with zipfile.ZipFile(archive) as package:
            members = safe_members(package)
            metadata = json.loads(package.read('build-info.json').decode('utf-8'))
            check_metadata(metadata, record, pin)
            staging.mkdir(parents=True)
            extract(package, members, staging)
        headers = staging / 'node-headers'
        jni = staging / 'verified-jni' / ABI
        assets = staging / 'verified-assets'
        if digest(jni / 'libnode.so') != record['librarySha256']:
            raise RuntimeError('ARM64 candidate library digest mismatch')
        check_android_elf(jni / 'libnode.so', {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libc++_shared.so'})
        check_headers(headers, record)
        (staging / 'node-headers.json').write_text(json.dumps({
            'nodeArchiveSha256': digest(archive),
            'files': {path.relative_to(headers).as_posix(): digest(path)
                      for path in sorted(headers.rglob('*')) if path.is_file()},
        }) + '\n', encoding='utf-8')
        toolchain, compiler = arm64_ndk_tools(ndk, system)
        shutil.copyfile(toolchain / NODE_RUNTIME, jni / 'libc++_shared.so')
        check_android_elf(jni / 'libc++_shared.so', {'libc.so', 'libm.so', 'libdl.so'}, prebuilt_runtime=True)
        (assets / 'third_party/ndk').mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ndk / 'NOTICE', assets / 'third_party/ndk/NOTICE')
        sources = staging / 'probe-native-source'
        sources.mkdir(parents=True, exist_ok=True)
        bridge_inputs = {}
        for name in ('node_probe.cpp', 'node_snapshot_bridge.inc'):
            shutil.copyfile(ROOT / 'src/main/cpp' / name, sources / name)
            bridge_inputs[name] = digest(sources / name)
        compile_glue(compiler, headers, sources, jni)
        if any(digest(ROOT / 'src/main/cpp' / name) != sha for name, sha in bridge_inputs.items()):
            raise RuntimeError('Node JNI inputs changed during preparation')
        library = jni / 'libmbot_node_probe.so'
        check_android_elf(library, {'libnode.so', 'libc++_shared.so', 'libc.so', 'libm.so', 'libdl.so', 'liblog.so'})
        (staging / 'node-bridge-inputs.json').write_text(json.dumps({
            'inputs': bridge_inputs, 'librarySha256': digest(library),
        }) + '\n', encoding='utf-8')
        base_probe_assets(assets)
        (assets / 'node-validation.json').write_text(json.dumps({
            'requiresIntl': True, 'sourceArchiveSha256': record['archiveSha256'],
            'requiresSqlite': False, 'abi': ABI,
            'candidateRecord': 'node-arm64-candidate.json', 'admittedForProduct': False,
        }) + '\n', encoding='utf-8')
        output.parent.mkdir(parents=True, exist_ok=True)
        os.replace(staging, output)
    except BaseException:
        # Only the staging directory this run created is removed; the prepared
        # inputs of another attempt are never removed.
        shutil.rmtree(staging, ignore_errors=True)
        raise
    print('Prepared reviewed ARM64 Node candidate inputs (verification only, product not admitted)')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--output-root', type=Path)
    args = parser.parse_args()
    prepare(args.ndk.resolve(), args.archive.resolve(),
            args.output_root.resolve() if args.output_root else None)
