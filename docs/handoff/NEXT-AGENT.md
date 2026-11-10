<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 下一位 agent 的开发合同

更新：2026-11-10。这是当前接手顺序，旧专题卡的“下一步”不覆盖本文件。历史详见[整理前版本](https://github.com/Wzdhehe/minimax-bot-android/blob/e9d0bebd5fc2487b87d3b5a77b32c2107a0fe54e/docs/handoff/NEXT-AGENT.md)。用户因GPT即将到期要求详细交接，不应再让用户重新讲一遍架构。可直接转发给Ubuntu agent的编译卡、逐步命令与失败处理见[ARM64执行手册](ARM64-RUNBOOK.md)。

## 先做什么

1. 阅读根AGENTS.md、dsh-go/AGENTS.md、[HANDOFF](../HANDOFF.md)、本文件、[DEVELOPMENT](../DEVELOPMENT.md)、[sandbox](../sandbox.md)、[架构](../architecture.md)、[runtime pin](../UPSTREAM-RUNTIME.md)。改哪个子树再读其适用指令。
2. 仓库 Wzdhehe/minimax-bot-android，**继续feat/t-plugins，不另起分支**。先git status、fetch、merge --ff-only；有他人改动或不能快进时保留现场，不reset/stash/force覆盖。后续文档提交的HEAD会在e9d0beb之后，正常；已验产品源码仍是e9d0beb。
3. [当前事实](../HANDOFF.md)：官方Bot产品已经在API35/x86_64跑过真实MiniMax文本、生成中取消及再次对话。**不要重新实现launcher、SQLite迁移备份、Flutter换芯、连接测试或目录500修复。** 这些不再是“尚未开始”。
4. 读完就按下方ARM64主线动手；本机可并行补流式观察。每个可审阅阶段跑相关检查、明确暂存、commit并正常push，再继续下一阶段。用户已授权阶段性推送，不需要每次重问。

## 不可变约束

- main、dev/integration保持不变；不force、不重写共享历史、不PR、不发布APK/Release，除非用户之后明确改变授权。旧架构卡“每卡一PR”已过时。
- 产品是本机Bot，非传统通用agent工作台；不要恢复DSH首页、PRoot/Ubuntu安装引导、远控、手机操控或同Main UID任意shell。MiniApp不是插件，暂不适配。
- Key/真实provider URL只在native；Key绝不进Git、文档、日志、命令参数、环境、Agent可读文件、诊断包或截图。不得读其它App/电脑agent的凭据。已有模拟器Key由用户维护，不向下一位复制。
- 不加MANAGE_EXTERNAL_STORAGE/宽FileProvider，不关闭鉴权、UID/FD校验、快照复验、来源/pin/摘要闸门，不把false标志手改true。SDK失败不许网络fetch兜底或自动重提结果未知请求。
- 新文件AGPL-3.0头；保留官方Bot/MIT素材等原许可。不改vendor业务快照或上游checkout；优先在本地适配层接线。上游pin不随手升至npm最新版本。
- 内置Bot已有可信自动allowOnce，问题提问仍需回答；不要新增通用agent权限档位，也不能把自动批准解释成关闭native/UID/文件隔离。真实工具回合仍需单独验证。
- 源码有页面、宿主单测、Android编译、组件设备测试、产品设备真实模型是不同证据层。任何未跑环境写未验证，不写“应该通过”。

## 第一主线：ARM64产品，按阶段完成

### A. 独立 full-ICU ARM64 producer

**现状（2026-11-10）**：producer与candidateOnly=true的pin已在库；独立CI/来源核验已有。20项ARM64源码/来源、原x64来源5项通过。**手工full-ICU候选已编出并复核**（见下方实际），不是CI产物；GitHub ARM64作业仍可能因账户计费未启动，不必为补候选反复排队。阶段B产品准入已接通，勿再把“A尚未编出”或“prepare-product仍拒绝”当成当前事实。

- 先读 [producer](../../dsh-go/tooling/android-node-probe/build-arm64-source.py)、[ARM64 pin](../../dsh-go/tooling/android-node-probe/node-arm64-source-build.json)、共享[原producer](../../dsh-go/tooling/android-node-probe/build-source.py)及其宿主测试。
- 编译宿主须Linux x86_64 + NDK27.3.13750724；Windows本机无WSL，不能替它声称已编。可用用户另一个Ubuntu云端agent承担长编译，先明确同分支/文件分工；不能假定它已经拿到产物。
- 按脚本preflight检查干净的RockinChaos/nodejs-mobile checkout及其node子模块commit/tree；准确值读pin，不凭版本名下载任意包。先--check-only，再真实编译到新输出目录。
- 记录精确Git HEAD、tracked状态、producer recipe/input SHA、源commit/tree、NDK/API/ABI、full-ICU配置、libnode ELF/依赖/对齐、归档SHA256和真实编译退出码。编译通过不等于设备通过。
- x86_64原成功producer与其五个权威输入不能为方便ARM64随手改坏；ARM64有自己的产物身份和授权记录。日志/产物留本机或授权制品通道，不提交二进制、不泄露签名下载URL。

**阶段A验收**：真实Android/Bionic AArch64 full-ICU库及可核验来源记录存在；缺工具/资源则把确切阻塞写回，不伪造producer成功或跳来源验证。

**2026-11-10实际：A已完成（候选层）。** 云端Ubuntu手工按手册A编出（producer HEAD 40c922a冻结，EXIT0，2026-11-08 08:21 Asia/Shanghai结束），归档留本机/workspace/scratch，未入库。复核者另行实查：内层归档43,376,124字节/SHA256 `424bee7cf1e9e2cae702fbb797a6a21aeb1ef0a90ab16fe78441a931eb7460ec`；libnode `d46110d0…0a93`为ELF64 AArch64 DYN、Android ident API26/NDK r27d、4个LOAD均0x4000、NEEDED仅libm/libdl/liblog/libc++_shared/libc；config.gypi OS=android/target_arch=arm64/full-ICU；用原build.py/versions.py重算modified recipe/versions摘要一致；补丁后Node index树write-tree=pin nodeTree；四个producer输入与HEAD blob一致。结论只写入[审阅记录](../../dsh-go/tooling/android-node-probe/node-arm64-candidate.json)（admittedForProduct=false），**没有Actions run ID，不是设备证据**。

### B. ABI准入、SQLite addon与产品装配

主要修改面：`tooling/android-node-probe/{prepare-probe.py,prepare-sqlite.py,对应测试}`、`tooling/mobile-runtime-bundle/prepare-product.py`及必要的明确ABI来源记录/Gradle装配。这里的tooling前缀均是dsh-go/tooling。

1. `prepare-sqlite.py`已支持明确的x86_64/arm64-v8a→Android26 triple允许表，并检查headers的Android/target_arch与输入、输出ELF；Windows仍用clang.exe而不是.cmd/shell=True。x64原路径不变，ARM64独立放`android-node-probe/build/abi/arm64-v8a/`，26项SQLite宿主/输入用例通过。**这是mock编译命令与拒绝用例，不是已编出ARM64 addon。** `prepare-probe.py`仍固定x64、未改。**2026-11-10已补ARM64消费者**`prepare-arm64-node.py`：只信已提交的审阅记录（工作树须等于HEAD），无任何传SHA参数；再对照producer四输入现值与祖先commit、归档大小/SHA、安全解包（白名单成员、重复/大小写别名/越界/反斜杠/链接/加密/压缩比/预算）、mbotSourceBuild与mbotArm64Producer精确相等、唯一arm64 slice/ABI137/ICU版本、头config与node_version、纯Python ELF（AArch64 DYN、API26 note、16KiB LOAD、NEEDED白名单；NDK预编libc++_shared允许API≤26），只写build/abi/arm64-v8a（先.partial再原子改名，已存在拒绝，x64位置拒绝）。随后`prepare-sqlite.py --abi arm64-v8a`以真实NDK编出addon。
2. Node24.22.2 full-ICU产物、对应headers/config.gypi、ABI、NDK、source recipe和实际归档摘要必须互相匹配；ARM64的mbotArm64Producer不能冒充x64的mbotSourceBuild。当前verify-source-run.py只授权旧x64工作流，ARM64来源验证需要独立设计/实查，不能传x64 SHA凑通过。
3. SQLite仍用12.11.1钉住归档和全部36条defines.gypi定义，fd_vfs/fd_vfs_node用目标ABI重编；backup准备已支持两ABI，但其输入变动仍须重编。**Linux ARM64/glibc的npm预编译addon不能当Android/Bionic库使用**，Node ABI相同也不够。
4. **2026-11-10：B2已接通ARM64产品分支（76f67a0）。** `admitted_native_arm64`只信已提交审阅记录与`build/abi/arm64-v8a`准备输入；requiresIntl/Sqlite/FdVfs须为true且admittedForProduct仍为false（由产品闸门而非消费者自准）；再核recipe/头/ELF（AArch64、API26、16KiB LOAD、依赖白名单）、SQLite/backup一致性。正反例覆盖混入x64库、陈旧/篡改输入、缺Intl/SQLite/FD元数据。x64仍走原`admitted_native`，路径与闸门不变。不得降低验证或把标志手改true。
5. App已有arm64 flavor不代表引擎已支持。保持useLegacyPackaging=false / extractNativeLibs=false：独立UID从APK未压缩库映射。不能为旧PRoot库改回true。所有目标库检查Android/API26、AArch64、依赖和16KiB LOAD；静态对齐不等于16KiB页设备运行。

**阶段B验收**：x64/ARM64各自prepare-product及Gradle闸门通过；篡改ABI/头/recipe/digest、缺Intl/SQLite/FD元数据、混入宿主库和陈旧输入必须失败。ARM64 APK可构建且确实含对应引擎/JS/许可；仍不称手机可用。

**2026-11-10实际：B已完成（产品准入与调试APK，非设备）。** prepare-backup.py对x86_64与arm64-v8a均EXIT0。prepare-product `--abi arm64-v8a`与`--verify-only`均EXIT0；真实拒绝：混入x64库、误传x64归档SHA、缺FD元数据、陈旧头文件。`test_product*.py` 35项OK（原x64 7项未改）；android-node-probe 149项OK（6项既有skip）。ARM64调试APK（本机，未入库）295,445,843字节，SHA256 `6331421c307d1c4a592072d49ef8e1279c56bbd26bb1ee2ce54eb0bb48f8622f`：仅arm64-v8a，26项product-inputs全部命中（引擎/JS/许可），全部`.so` stored且APK内16KiB对齐，extractNativeLibs=false，无MANAGE_EXTERNAL_STORAGE，readelf均为AArch64。重编x64调试APK 290,520,571字节，SHA256 `9e37a9ee4e1598bffb095da80509244240117c22384fca01f2e6dda21715d7d5`；其lib/与assets/mbot/与498a151基线APK逐项摘要相同（整包SHA因prepare-product.py自身进入源清单而不同）。API35/x86_64软解模拟器（无KVM）安装该x64包Success；设备时钟17:39:16 FGS Allowed，17:40:21 ForegroundServiceDidNotStartInTimeException，随后bot_assets_manifest_verified，17:41:01主进程ANR，17:41:18 IsolatedBotService启动超时ANR后被杀；**未见bot_ready**。这与此前软解模拟器FGS超时同类，**不写成启动回归通过，也不写成产品回归失败**，更不能当ARM64设备证据。

**2026-11-10实际：阶段D（FGS启动顺序）已合入。** 根因是产品问题，不是“只是模拟器慢”：`BotRuntimePlugin.onAttachedToEngine` 在 `MainActivity.provideFlutterEngine` 的主线程里同步 `startForegroundService()`，系统 FGS 计时立即开始，但 `BotRuntimeService.onStartCommand`/`startForeground` 必须等主线程跑完 FlutterLoader、GeneratedPluginRegistrant 和 Dart entrypoint 才执行；基线 76f67a0 包在软解模拟器上从 18:44:20 FGS Allowed 到 18:45:31 ANR / 18:46:03 `ForegroundServiceDidNotStartInTimeException`。修复把真正的 `startForegroundService` 推迟到主 Looper `IdleHandler`（`ForegroundStartGate`，纯 Kotlin，可宿主测试）；`onStartCommand` 仍第一句 `startForeground`，随后打 `bot_fgs_foreground`。隔离红线未动：未改 `AndroidManifest.xml` / `IsolatedBotService.kt`，Node 不回主进程或同 UID。宿主单测 `testX64DebugUnitTest`（ForegroundStartGateTest + Runtime* + BotRuntime*）78 项全过，0 failure/error/skip。本机 x64 调试 APK（未入库）290,520,483 字节，SHA256 `bfba461bce12de62fcad7ea7ba12fe79e6e637561fe20ebd638f4298ccdf0543`。emulator-5554 `install -r -t` Success；设备时钟 19:18:01.338 `bot_fgs_requested`，19:18:03.145 `bot_fgs_foreground`，无 FGS 超时、无本包 ANR、无 FATAL；19:18:05 IsolatedBotService 以 `u0i2` 启动，19:18:14 `bot_isolated_peer_verified`，19:18:16 `bot_grants_sent`，19:18:20 隔离进程加载 `libmbot_node_runtime.so`，19:19:27 备份 worker `u0i3`；19:19:33 `bot_runtime_start_timeout` 后隔离进程因 isolated not needed 被杀。**当时未见 bot_ready**（90 秒预算）。已另开启动预算卡：见下方 180 秒实测。

**2026-11-10实际：启动预算 90→180 秒已合入，本轮软解模拟器仍未见 bot_ready。** 90 秒计时点在 `BotRuntimeExecution.start()`（`deadlines.schedule(..., 90, SECONDS)`，由 onStartCommand 在 `bot_fgs_foreground` 时启动）以及 `BotRuntimeManager.descriptorWhenSettled` 的 `90_000_000_000L`。隔离进程并非卡死：上一轮 19:18:14 已 `bot_isolated_peer_verified`，19:19:27 备份 worker 刚拉起即被 90 秒预算杀掉。现抽 `BotRuntimeStartBudget`（纯 Kotlin），`START_TIMEOUT_SECONDS=180`，UI 等待与之对齐；计时仍从 start() 开头武装，以覆盖 START 之前的挂起；10s bind/identity 与 25s close 未改。宿主单测 81 项全过。本机 x64 调试 APK（未入库）290,523,011 字节，SHA256 `2525e095a891dabb84e587cf2f824b67f07651c763f332c7f20533cf73c33186`。emulator-5554 `install -r -t` Success；19:48:56.686 `bot_fgs_requested`，19:48:57.331 `bot_fgs_foreground`，无 FGS 超时/本包 ANR/FATAL；19:48:58 IsolatedBotService `u0i4`，19:49:04 `bot_isolated_peer_verified`，19:49:06 `bot_grants_sent`，19:49:09 加载 `libmbot_node_runtime.so`，19:50:14 备份 worker `u0i5` 并于 19:50:20 自行 retire（AM 记 crashed 并计划 1s 重启，日志未见第二次 Start proc）；此后隔离进程持续定期 GC 直至 19:51:57.810 `bot_runtime_start_timeout`（距 start() 整 180 秒），随后 `u0a209i4` 因 isolated not needed 被杀。**未见 bot_ready**。这是过载无 KVM 软解模拟器的速度上限，不是把 180 再盲目加长就能证明的产品通路径；也不当 ARM64 证据。真实模型文本/流式/取消：**需用户在 App 配置 provider 后验证**。

### C. ARM64设备

仅用户指定设备，先只读确认序列号/ABI/API/boot。install -r同应用ID，不清数据/wipe/降级卸载，不接触其它设备；记录源码HEAD、工作树、APK大小/SHA、ready/真实UID。先启动与原设置保留，再由用户在App配置provider，发一次短文本、生成中取消、下一条恢复。无ARM64设备就写“仅编译/打包，设备未验证”，**不能拿x64模拟器结果补勾**。

## 本机可并行的小项：流式，不重编已通过的包

当前已装e9d0beb，可只发一条适量长度文本，记录首token时间、至少两次不同长度的真实文本增量、最终状态；确认输出部分文字后再停止、随后短消息恢复。不要把骨架/“正在回复”动画、最终气泡或modelCalls当增量证据。最多限定一轮，失败就给确切现象，不重跑58/23组件。

若确无增量，沿以下顺序定位，改最小层：

1. native ProviderStream / 模型FD真实分帧与flush；
2. model-fd-client → model-sdk-fetch → 原SDK事件；
3. agent-port与官方Bot message.delta事件；
4. MiniMaxBotApi.events → BotController合并通知 → BotHome绘制。

不能为了“看起来流畅”把完整回答在UI本地拆字伪造流式；不要按每个token刷新整个页面。真实Key只在用户设备，该任务不需要把Key转给云端。

## 后续独立任务，先读源码再限定范围

| 任务 | 入口/缺口 | 不允许的捷径 |
|---|---|---|
| 五档effort/多模态 | BotCreate与provider模型设置已具备；核对原mcode SDK实际发出的thinking/output_config及模态转换，再做一小次真实调用 | UI保存成功不等于五档生效；上传成功不等于图像被理解；不得输出请求全体/Key |
| 工具与Bot定时 | 原Bot MCP、任务UI/进程内scheduler已有；还缺真实模型工具回合、可信每Bot caller、同源任务闭环 | 不另造Android scheduler掩盖缺失，不承诺进程死亡后仍执行；不恢复同UID shell |
| 插件 | BotPluginsPort api=null/canMutate=false；McodePluginsApi和UI已在库。需可信插件端点及Android执行/包兼容策略 | 不只打开canMutate；官方deliveryTargets只有desktop/cloud，不伪造android；不把MiniApp当插件 |
| 持久化与恢复 | 已接Main文件/SQLite/备份/只读授予；产品级重启、未知提交、取消死亡竞态仍缺 | 不删除迁移/online backup，不把超时/普通disconnect当真正死亡，不自动清UNKNOWN stage |
| 群聊/助手/记忆 | 原服务业务和部分UI/测试已有，查实际调用/设备缺口再补 | 不再造一套Bot/记忆服务，不假装所有mcode能力已移植 |
| 后台/通知/用量/工作区 | 设置壳部分可达；真实通知/账号租约、项目绑定及生命周期还未完整接入 | 不显示伪额度；自定义API额度不可查询时要如实显示；不滥用存储/保活权限 |
| 发布清理 | 真机/屏幕/profile、品牌素材、许可证和AGPL对应源码供给 | 不删除上游golden资产，不把“未发布调试包”叫正式Release |

## 核心源码地图

以下路径相对仓库根，不是根目录废弃Java app。

| 链路 | 主要源码 |
|---|---|
| Flutter入口/重连/设置 | dsh-go/app/lib/main.dart；bot/bot_app.dart、bot_runtime_endpoint.dart、bot_settings_hub.dart |
| 会话/创建/资料/记忆/附件 | 同一lib/bot/下 bot_home.dart、bot_controller.dart、bot_create_page.dart、bot_profile.dart、bot_memory_page.dart、bot_attachments.dart |
| HTTP/SSE客户端 | dsh-go/packages/dsh_client/lib/src/minimax_bot_api.dart；mcode_plugins_api.dart |
| Main生命周期/UID/ready | dsh-go/app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/runtime/ 下 BotRuntimeManager、BotRuntimeCoordinator、BotRuntimeExecution、BotRuntimeEndpoint、IsolatedBotService、EmbeddedBotNode |
| Key/模型/原生网络 | 同包 bot/BotProviderStore、GuestProviderCatalog、NativeModelRequestHost、NativeModelChannel；model/NativeModelBroker、NativeProvider、NativeModelPayload、ProviderStream |
| Node JNI/产品bootstrap | dsh-go/runtime/native-node/{bootstrap.cjs,product_entry.inc}；共享 dsh-go/tooling/android-node-probe/src/main/cpp/node_probe.cpp |
| 产品JS/原宿主适配 | dsh-go/runtime/minimax-bot-host/{mobile-runtime-entry,bot-runtime-start,runtime-bundle-entry,runtime-host-session}.mjs |
| 模型通路/失败 | 同目录 native-probe-client、native-model-runtime、native-model-binding、native-model-route、model-sdk-fetch、model-fd-client、agent-port |
| 受控review策略 | 同目录 runtime-build-plugin、runtime-services、runtime-native-review；不得用第一方假回答代替 |
| 文件/数据库/备份 | 同目录 mobile-filesystem、runtime-sqlite-*、runtime-snapshot-*；dsh-go/runtime/native-sqlite；Android isolation/RuntimeSnapshotOwner及SqliteBackup* |
| 设置复用包 | packages/mbot_settings；dsh-go/app/lib/l10n/parts为ARB输入，生成器见DEVELOPMENT |
| 原业务快照 | dsh-go/runtime/vendor/minimax-bot/miniapp/node/{server,engine,team,store,memory,mcp}.mjs；通常不改 |

## 环境、证据、协作与交付

- 云端/workspace及/tmp工具不持久保证；Windows D盘也不是云端目录。先探测，不能抄旧路径后声称运行。具体已用位置与命令见DEVELOPMENT。
- GitHub Actions最新实查：01941e0的ARM64 run42380251554确有build job，但check128426174992的annotation为账户付款/支出限制、job未启动。此前07c5a34的同类workflow初版是另一处表达式上下文错误，已用actionlint修正，不能混称计费。看实际job/annotation，不能把push、排队或组件绿灯当新产品验收；也不必等CI才继续本地可做的开发。
- 本机agent负责接口明确的UI/打包/指定设备验收；强agent负责native/ABI/来源/生命周期等核心。派活前给精确基线、允许路径、验收和禁止项；同分支推送前fetch，自有未推提交如需rebase先确认不覆盖他人，不force。
- 报告格式：精确HEAD及tracked状态；实际命令/退出码和范围；设备/API/ABI/UID；若构建则APK大小/SHA；已通过/失败/未验证；下一最小步骤。不要dump完整env、descriptor、prefs、模型请求或原始异常。
- 阶段结束更新HANDOFF与相关专题，正常push后用远端实查确认SHA及保护分支；仓库只放源码/必要文档/合法素材，不放APK、Node归档、node_modules、Key或本机私有报告。

## 可直接发给接手模型的启动提示词

> 接手私有仓Wzdhehe/minimax-bot-android的feat/t-plugins，不新开分支。先git status，保留任何他人改动，再fetch并只允许merge --ff-only。读AGENTS.md、dsh-go/AGENTS.md、docs/HANDOFF.md、docs/handoff/NEXT-AGENT.md、docs/DEVELOPMENT.md和docs/handoff/ARM64-RUNBOOK.md。旧DSH背景不覆盖这些当前合同。x64产品在e9d0beb已通过真实MiniMax文本/取消/恢复，别重新造launcher、换芯或连通性测试。ARM64手工full-ICU候选已复核；256ca35补Node消费者并真实编出ARM64 JNI/SQLite addon；76f67a0接通prepare-product ARM64闸门并编出ARM64调试APK。07c5a34/01941e0/faa531c分别完成独立CI/来源、CI表达式修正、SQLite双ABI隔离。**阶段B（产品准入与APK）已完成，阶段D已修FGS启动顺序并把我们自己的启动预算提到 180 秒，隔离未削弱，仍不代表手机可用或启动全流程已通（本机软解模拟器 180 秒内未见 bot_ready）。** 下一张是阶段C：仅在用户指定的ARM64真机上install -r并验收启动与短文本；无该设备就写“仅编译/打包，设备未验证”，不能拿x64模拟器补勾。不要重做已完成的producer/消费者/产品闸门/FGS启动顺序/启动预算，不要为这一台过载软解模拟器再盲目加长预算，不要放宽验证或把mock打印当编译。不要把 Node 挪回主进程，不要关掉 isolatedProcess。Key不需要交给你。每一小阶段跑相关测试、记下真实结果和未验证项，明确暂存自己的文件，commit并正常push。main/dev/integration不动，不force、不PR、不发布二进制。文档已写明的日常选择直接执行，只有缺少来源/授权等无法从代码与文档决定的信息才提问。

不要让“模型较弱”变成越过准入的理由。任务太大就依现有边界拆开，交付一个完整、可审阅的小阶段。阶段B已完成产品准入与调试APK；没有用户指定的ARM64设备就不能宣布阶段C通过，也不能拿x64模拟器结果补勾。

## 最后一次代码增量与检查，防止误读状态

| 提交 | 内容 | 实际检查 | 没做什么 |
|---|---|---|---|
| 9a7cffe | 整理当前状态、开发命令与接手顺序 | 文档链接57份0问题 | 不改已验产品代码 |
| 07c5a34 | ARM64独立workflow与来源核验器 | ARM64源码/来源20项，原x64来源5项 | 未编ARM64；workflow初版后由01941e0修正 |
| 01941e0 | CCACHE_DIR移入可用runner上下文的step | actionlint1.7.7退出0，ARM6420项；新run42380251554的计费阻塞已实查 | 没有编译步骤执行，不存在成功artifact |
| faa531c | prepare-sqlite固定双ABI目标、独立目录、headers/ELF防混装 | SQLite26项、backup宿主11项、原Node宿主6项全部OK；原x64五个权威输入逐路径diff为空 | 无NDK，未真编任一ABI，未构建新产品APK，未解除产品ARM64拒绝 |
| 256ca35 | ARM64候选审阅记录+Node消费者prepare-arm64-node.py及51项正反例 | 消费者51项OK；android-node-probe全目录149项OK（6项为既有directory_lease无编译器skip）；真实候选+真实NDK27d：消费者EXIT0、ARM64 libmbot_node_probe.so与libmbot_sqlite_probe.so真实编出并readelf核验；篡改1字节归档与重复输出均拒绝 | 当时未改prepare-product/Gradle；无ARM64设备 |
| 498a151 | 缺锁定esbuild时跳过真实probe bundler测试 | 有依赖时该项仍执行；移走node_modules后明确skip并写原因；51项中该项skip时其余OK | 不跳过有依赖时的真实失败 |
| 76f67a0 | prepare-product接通ARM64：只信审阅记录，完整来源/ABI/ELF/SQLite/backup闸门与28项正反例；x64仍走原admitted_native | test_product*.py 35项OK（原x64 7项未改）；probe 149项OK（6 skip）；真实prepare-product arm64与verify-only均EXIT0；混入x64库/错SHA/缺FD/陈旧头均拒绝；Gradle编出ARM64与x64调试APK（见上方面实测） | 未发布APK/Release；无ARM64设备；当时x64软解模拟器因FGS超时未见bot_ready，不作启动通过或产品失败结论 |
| 622e830 | 阶段D：ForegroundStartGate 将 startForegroundService 推迟到主 Looper 空闲；onStartCommand 仍先 startForeground；IsolatedBotService 保持 isolatedProcess=true | ForegroundStartGateTest 6 项 + 相关 Runtime/BotRuntime 宿主单测共 78 项 OK；软解模拟器 bot_fgs_requested→bot_fgs_foreground（1.8s），无 FGS 超时/本包 ANR，隔离 UID u0i2 已核验 | 当时 90 秒预算内未见 bot_ready；未发布 APK/Release；无 ARM64 设备 |
| c8bcdff | 将我们自己的启动预算 90→180 秒，抽成 BotRuntimeStartBudget，并与 descriptorWhenSettled 对齐；隔离与 10s/25s 等待不变 | BotRuntimeStartBudgetTest 3 项 + 相关宿主单测共 81 项 OK；软解模拟器确认预算按 180 秒触发，FGS 仍正常，隔离 UID u0i4 已核验 | 180 秒内仍未见 bot_ready（过载无 KVM 软解模拟器速度上限）；未再盲目加时；未发布 APK/Release；无 ARM64 设备；真实模型调用需用户配置 provider |

复现上述准备器测试，从repo根运行：

```bash
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_sqlite*.py' -v
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_backup_hosts.py' -v
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_prepare_hosts.py' -v
```

本轮对应终态分别为`Ran 26 tests ... OK`、`Ran 11 tests ... OK`、`Ran 6 tests ... OK`。正例mock编译器会打印Prepared，这不是NDK执行证据。文档后续提交不会自动使已装e9d0beb APK失效，也不会使它覆盖后续新native改动。先检查具体改动层再决定是否重建，不派本机agent反复全量重跑。
