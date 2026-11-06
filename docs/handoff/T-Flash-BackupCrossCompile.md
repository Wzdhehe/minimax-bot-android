<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-Flash-BackupCrossCompile：备份核心的 Android 双ABI编译

## 结果：已完成（2026-11-06，用户转述本机实跑）

本卡结项，后文保留原任务要求。构建精确HEAD **df0eeac5dfd0c12739e698a6be7410c2bb8fd541**，已跟踪源码干净、仅四个不参与编译的本地报告未跟踪；零源码修改/提交/APK操作。云端未直接读取D盘原始日志。**不含后续7479c62的JNI**，不能把下面目标链接结果扩成JNI/Android worker已验证。

Windows11/Python3.13.5/NDK27.3.13750724(r27d)；真实windows-x86_64 clang(.exe)/clang++/llvm-readelf/llvm-nm，没有shell/cmd/PATH或C盘改动。源码按实际CRLF工作树字节复制独立快照，构建前后摘要未漂移。原SQLite归档SHA256 `ebf0ed75a7a59dbcb3b24bbd014ef49d9f15bc328e4adcbf516f2a8fadfa2835`，原36个defines全部保留，含OMIT_PROGRESS_CALLBACK/THREADSAFE=2/FTS5。C99/C++20、PIC/hidden/NDEBUG/O3、exceptions/RTTI，每ABI独立重编4个对象，Android26 targets固定为x86_64-linux-android26及aarch64-linux-android26。

| 候选库 | bytes | SHA256 |
|---|---:|---|
| x86_64 | 2038640 | bb197ba8f252c824c48a6b6594fc19164bc9cd3a976f56a6b2e7845ca9cde5b9 |
| arm64-v8a | 2036904 | e67d4d86a3a00080e3d6216dd1fef5430befe5c4cb86127ce5448029c09e3adf |

两库均ELF64/little/DYN，machine分别X86-64/AArch64，SONAME libmbot_sqlite_backup_candidate.so；DT_NEEDED仅libc++_shared.so/libc.so/libm.so/libdl.so，无libnode/宿主glibc。四个LOAD均0x4000对齐；Android ident为API26/NDKr27d/13750724。`--no-undefined`及`-Wl,--no-gc-sections`生效，未strip，普通符号表存在copySqliteBackup/verifySqliteBackup/runGrantedSqliteBackup三个核心符号（hidden导致本地t，非动态导出）。链接驱动须将lld的no-gc选项写为`-Wl,--no-gc-sections`，原卡所指的同一开关；extract剥package前缀，读取deps目录。未改Core/defines/检查凑过。

实际输入SHA256（CRLF仓库文件，不是Git LF blob）：

```text
3518a8ac05f79cf8bc2d3b95559c8e4d96fec991d5f827a7a94a60c8baaad2fc backup.cpp
6f162ac0a91abeecf42f92554ca21a4444972a012f4f76b45c6f6173d30063b3 backup.h
06c420976ffc38b07717abd204e7b46f1e655394562cae09d1470f3a605bad5e fd_backup.cpp
0cf282b2de53ce841b9c357d0ece5e3f77a7c26429b1a7656ec92b50c48e4c2c fd_backup.h
b0b071c0fd996b4febadd6dca63ccf3e8bf3650ff5afaae9208da70347bbc134 fd_vfs.cpp
eb52541daaa05a06f885e2281168a7c119d3b88b5152c1233f9c2ef467acdafa sqlite-runtime-pin.json
60d2f39a3726cd6b9021da6f4e868608d66fbb6528a9f513dc8ffcc640493422 sqlite3.c
9e69a1353a4288450b0d5239ede11fc7f1f4c8e5eb07491fc8317eacb5b7de7e sqlite3.h
029426d3d6528d56ea75682a67a49e48747ccb7439faa75107601d4e2cafabc3 defines.gypi
```

产物/编排只留D盘backup-cross临时目录；报告本机docs/research/flash-backup-cross-compile.md，不入库。**仅Android目标编译/链接通过**：JNI/Binder/worker UID/取消/死亡、发布/完整FS/runtime、真实模型、ARM64设备/16KiB设备/物理真机/产品APK均未验证。VFS仍为验证adapter，不是已装配的产品库。

2026-11-06。本机上一张 [FD metadata设备卡](T-Flash-FDMetadataProbe.md) 已交付；本卡只做Windows上的NDK编译/ELF核验，**零仓库源码改动、零提交、不构建/安装APK**。Codex继续JNI/可信worker/owner，不把构建杂项与核心同时派给你。

先读根及dsh-go的AGENTS、本卡和 [native backup README](../../dsh-go/runtime/native-sqlite/README.md)。仓库仅feat/t-plugins；先检查本地工作，再fetch/快进，至少含d05bd35。报告构建精确HEAD/源码状态，不reset/stash/覆盖、不动main/dev/integration、不force、不PR/发布。若编译出源码错误只回报，不能修Core/改检查来通过。

