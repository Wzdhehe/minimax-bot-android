<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-Persistence：预创建数据FD的SQLite VFS（2026-11-03）

**当前是独立验收候选，不是正式产品runtime。新4文件FD候选尚未通过Android验证；不要把宿主成功写成Android成功。** 默认App仍DSH，没有完整mcode/官方Bot APK或物理真机/ARM64证据。

## 已拿到的实际失败位置

6605508 / run **41700450899** 的两份artifact元数据：

```text
API26: node-candidate-results-api-26-t1-f1-e0-s0-ipc-l252
API35: node-candidate-results-api-35-t1-f1-e0-s0-ipc-l252
```

Check annotations也确认 `kind=ipc`、`IsolatedNodeModelTest.kt:252`（以及调用行126）。252是ProbeConnection.run的Messenger.send：**Main发送含目录FD的Bundle时失败，Node/SQLite尚未执行**。这不是已证实的WAL/SQLite报错。没有读取到SELinux原始拒绝，不能声称已拿到具体policy原因。

此前 b2a32b5/41693324311、e983424/41695986670、56390d7/41698083995、ee40005/41699206972 均API26/35失败。旧报告器没有给出可读取阶段；本环境下载Azure-backed日志/XML请求被拒绝，不能输出签名URL或猜异常。6605508才加入有限状态artifact name，突破读取限制。真实metadata ID：API26 `13263417778`、API35 `13262709762`。

既有前提仍成立：d7d65ad/run41683691783的明确授予**普通文件**FD读/写同步/截断/锁/RW映射和按私有路径open拒绝已API26/35通过；bc07b1c/run41663798958的隔离Node内存SQLite/模型/Key边界也通过。不能将普通文件FD的证据推广为目录FD可传递。

## 本阶段实现：目录始终留在Main

- Main预创建DB/WAL/SHM/journal四个专用非敏感文件，fsync四文件及其所在目录；只向隔离端传这**四个普通文件FD**，不传目录FD、真实数据路径、Key文件或任意目录解析接口。
- Native/JNI/Node binding统一四数据FD。`mbot_register_precreated_fd_vfs` 明确要求可信owner已同步创建目录项，且整个lease期间不unlink/rename/替换这些物理inode。一个进程一次不可变注册；最多16个普通文件，Node验收binding固定四个。
- SQLite仍使用原unix VFS的锁、inode、延迟close和SHM实现。公开xSetSystemCall拦截open/stat/lstat/access/unlink/openDirectory，xFullPathname只允许授予的虚拟basename，虚拟根/mbot-data不对应物理目录。未知路径/穿越拒绝，仅保留固定只读/dev/urandom熵源。
- 运行期目录成员不变：sidecar存在与否来自实际文件大小，删除用truncate(0)模拟。**必须fsync该数据FD**才能报告逻辑删除成功；目录fsync不能代替文件长度持久化。xDelete只执行受控逻辑删除，原unixSync在完成文件同步后按其公开约定接受openDirectory的CANTOPEN；本lease没有需要隔离端同步的新目录项。
- 文件sync的EINTR重试，EIO返回SQLite删除错误。此同步是代码契约修复，**没有物理断电验证**。截断不等于完整POSIX unlink/重建语义，不能据此切产品。
- setFdVfs后、任何文件DB锁产生前关闭隔离进程内输入别名；registry拥有dup。SQLite活跃时关闭同inode任意别名会释放进程POSIX锁。Main只持owner副本，不在Main开SQLite连接。

prepare-sqlite仍从摘要钉死的better-sqlite3 12.11.1 tar提取，唯一NODE_MODULE_INIT入口插入独立AGPL binding调用，原tar/vendor不改；adapter/修改后addon/headers摘要入build-inputs。MIT、SQLite public domain及adapter AGPL声明随验收assets保留，不提交二进制。

## 验证与边界

实际执行：

```bash
python3 dsh-go/tooling/android-node-probe/test-fd-vfs.py \
  --archive /tmp/mbot-sqlite-reference/better-sqlite3-12.11.1.tgz \
  --ndk /tmp/mbot-ndk27d --node-probe
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_*.py' -v
node --test dsh-go/tooling/android-node-probe/probe-bundle.test.mjs
```

