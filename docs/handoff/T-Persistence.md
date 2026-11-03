<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-Persistence：明确授予FD的SQLite VFS原型（2026-11-03）

**当前：验收APK已接入并编译，但首轮Android执行失败；产品runtime未接。**

## 续写：跨进程验收与XML诊断

第二轮 e983424/run41695986670 的 API26/35 也失败，没有取得有限阶段码。下载日志和XML均被本环境的下载请求拒绝；没有读到原始异常，不能推断SELinux/SQLite的具体原因。发现提取器只读failure.message，未覆盖Android把堆栈放在XML正文的形式，现改为独立report-results.py，合并正文/属性私下匹配，仅输出固定phase/code及本测试行号；未知文本、env/响应/异常原文一律不输出。4项回归覆盖正文/属性、未知/敏感文本不输出、无结果拒绝、skipped拒绝；全部Python构建/报告检查共12项通过。

固定脚本现在可重入同一数据：首次创建并写两条；第二个新进程必须读回原内容，不能靠重新造两条假装恢复。Android用例重复两次、unbind后等待旧Binder死亡，断言PID不同；每次继续检查Keystore/模型/流/取消/Key边界。两个APK本地离线构建实际输出：

```text
BUILD SUCCESSFUL in 40s
69 actionable tasks: 9 executed, 60 up-to-date
```

宿主新增可重复执行的 `--node-probe`：从同一摘要验证的tar编译真正Node addon（复用prepare-sqlite的唯一入口插入函数），用准备好的同源wrapper/Node headers运行APK源码中精确SQLite块；两个独立进程共享Main样式owner持有的五FD。只作宿主ABI/持久化证据，不是Android隔离。

```bash
python3 dsh-go/tooling/android-node-probe/test-fd-vfs.py \
  --archive /tmp/mbot-sqlite-reference/better-sqlite3-12.11.1.tgz \
  --ndk /tmp/mbot-ndk27d --node-probe
```

实际输出：

```text
PASS: FD VFS WAL, multiple connections, external writer lock, rollback, reopen, crash recovery, integrity and ungranted-path refusal
PASS: exact APK SQLite block, addon and FD VFS preserve data across independent host Node processes; not Android evidence
PASS: Android API26/x86_64 VFS object compilation only; not Android execution
```

host固定模型脚本1项、scope4项也通过。**本版Android跨进程执行未验证；先看新CI的有限阶段码/行号修复首个失败，不切产品**。目录同步、真实lease/backup/完整FS与物理真机/ARM64仍未验证。

## 当前Android候选

SQLite addon增加独立AGPL fd_vfs_node binding；prepare-sqlite从固定tar提取后，仅按唯一NODE_MODULE_INIT入口插入注册调用，再链接适配器。原tar不改，改后addon/两个adapter摘要入build-inputs，MIT/SQLite声明及适配器AGPL许可证都进验收assets。没有改vendor快照或提交二进制。

Main只创建临时非敏感DB/WAL/SHM/journal和专用目录，明确授予五个FD；JNI只传整数数组，不给隔离端物理路径。setFdVfs成功后先关闭输入别名，再创建任何文件DB连接。固定脚本配置WAL、验证同时只读连接、写事务、关闭重开与integrity_check；owner检查实际文件已写入且不含随机测试Key。原模型/SDK/大请求/内存SQLite/Key断言保留。

本地实际准备：JNI和带VFS的SQLite addon编译成功；host1/input拒绝8通过。最后两个APK：

```text
BUILD SUCCESSFUL in 3m 42s
69 actionable tasks: 5 executed, 64 up-to-date
```

首次因AGPL文本路径定位到不存在的仓库根LICENSE而拒绝，改为实际dsh-go/LICENSE；随后仪器源码用了SDK不公开的O_DIRECTORY而编译失败，改为打开已知临时目录并fstat验证目录类型。没有补隐藏API、宽权限或绕过隔离。

**首轮 b2a32b5/run41693324311 的 API26/35 均失败**，实际API查询确认。日志下载在本环境被403拒绝，暂只有失败步骤/退出码，尚未知道具体阶段，不能声称已定位原因。新增私有结果FD上的固定阶段/错误码，由Main白名单复核后生成有限JUnit断言，再由CI解析为固定注解；不打印异常原文、env、Node报告或响应。诊断版脚本host1及输入拒绝8通过；两个APK离线构建实际输出：`BUILD SUCCESSFUL in 41s`、`69 actionable tasks: 8 executed, 61 up-to-date`。

