<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-Persistence：明确授予FD的SQLite VFS原型（2026-11-03）

**仅宿主原型，未进入验收APK/生产runtime。** 已证明单个文件FD在隔离UID下的基本操作可用，但尚未证明Android上的SQLite WAL、共享内存、多连接或重启恢复。

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

宿主测试覆盖WAL提交、同时只读连接、回滚、关闭一个连接后另一个进程仍被writer lock挡住、关闭重开、独立进程提交后直接_exit（不close/checkpoint）的WAL恢复、实际integrity_check恰好一行ok、拒绝未授予路径且不创建目标文件。还验证无效注册不关闭caller FD。不是单纯“exec返回OK”。独立进程exec重置SQLite状态，不在fork后继续使用父进程连接。NDK部分只编译object，未链接进addon或运行Android。

已有设备前提：d7d65ad/run41683691783的API26/35基本FD读/写同步/截断/锁/RW映射与路径拒绝全过（T-SQLite）。**不能据此把本VFS的Android测试也写成通过。** CI为现有Node矩阵增加同一宿主/NDK object检查，不会自动把原型编入APK。

## 下一位必须处理

1. 审阅VFS的sidecar生命周期与FD别名生命周期，不能把原型直接切到产品。安装前已预检全部syscall，安装/注册失败会恢复原指针并关闭暂存FD；尚未做故障注入或Android执行。必须在所有SQLite连接创建前配置，不支持运行中替换registry。
2. Native只授予专用runtime-data目录中的数据文件；拒绝Key prefs/Keystore/模型endpoint与token。不得把整个filesDir/workspace或任意路径作为数据文件解析接口。虚拟文件名只是能力映射，不等于权限授权。
3. **关闭owner副本不能撤销已经dup的FD**。native lease应绑定可信身份/隔离进程生命期；撤销必须停止对应隔离进程。配置前关闭或移交隔离进程内的输入FD别名，之后不要在SQLite活跃期间随意关闭同inode别名，否则POSIX范围锁会被释放。
4. 先给验收target接受控native lease/FD handoff及SQLite addon初始化，再在API26/35验证真正WAL、多连接、干净重启、崩溃恢复/完整性、所有FD关闭与Key边界。目录fsync、映射实际写入、物理真机/ARM64也未验证。
5. 钉点DatabaseClient明确v1/v2双连接，并用独立只读连接验backup；完整online backup、临时文件、mkdirSync/dataDir、JSON/历史/插件FS仍未适配。不要以本原型替代这些验收。
6. full-ICU独立重跑41680254279仍在构建；没有Intl仍不能启动完整官方Bot。严禁退回同UID任意shell或宽存储权限。
