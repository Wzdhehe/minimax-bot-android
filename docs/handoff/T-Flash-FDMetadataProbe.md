<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# T-Flash-FDMetadataProbe：新 metadata API 的 API35 直接验收

2026-11-06，Codex 给用户本机 agent 的后续设备卡。先读根及dsh-go的AGENTS、[上一卡](T-Flash-SqliteWindows.md)和本卡。只在 `feat/t-plugins`；本卡**不改任何源码，不提交报告或APK**，仅准备/构建/设备验证。Codex继续native备份owner/IPC，双方不等待旧DSH rootfs。

## 为什么重跑

上一包构建在d68e48f，8/58项真实设备结果有效，但不含02fb530的 `fdVfsMetadata`。本卡对应的新Node脚本会直接调用该API，仪器断言 `persistent.metadata` 的4个布尔值和6次非法输入拒绝；不能再拿旧包跑同名测试代替。新断言本轮由Codex写，不要求你实现或放宽。

## 同步与产物来源

1. 先检查工作树/未推提交。干净时fetch后快进到远端最新；有本地工作先回报，不reset/stash/覆盖。记录**构建时精确HEAD及工作树状态**，不要用事后报告的HEAD代替。
2. 确认当前源码包含：`node-fd-probe.cjs` 调用 `fdVfsMetadata` 且填入 `persistent.metadata`；`IsolatedNodeModelTest.kt` 在requiresFdVfs时断言该对象；`fd_vfs_node.cpp` 真正导出此API。不满足则是没有同步到本卡实现，先回报。
3. 可以复用上一卡已授权的full-ICU来源和本地缓存：producer41826964330、内层node-full-icu.zip SHA256 **cd8823cf323e0c7216902a9a2dfb4a50542a99f85ffe21e4a2fa99e3436e9747**。先确认producer五个权威输入仍与授权版本一致，保留原校验；如需重跑verify-source-run，沿用上次原blob导出树解决CRLF的方法，不改验证器/权威输入，不关闭TLS，不打印凭据或带签名URL。
4. **重新执行**base Node准备（full-ICU zip+实际SHA），再执行SQLite准备，使用已有NDK27d和真正的0.28.2 esbuild.exe。不能直接复用上包的libmbot_sqlite_probe.so，也不能手改生成脚本。确认requiresIntl/requiresSqlite/requiresFdVfs全true，准备失败不得改标志位凑通过。
5. 保存并核对 `build/verified-assets/third_party/better-sqlite3/build-inputs.json`：adapter摘要应与此次实际编译的工作树字节一致（注意Windows CRLF与Git blob LF的差异），不是d68的旧摘要。报告可给摘要，不要给整份运行报告/环境。

## 构建与设备

沿用本机已通过的JDK17/Gradle9.3.1/SDK/NDK27d，不改Java target、依赖或编译配置：

```text
gradle --no-daemon --max-workers=2 -p dsh-go/tooling/android-node-probe assembleDebug assembleDebugAndroidTest -Pkotlin.incremental=false
```

只操作 `adb -s emulator-5554`，先核实API35/x86_64/boot完成。安装本次两个新APK；不清数据、不wipe、不卸载其他App、不修AVD、不碰其他设备。若同包签名不匹配需要卸载，先回报。使用pm list instrumentation的实际runner。

先跑上次三类：BotSocketConnectionTest、IsolatedNodeModelTest、RuntimeCoordinatorProbeTest；通过后再跑独立验收包完整suite。新源码不增加@Test数量，因此总数可能仍8/58，**数量本身不能证明新metadata被调用**。编译/执行失败保留本地日志回报，不自行改生产或测试断言。

新增且必须实际通过的断言：

- `directoryPrivate`：slot4为私有虚拟namespace（不是物理目录权限证明）。
- `filesPrivate`：slot0..3实际FD mode为0600普通文件。
- `invalidRefused == 6`：空参数、负slot、越界、字符串路径、非整数和多参数均得到稳定拒绝。
- `walLive`：两个独立Node进程都进行一次实际WAL写入后，native metadata报告存在、size>0、私有mode。
- `walRetired`：关闭最后数据库连接后metadata报告WAL为逻辑不存在且size=0。

Node脚本会对旧addon（没有该API）直接失败，不允许skip/fallback。现有真实官方Bot、记忆/恢复、SQLite、Key、UID、桥、coordinator断言都必须保留并通过。失败诊断只应有有限phase/code；新值为 `vfs_metadata` / `fd_vfs_metadata_unavailable`，不能把原始报告/私有路径/凭据贴到公开日志。

## 回报

报告留本机 `docs/research/flash-fd-metadata-probe.md`，不提交。回报构建HEAD、源码是否干净、Node/SQLite metadata三个flag、实际工具版本、编译命令和原始终态、实际runner/设备、定向/全套统计、APK本地路径/大小/SHA256、此次adapter摘要和未验证项。必须明确 `persistent.metadata` 新断言在requiresFdVfs=true分支执行；无需也不得上传完整JSON、env或任何Key。

这是API35/x86_64独立验证包。它只验证FD metadata API及旧组件回归，**不证明1d2f0af的整个mcode FS shim/factory在Android启动，也不证明native备份kernel/owner/IPC/发布、完整runtime、真实provider、ARM64/真机或产品APK**。本卡没有任何需要真实用户Key的步骤。源码若需修复由Codex处理，验收完等待新卡。