**Android VFS/WAL尚未通过**。同一Node内关闭重开不是跨进程重启；崩溃恢复仍只有下文宿主证据。真实runtime-data lease、完整mcode的mkdir/JSON/history/backup FS、多进程生命周期、物理真机/ARM64未做。不能称正式持久化已经完成。

## 已实现的原型

`tooling/android-node-probe/src/main/cpp/fd_vfs.cpp` 复用固定SQLite的unix VFS，以其公开xSetSystemCall/xFullPathname扩展将白名单虚拟文件名映射到明确授予的FD。保留原锁、inode引用、延迟close和SHM实现，不自行简化成无锁VFS。注册最多16个普通文件和一个仅用于目录同步的目录FD；虚拟根固定/mbot-data，未知路径/穿越拒绝，唯独保留固定只读/dev/urandom以供SQLite熵源。没有任意路径fallback、shell、网络、Key或权限接口。

测试用Main DB/WAL/SHM/journal四个FD。sidecar创建/删除目前用已持有FD的大小与truncate模拟；每次看实际size，不能使用进程本地exists缓存，否则另一进程提交WAL后可能被漏读。**此模拟不是所有POSIX unlink/重建语义的完整替代**；backup目标、替换/重建、临时文件、目录与FS桥、native lease/JNI API均未实现。当前测试设置temp_store=MEMORY；正式方案不能默默改变或忽略上游FS需求。

## 实际验证

用校验摘要后的better-sqlite3 12.11.1内置SQLite源码和同一defines.gypi，从源码编译独立宿主测试：

```bash
python3 dsh-go/tooling/android-node-probe/test-fd-vfs.py \
  --archive /tmp/mbot-sqlite-reference/better-sqlite3-12.11.1.tgz \
  --ndk /tmp/mbot-ndk27d
```

原始输出：

```text
PASS: FD VFS WAL, multiple connections, external writer lock, rollback, reopen, crash recovery, integrity and ungranted-path refusal
PASS: Android API26/x86_64 VFS object compilation only; not Android execution
```

宿主测试覆盖WAL提交、同时只读连接、回滚、关闭一个连接后另一个进程仍被writer lock挡住、关闭重开、独立进程提交后直接_exit（不close/checkpoint）的WAL恢复、实际integrity_check恰好一行ok、拒绝未授予路径且不创建目标文件。还验证无效注册不关闭caller FD。不是单纯“exec返回OK”。独立进程exec重置SQLite状态，不在fork后继续使用父进程连接。当时NDK部分只编译object；此后已链接进验收addon并编译APK，Android执行首轮失败，待定位。

已有设备前提：d7d65ad/run41683691783的API26/35基本FD读/写同步/截断/锁/RW映射与路径拒绝全过（T-SQLite）。**不能据此把本VFS的Android测试也写成通过。** 5428ac7当轮CI仅增加宿主/NDK object检查；b2a32b5才实际编入APK且首次设备执行失败。

## 下一位必须处理

1. 审阅VFS的sidecar生命周期与FD别名生命周期，不能把原型直接切到产品。安装前已预检全部syscall，安装/注册失败会恢复原指针并关闭暂存FD；尚未做故障注入；Android执行首次失败，不能跳过。必须在没有活跃连接时、任何文件数据库连接创建前配置，不支持运行中替换registry。
2. Native只授予专用runtime-data目录中的数据文件；拒绝Key prefs/Keystore/模型endpoint与token。不得把整个filesDir/workspace或任意路径作为数据文件解析接口。虚拟文件名只是能力映射，不等于权限授权。
3. **关闭owner副本不能撤销已经dup的FD**。native lease应绑定可信身份/隔离进程生命期；撤销必须停止对应隔离进程。配置前关闭或移交隔离进程内的输入FD别名，之后不要在SQLite活跃期间随意关闭同inode别名，否则POSIX范围锁会被释放。
4. 临时验收FD handoff/addon初始化已接，先用有限阶段码定位API26/35失败；再接真正runtime-data lease，验证跨进程干净重启、崩溃恢复/完整性、所有FD关闭与Key边界。目录fsync、映射实际写入、物理真机/ARM64也未验证。
5. 钉点DatabaseClient明确v1/v2双连接，并用独立只读连接验backup；完整online backup、临时文件、mkdirSync/dataDir、JSON/历史/插件FS仍未适配。不要以本原型替代这些验收。
6. full-ICU独立重跑41680254279仍在构建；没有Intl仍不能启动完整官方Bot。严禁退回同UID任意shell或宽存储权限。