新4FD候选宿主输出：

```text
PASS: FD VFS WAL, multiple connections, external writer lock, external reader snapshot/checkpoint contention, delete sync failure/retry, rollback, reopen, crash recovery, integrity and ungranted-path refusal
PASS: exact APK SQLite block, addon and FD VFS preserve data across independent host Node processes; not Android evidence
PASS: Android API26/x86_64 VFS object compilation only; not Android execution
```

C++用同源SQLite和defines.gypi，覆盖独立writer锁、关闭一个连接不能丢锁、另一个只读进程持快照时写入/关闭且checkpoint必须BUSY、读者快照保持、随后新连接见提交、独立进程提交后直接_exit不checkpoint的WAL恢复，以及integrity_check恰好一行ok、未授予路径不创建、拒绝目录冒充普通文件。所有子进程exec重置SQLite状态；父fork前无连接。owner同步创建，目录设CLOEXEC，不给子进程目录能力。

只在宿主测试可执行文件中用GNU ld --wrap=fsync注入EIO/EINTR，验证删除错误传递/重试；wrapper不进入Android或Node addon。Node host检查使用同一instrument_addon、同源wrapper/准备后的headers及APK源码中精确SQLite块，两个新PID共享四普通文件FD，第二个必须读回原数据。不是重新造数据假装恢复。

Python检查19项通过（source/archive/ABI拒绝、有限XML/metadata不泄露、source artifact authority）；固定模型脚本1项通过。新四FD的JNI/SQLite重编及两个APK构建成功，原始输出：

```text
BUILD SUCCESSFUL in 1m 27s
69 actionable tasks: 15 executed, 54 up-to-date
```

此前22s构建是仍传目录FD的同步修复版本，以本次新四FD构建为准。

Android固定用例计划完整保留模型/SDK/84KB/取消/Key边界，额外WAL/并行只读/重开/integrity及两次不同PID启动；每次unbind等待service Binder死亡。**新四FD的Android执行尚未验证。**

## 有限诊断与接手顺序

新run先读metadata，不依赖被拒绝的日志下载：

```bash
probe_run=41700450899
env -u GH_TOKEN -u GITHUB_TOKEN gh api \
  "repos/Wzdhehe/minimax-bot-android/actions/runs/${probe_run}/artifacts" \
  --jq '.artifacts[]|{id,name}'
```

report-results.py兼容属性、正文、namespace、suite级failure/嵌套堆栈。只输出白名单阶段/code/异常类别和数字测试行号，以及计数；同一有限result_id进入GITHUB_OUTPUT和artifact name。没有异常原文/env/响应/平台诊断。未知值归一化，不通过通配符将任意error.code输出。XML仍是七天私有测试artifact。Keystore随机测试Key的断言不包含原始报告作为失败信息。

1. 观察新四FD run，先确认越过Main IPC；若失败，按真实phase/类别/行号修复，不自动退回同UID、宽存储或内存周期快照。
2. 真正native runtime-data lease尚未实现。只允许专用数据文件，不给Key prefs/Keystore/整个filesDir/workspace；cwd不是权限。owner关闭副本不能撤销dup，撤销需终止隔离执行单元；正式launcher须验证若存在子进程也一起退出。当前仅等service Binder死亡，不证明fork/FD转交场景撤销。
3. 固定mcode DatabaseClient明确v1/v2双连接，并用独立只读连接验backup。online backup、临时文件、mkdirSync/dataDir、JSON/历史/插件FS与物理inode替换仍缺，不能将本VFS当完整FS桥。
4. Android设备上的进程崩溃恢复、所有FD关闭、物理真机/ARM64/16KiB页面、断电/存储满尚未验证。宿主crash恢复不能代替它们。
5. full-ICU producer41680254279仍未完成，默认候选无Intl，完整官方Bot仍不能启动。ee40005已支持经过同repo/branch/精确build inputs验证后的私有产物复用，下载/组合设备路径未验证；见T-AndroidNode。
