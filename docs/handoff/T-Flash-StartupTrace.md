<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 本机小卡：重包一次，定位真实启动停点

2026-11-11，feat/t-plugins。本卡**零源码改动、零提交**，只做一轮产品构建与指定设备启动。先读[HANDOFF](../HANDOFF.md)和[开发命令](../DEVELOPMENT.md)。不再重做候选/ARM64准入、FGS修复或继续延长180秒预算。

## 为什么必须新包

11-11有两类实际源码变化：

- `mobile-filesystem.mjs`：已确认持久清单与新清单字节相同时跳过重复Main发布；真实变更仍同步提交，UNKNOWN仍失败并毒化。需重建JS。
- bootstrap、host启动接线、`EmbeddedBotNode.kt`、`product_entry.inc`与共享`node_probe.cpp`：新增有限启动阶段日志，并把真实停止信号传给尚在启动的runtime。**既有JNI库不能改摘要后复用**，必须按准备器/产品闸门要求重编。Node本体、上游pin、SQLite源版本和备份核心没有变化，不需要重编Node。

## 开工及准备

1. 记录git status/branch/HEAD，保留本地报告。fetch后仅merge --ff-only origin/feat/t-plugins；不能快进或有不属于本卡的源码改动则报告现场，不reset/stash。构建时再记录精确HEAD和tracked状态。
2. 实查本机Flutter、SDK、NDK27.3.13750724、真实esbuild可执行文件、已授权full-ICU x64归档与SQLite钉住归档。参考DEVELOPMENT，不照抄不存在的云端路径。Key完全不需要，不读取/导出已有凭据。
3. 选择已有授权的x64 full-ICU归档，按原来源规则复核；依次重跑`prepare-probe.py`与`prepare-sqlite.py --abi x86_64`。共享node_probe.cpp变化会影响桥接输入记录，不能仅重建Flutter。`prepare-backup.py`仅在其真实输入/库检查要求时重跑；未变且闸门通过则可复用，不手改任何摘要或requires*字段。
4. 在repo外全新stage后缀重建partial/mobile，再`prepare-product.py --abi x86_64`。旧product目录按原流程改为独有stale目录，不覆盖未知文件。所有闸门必须通过。缺归档/工具或拒绝时记录第一个稳定错误就停，不擅自换无Intl发行库、别的ABI或放宽验证。
5. 从dsh-go/app执行`flutter build apk --debug --target-platform android-x64 --flavor x64`。记录APK路径/大小/SHA256，留本机；不入库、不上传/发布Release。确认新资产/native都进入APK、extractNativeLibs=false，无旧DSH rootfs。

## 设备：一次即可，不循环试到绿

只操作用户此前指定的Windows本机emulator-5554；先只读确认API35/x86_64和boot完成。若设备/授权不符，报告缺项，不触碰其它设备。install -r同应用ID `io.github.ccch1mneyyy.dsh_app.mbot`，不wipe/清数据/降级卸载，不重置供应商或Bot。

启动一次，至多观察完整180秒启动预算及有限清理窗口。保留同一启动的以下信息即可：

- 构建HEAD、tracked状态、APK SHA与指定设备/API/ABI；
- Main/隔离guest的实际UID/PID；
- `MiniMaxBotRuntime`固定标签中bot_fgs_requested、bot_fgs_foreground、bot_assets_manifest_verified、bot_isolated_peer_verified、bot_grants_sent、下表阶段以及终态bot_ready/有限失败码的时间；
- UI处于骨架、列表还是错误态；有无本包ANR/FATAL。原始大日志留本机，回报只摘固定阶段，不dump env、descriptor、prefs、请求体、整个logcat或用户数据。

**不因为超时就force-stop反复重开，不重跑58/23组件suite，不测试真实provider，不发送模型消息。** 本卡目的只是把启动卡点缩小到具体阶段。页面退出不等于runtime停止，不把按返回键写成取消测试。

## 阶段解释（guest进度不等于Main ready）

| 最后一条固定阶段 | 已走到哪里 / 下一步看哪里 |
|---|---|
| bot_grants_sent但没有bot_start_assets_loading | 隔离服务到Node VM/固定bootstrap之间；结合APK映射/隔离进程生命周期，不推测已进factory |
| bot_start_assets_loading | 开始读/校验APK中runtime、filesystem、resources及SQLite wrapper；下一条assets_loaded才表示此段完成 |
| bot_start_filesystem_loading | 虚拟文件系统安装及持久清单/内容恢复；后续filesystem_installed才表示返回 |
| bot_start_runtime_importing | 正在导入真实mobile JS大bundle，runtime_imported表示导入返回 |
| bot_start_runtime_factory_starting | 正在构造原mcode host，含其DB迁移/备份/内置资源初始化；不要把这里的等待直接归咎于provider |
| bot_start_runtime_factory_started | 原host已返回；native_catalog_starting/projected分别是目录投影开始/完成 |
| bot_start_bot_service_starting | 原官方Bot服务的构造/持久恢复；bot_service_started表示返回 |
| bot_start_ingress_started | guest已启动FD入口；**不是产品可用证明**，Main还须真正匿名403/授权200健康检查后才发布endpoint |
| bot_ready | Main已完成健康检查并发布本代endpoint；仍不证明真实模型、多模态或ARM64设备 |

全部阶段为允许列表，每UID单调、每项最多一次，停止后关闭；没有用户文本、端点、模型Key或异常原文。缺某条可能是尚未到达、进程退役或资产版本不符，不能只凭缺日志宣布根因。

## 回报与停止条件

成功：明确这一包实际bot_ready及耗时、UI列表、UID。失败/超时：给同一启动最后固定阶段、有限失败码和首次关键ANR/FATAL；保留日志本地路径，**到此停止并交回核心开发**。不要改Core、权限、隔离、超时、pin或断言来完成本卡。

报告只留本机docs/research，不提交。共享进度由主agent统一更新。本卡是API35/x64产品启动证据，不能替代用户指定ARM64真机的阶段C，也不证明新取消接线在设备上执行。