## 目标与来源

以已有Windows NDK27d（27.3.13750724）将同一份真实SQLite/备份核心/VFS分别编译为 Android26 的 **x86_64 与 arm64-v8a** 共享库候选，证明代码能被目标编译器接受并完整链接。**这不是JNI库、不是Android运行、更不是ARM64 Node或产品包支持。**

不依赖Node/V8/ICU/Flutter。只使用以下源码和头：

- `dsh-go/runtime/native-sqlite/backup.cpp` / `backup.h`
- `dsh-go/runtime/native-sqlite/fd_backup.cpp` / `fd_backup.h`
- `dsh-go/tooling/android-node-probe/src/main/cpp/fd_vfs.cpp`
- 原 `sqlite-runtime-pin.json` 钉住的better-sqlite3 12.11.1归档中 `deps/sqlite3/sqlite3.c`、`sqlite3.h` 和 `deps/defines.gypi`

沿用本机已核验归档（SHA256 ebf0ed75a7a59dbcb3b24bbd014ef49d9f15bc328e4adcbf516f2a8fadfa2835）。可只读import当前 `prepare-sqlite.py` 并调用它的extract，复用原摘要/安全解包验证；不调用其Node构建步骤，不改模块ROOT/PIN，不用已篡改源码。临时编排脚本可留D盘临时目录，不能提交。

开始时把上述仓库输入及pin按当前工作树字节复制到独立构建目录，并记录SHA256；从这份快照编译。头包含目录用快照和已验证SQLite源码目录。构建后复核输入未变，避免把并行更新混入一次产物。报告说明Windows CRLF的来源字节，不用Git LF摘要冒充实际输入。

## 编译要求

1. 实查NDK source.properties为27d。使用真实 `clang.exe`、`clang++.exe`、`llvm-readelf.exe`/`llvm-nm.exe`；不走.cmd、不用shell=True，不改系统PATH，不安装新全局工具，不写C盘临时文件。
2. 两个固定target：`--target=x86_64-linux-android26`、`--target=aarch64-linux-android26`。不要用宿主编译器、Linux/glibc预编译库或Node/V8 addon替代。
3. 每ABI单独重新编译SQLite C对象，传归档内defines.gypi的**全部原定义**（与现有prepare-sqlite.py同来源/校验方式），不删除SQLITE_OMIT_PROGRESS_CALLBACK等定义。C用C99，Core/VFS用C++20；优化/PIC/hidden visibility/NDEBUG，C++保留exception/RTTI支持。
4. 链接本ABI的sqlite3.o、backup.o、fd_backup.o、fd_vfs.o为 `libmbot_sqlite_backup_candidate.so`。使用 `-shared`、`--no-undefined`、16KiB max/common page size、明确SONAME；使用NDK libc++_shared及Android libc/libm/libdl。不要把Linux的-lpthread/-lrt或libnode链接进去。
5. 本阶段没有JNI导出入口，**禁止GC把核心删成空库**：明确 `--no-gc-sections`，不strip，保留普通符号表以核验 `mbot::copySqliteBackup`、`mbot::verifySqliteBackup`、`mbot::runGrantedSqliteBackup` 确实存在（用llvm-nm --demangle，不能只查动态符号表）。不要修改Core可见性来造JNI入口。
6. readelf核验：ELF64/little-endian/DYN，machine分别X86-64/AArch64；Android API26/NDK来源；SONAME及DT_NEEDED，没有libnode/宿主glibc依赖；所有LOAD segment对齐至少16KiB。候选库本身的对齐不代表已在16KiB设备验证。

链接失败时保留完整本地日志，只回报必要错误与实际命令，不带任何环境/凭据/签名URL；不得换旧库或删--no-undefined绕过。新库只留D盘本地临时目录或被忽略的build目录，不进仓库、不塞产品APK、不发布Release。

## 回报与边界

报告留本机 `docs/research/flash-backup-cross-compile.md`，不提交。回报HEAD/干净状态、实际工具版本、两个ABI的精确命令、输入摘要、实际编译/链接结果、库大小/SHA256、ELF machine/SONAME/依赖/LOAD对齐和核心符号核验。没有执行的写未验证；一个ABI失败不能用另一个成功代替。

即使两ABI通过，也只能叫**Android目标编译/链接通过**。本卡不验证JNI/Binder、worker真实UID/权限/取消/死亡、持久发布、完整runtime/FS、真实模型、ARM64设备或产品APK。VFS仍是验证adapter，产品native库的打包/装配由Codex完成。不要把本库当成此前已装的Node验收包，也不要为了演示加可执行入口。
