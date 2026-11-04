# 最新接手入口（2026-11-04）

**下一位先读：[详细收尾与接手顺序](handoff/NEXT-AGENT.md)**。包含真实完成边界、最近commit/CI/artifact、Main/Node文件契约、下一项建议、工具链现状与复现命令；不是完整App已完成报告。

只在 **feat/t-plugins** 连续开发、阶段验证后及时push并更新文档。用户授权持续推进；不新开分支、不动main/dev/integration、不force push、不建PR、不发布APK。

## 本轮续写：启动会话鉴权（2026-11-04）

从远端7967b9d接手。修复实际 Bot listener 忽略 descriptor token 而另生成凭据的问题；可信启动器可显式传 `authToken`，输入错误在文件/监听副作用前拒绝。Node descriptor 端口/token校验与Kotlin一致，host CI加入descriptor测试。**宿主28项通过**（含实际HTTP鉴权、错误角色token拒绝、重启旧token拒绝）；未修改正式Android启动器，完整APK/真机/真实provider仍**未验证**。详细代码定位、测试命令、剩余装配缺口与环境见[NEXT-AGENT最新快照](handoff/NEXT-AGENT.md)。

重新实查发现：fork公开的 `host.application.miniAppAgent` 不提供 `registerArena`；旧第11节「从公开应用对象注册caller」不是可直接执行的API。下一阶段须接真实内部gateway的宿主注册/撤销生命周期，不能伪造caller，也不能为省事直接用DSH同UID/PRoot跑完整Agent。`miniAppSurface` 已可省略并报OPEN_UNAVAILABLE，继续不适配Mini App容器。

本轮代码阶段**5873c98**已推送。后续加入**完整fork宿主构建图检查**：实际打包2,124个上游输入（逐blob校验）、原session-ports和官方Bot服务，生成13,296,231字节partial bundle；49个外部包引用保持external，3项真实集成测试通过且不执行runtime。已接CI，命令与边界见[钉点文档](UPSTREAM-RUNTIME.md)。这消除了「只有契约文件能打包」的验证空白，仍不代表rootfs/正式启动器/插件市场已运行；动态better-sqlite3等加载及资源仍须单独处理。

同时修正native Key测试遗留的descriptor四字段断言（现有broker字段须严格断言为null），本地完整JUnit32/Node46通过；289f88f的native CI失败日志已读到，JUnit32通过、Node45/46，其他host/docs/scope CI成功。修复后完整native脚本结果及新CI以[NEXT-AGENT](handoff/NEXT-AGENT.md)最新记录为准，不能将旧失败run描述成成功。

本轮随后复核并修复Node descriptor的四处问题：叶文件软链写穿、父目录软链绕开workspace/private排除、writer未投影额外字段、extra环境变量绕过allowlist。四项均先复现失败再修复；现在私有stage原子rename、父路径真实位置校验、同一FD读取/owner权限检查、固定字段序列化及两路同白名单。它仍依赖宿主私有目录所有权，不能替代Main-held cap/独立UID；native路径API及正式启动器本轮未改。本地完整宿主32项、JUnit32项、native/Node50项全通过且0 skipped；文档31份无坏链接，详细验证记录见[NEXT-AGENT](handoff/NEXT-AGENT.md)。

5873c98的远程CI已实查：**host41813024238、native41813024224、docs41813024207、scope41813024280全success**，日志含完整图3/3、JUnit32/Node46及Android组件构建2m29s。这是路径硬化前的阶段证据，不冒充后续新CI。

**最新代码24bdeca的CI也已实查全success**：host41814570291（宿主32、pin23、完整图3）、native41814570242（三个job均成功）、docs41814570339、scope41814570376。新路径/环境回归已在此代码commit运行；不是挪用5873c98结果。没有新增设备仪器运行，也没有完整Bot APK/真实provider/真机证据。纯文档收尾SHA以远端ref为准。

## 当前状态

| 能力 | 已取得的证据 | 尚未完成 |
|---|---|---|
| native-only模型broker | 四类请求体/工具、流/取消；JUnit32、Node44、实际SDK9、钉点pi-ai8；7ce84ec的native/host/scope CI通过 | 真实provider、正式配置同步/启动器 |
| 隔离Node + Keystore + SQLite | bc07b1c/run41663798958：API26/35各1项仪器测试通过，含内存事务/回滚/中文/JSON/serialize及Key边界 | WAL/干净重启另见下行；ARM64、物理真机未验证 |
| 动态模型请求 | d545dc7/run41665743434 Binder矩阵成功；4f92405/run41667564473 Node Promise→JNI→Binder→native模型FD在API26/35成功 | 正式model resolver/scope/生命周期装配 |
| 1MiB大请求FD | 64KiB内保持inline；更大JSON走有界pipe，精确长度/EOF/UTF8/截止/取消。broker目标最终2m53s、Node目标2m39s构建成功，host1+输入拒绝8过 | 3d99bed的Node41682351200/broker41682351421已API26/35通过 |
| 数据文件FD能力（当前研究） | 单个明确授予FD的读/写同步/截断/锁/RW映射五用例已编译，broker目标1m23s成功；按私有路径open必须继续拒绝 | d7d65ad/run41683691783已API26/35通过；不是VFS/持久化已实现 |
| **SDK→JNI路由（当前阶段）** | 虚拟SDK URL/placeholder→可信binding；JUnit32+Node44、SDK9/pi-ai8通过，Node APK43s构建成功 | e03b997/run41686820725已API26/35通过；正式resolver未装配 |
| FD SQLite VFS Android候选 | **46c1c07/run41703671145 API26/35 success**，artifact各t1/f0/e0/s0；WAL/并行只读/重开/integrity、两次不同PID读回、既有模型/Key断言通过；宿主另有writer/reader争用、crash恢复、sync故障注入 | 临时验收四普通文件FD；不是正式native数据lease、完整FS或产品runtime；ARM64/真机/Android crash恢复未验证 |
| Main持久数据库文件owner | 生产RuntimeDatabaseFiles固定四文件、NOFOLLOW/普通文件/单链接/私有权限/owner/worker校验；两个APK43s构建、Python22过；验收Node改用同一生产源码 | **44c0190/41760010243 API26/35各7项全过**；含原模型/Key/WAL两PID。hardlink按平台/owner边界记录，非完整lease/撤销 |
| Main独占文件发布探测 | 新独立JNI在Main用私有目录FD+严格basename做renameat2(NOREPLACE)，4用例已编译；APK2m7s、Python23/host1过 | **7406bb3/41761635832 API26/35各11项通过**，含原七项；仍仅验收，不是完整FS/通用link替代；见T-RuntimeFS |
| Main原子发布结果语义 | **132273a/41765512240 API26/35各17项通过**；生产JNI迁移**8d65ac0/41766746821也各17项通过**，双ABI编译/JNI/16KiB过；见T-RuntimeFS | 不是完整FS/launcher；不能用close撤销SQLite FD |
| Main原子替换 | **30b5249/41767469341 API26/35各21项通过**，含四新增替换/旧FD/并发/unknown和旧回归；同期native/broker设备均success | 未绑定原JSONL/MemoryStore，不得替换leased SQLite inode，unknown须上层恢复；见T-RuntimeFS |
| Main范围/操作文件broker | **10be45f/41769936256 API26/35各25项通过**；真实UID/nonce/范围/覆盖权限、84KiB二进制、active close/drain及原模型pipe/Key/WAL回归；native与broker设备也success | 不等于完整FS或SQLite撤销；该基线仍是隔离Kotlin矩阵，见T-RuntimeFiles |
| Node文件commit JNI | **a49d79b/41771737494 API26/35各25项通过**；offset view快照、实际文件提交/replace/空文件/非法buffers、两PID与扩展Key检查 | JNI仍验收，未接原FS/正式launcher；见T-RuntimeFiles |
| Main有界读取/size | **a96d916/41774204029 API26/35各30项通过**；Main openat、普通私有文件检查、有界响应pipe、输出截止/close、父目录替换下cap不逃逸与旧回归 | 非完整stat/FS，不能代替SQLite撤销；见T-RuntimeFiles |
| Node读取JNI | **585f082/41775322136 API26/35各30项通过**，84KiB/原快照/空文件/大小/缺失与路径拒绝、两PID/Key/WAL保留 | 仍验收，不是完整原FS/生产launcher；见T-RuntimeFiles |
| stage目录FD改造 | c78faca/41776354659的API26 **31项过**；Main固定openat/unlinkat、父目录替换及stage拒绝断言，双ABI过 | API35首次step33秒退出、t0/f0/e0/s0，原因未知；失败job重跑后31项通过（artifact13297459617），首次失败仍保留；见T-RuntimeFiles |
| Main move/remove | 同目录私有普通文件移动/替换/删除，冲突与unknown保留，拒绝symlink/目录/宽权限；四新增用例、APK2m25s、双ABI/Python28过 | **42fba3a/41777415973 API26/35各35项通过**；显式move/remove IPC已**c1b9aee/41779079402 API26/35各35项通过**；Node后续候选另见下行，不能视作完整FS；见T-RuntimeFiles |
| Node move/rename/remove | 新固定异步JNI→隔离Kotlin→Main cap；两新PID必需移动/冲突/替换/删除/拒绝与原Key/WAL断言，钉点JNI/SQLite与Python28/host1通过 | **c8e702d/41779493946 API26/35各35项通过**；不是完整FS/正式launcher，见T-RuntimeFiles |
| 有界append/目录协调 | Main按目录FD inode协调publish/replace/move/remove/append；新增APPEND显式权限与Node JNI，五新增设备用例、原Key/WAL回归保留，两个验收APK2m34s、Python29/host1/scope4/actionlint过 | 888aa77首次API26 t40 f1保留；后续**c8e506e与78d49f5各自API26/35均44项通过**（含追加、目录与夹具修正）；O(文件大小)整文件原子追加/总量1MiB，不是O_APPEND/FileHandle/大JSONL或跨进程锁，见T-RuntimeFiles |
| Main子目录cap | 固定openat/mkdirat、私有0700/owner/NOFOLLOW、8级上限；四新增用例、两个验收APK1m33s、Python30/scope4及双ABI JNI/16KiB过 | **c8e506e/41781117518及78d49f5/41781774323均API26/35各44项通过**；只Main API，未给Agent目录FD/IPC/Node，不是完整FS，见T-RuntimeFiles |
| **设备端 runtime 接线（当前阶段）** | 已查清三处硬缺口并补其一：Kotlin 侧 descriptor 写入（0600/O_EXCL/O_NOFOLLOW/rename/owner-only 校验）+ Node 契约同步扩展 broker 会话；**设备已验**：41794831403 API26/35 success（各19项、含新增3项descriptor用例、0失败），41794661470 node矩阵 API26/35 各49项0失败（44+5 held-cap），host 41794831396 success。**pin 已扩展为按整树 blob 校验**（b0d570c），fork runtime 现在可以进 bundle | rootfs 仍只装 DSH 引擎，mcode fork/官方 Bot/host adapter 未进 guest；设备侧无人启动 Bot 服务；**产品 APK 未产出**（Windows 上卡在 Kotlin 增量缓存，见 NEXT-AGENT 第13节）；见 NEXT-AGENT 第11节 |
| full-ICU Node | 旧41630373256取消、41680254279超330分钟失败。新41757171111 attempt1也budget_exhausted，但artifact13302870059确认cache-save success | 本轮已重跑失败job，attempt2编译中；preflight/cache restore step成功不证明hits或完整库。无full-ICU产物/Intl设备证据，默认候选无Intl；五个producer权威输入未改，不重复启动并行producer |

正式入口仍DSH；上述都是组件/独立验收APK/假provider，**没有完整mcode/官方Bot产品运行、完整Flutter Bot APK或真实模型验收**。插件变更仍关闭。持久化、可信Bot身份/MCP、工具权限及生产启动器仍缺。不得因组件通过就宣称App已完成。

详见 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)、[T-AndroidNode](handoff/T-AndroidNode.md)、[T-SQLite](handoff/T-SQLite.md)。较早的逐轮状态只作历史。

## 从哪里继续

**最新续写分支：`feat/t-plugins`**。这是连续叠加的功能分支链，不要从仍未更新的 main 续写，也不要分别重复 cherry-pick 前面各分支：

```text
dev/integration 891aefa
  → feat/t-sandbox a1f9738
  → feat/t-settings 1e4b97e
  → feat/t-providers 4c88198
  → feat/t-usage ce00692
  → feat/t-schedule 5e1860e
  → feat/ui-motion f04ce36
  → feat/t-plugins f0d93cb（插件代码；后续收尾文档提交见 git log）
```

`main` 保持 `e32fbd7383cd9bdc268aa7df2ec9150e8bc4e48e`；`dev/integration` 保持 `891aefa71c5aea952ec8aa2502c2ada854854042`。没有合并到这两个分支，没有 force push、PR 或 APK 发布。用户已授权持续开发及阶段 push，后续继续在功能分支提交并及时 push，不再为普通可逆修改重复询问。

开始先看 `git status --short --branch`，保护其他开发者的未提交工作，再 fetch/checkout 最新功能分支。当前执行环境保存的 gh CLI 凭据有效，但继承的 GH_TOKEN 无效；必要时使用 `env -u GH_TOKEN -u GITHUB_TOKEN gh ...` / `git ...`，不要输出任何密钥。

## 产品决定

- Android 本机多 Bot，改造 DSH Go 外壳，MiniMax Code WebUI fork 的 runtime，复用官方 mbot 业务；不是通用 coding Agent 工作台，不做云环境、远控、手机操控。
- 设置输入 Key / Base URL / 模型。保存的Key留在native；broker/隔离Node FD已有组件验证，正式启动器与WebUI配置同步尚未装配。
- 手机固定竖屏；平板允许横屏和自适应展开。参考 Grok 布局，不复制其商标图案。
- **自动任务在各 Bot 资料/设置页**，与 Bot 经 mbot MCP 创建的任务共用数据源；全局设置七组，无自动任务组、无电脑行。进程被杀后不执行、不补跑。
- **单一总工作区 `filesDir/workspace`，项目放子目录，多个 Bot 可绑定同项目协作**。仅总根和路径守卫已落地，项目管理/目录绑定待开发。cwd 不等于授权范围，PRoot 不等于沙箱。
- 页面要流畅、有过渡/轻量骨架，尊重减少动态效果/DSH 性能降级；不宣称未测的真机 FPS。
- Mini App 是另一个可视化程序类型，暂不适配。mcode 插件市场单独做，不能把能从 GitHub 下载等同于在 Android 上可安装执行。

## 外部 agent 任务验收：文档链接检查器

M3.1 Flash已提交78c8293（仅tooling/docs-links内4文件），本工作区已fetch并快进接收。38项测试与9处失效链接已独立复现；主agent修复history文档的9处链接、加入根Python缓存忽略规则。补上“目录读取失败被os.walk吞掉而误报成功”的回归，现39项通过；symlink测试外部目标也改为受管临时兄弟目录，避免共享/tmp固定文件。已新增只读、Python3.12的docs-links workflow；本地README/docs/检查器文档扫描29份0问题，**860563e的文档CI 41784617417已success**。原交付报告和主验收补记均见[REPORT](../tooling/docs-links/REPORT.md)。

## 新增参考：Nyral

用户提供aixtin/nyral，已只读检查固定提交72289ba9ad9dcbe894a3277c6e0f4a45e6dd7328（MIT），见[源码对照](research/nyral.md)。自研Kotlin引擎/原生SQLite更直接，但未发现mbot/mcode runtime或官方插件市场适配，不能因此放弃既定兼容要求。可借鉴会话epoch、pending事务与轻量交互；不搬宽存储、同UID shell、手机操控、系统闹钟链。本轮没有复制代码或构建/运行Nyral，当前脚本还有crashToken构建输入要求；原有安全边界不变。

## 新增参考：mcode-agent-table

用户提供的MiniMax官方BYOB项目已固定到f230386ba0e405f2470d4f42863df3a5c0399297（MIT）并静态检查，见[源码结论](research/mcode-agent-table.md)。对群聊拓扑/发言权、任务审批/执行/核验及账本恢复有用，暂作后续协作参考，不替换当前runtime/FS/身份路线，不导入Rabbit Hole Mini App。MCode适配器的full执行thread、Node私钥/env和直接FS不能照搬；本轮没有安装或运行其测试/CLI/模型。

## 已做的事情与边界

按顺序阅读各专题 handoff，避免只看提交标题：

1. [T-Sandbox](handoff/T-Sandbox.md)：路径防护、宿主媒体检查、targetSdk36、去 FileProvider 宽外部路径、凭据备份排除。**不是独立 UID 隔离**。
2. [T-Settings](handoff/T-Settings.md)：设置接入、通知偏好、native 系统跳转。旧八组已被每 Bot 任务决定覆盖。
3. [T-Providers](handoff/T-Providers.md)：四 API 协议多供应商/独立 native 加密保存、metadata UI、宿主 probe。native-only broker已有组件证据，正式模型同步未接。
4. [T-Usage](handoff/T-Usage.md)：只读额度白名单投影、页面和 API provider 不冒充账号登录；真实 OAuth/UsageQuotaClient 未装配。
5. [T-Schedule](handoff/T-Schedule.md)：Bot 资料/任务管理、同源 MCP 宿主测试、进程内 SSE→native 通知桥。调用者身份可信绑定、真机通知、断线/页面外通知尚未完成。
6. [UI-Motion](handoff/UI-Motion.md)：DSH 路由过渡、轻量骨架、会话入场、lazy lists、SSE 直接合并/32ms 通知与晚到历史竞态保护。
8. [T-RuntimeBroker](handoff/T-RuntimeBroker.md)：native Kotlin broker、四协议文本/工具请求体、流/取消、host代理/FD与Key边界；JUnit32+Node31通过，Android组件编译通过，正式runtime未装配。
9. [T-Isolation](handoff/T-Isolation.md)：isolatedProcess 文件占位、主进程 broker、身份/请求关联客户端、纯 JVM 路径/会话测试；Android组件 Gradle构建通过，Android35 模拟器文件 IPC 用例通过；完整 Flutter/Node 构建未完成。
7. [T-Plugins](handoff/T-Plugins.md)：插件页和只读 mcode adapter、GitHub 固定版本预览、协议字段/installed cursor 差异。**生产变更入口强制关闭，当前无正式运行时 descriptor，非已完成安装市场。**

关键现实：默认正式启动器仍是 DSH 基线。`lib/dev/minimax_bot_main.dart` 只是 UI 联调入口。没有成功构建完整 fork APK，没有真实 Android 设备，不能说 App 已写完/模型已跑通/完整沙箱可用。

## 接下来优先做

1. 查full-ICU producer41757171111 **attempt2**：本轮已重跑失败job，API实查in_progress。attempt1是budget_exhausted，cache-save success（artifact13302870059），没有库/verify证据；五个producer输入未改。后续须看真实cache恢复、完整编译/产物与Intl设备矩阵，不因缓存保存成功就宣布解决。
2. 按[NEXT-AGENT最新快照](handoff/NEXT-AGENT.md)接fork完整宿主、可信内部gateway注册和native/独立UID模型/文件能力。held-cap publisher已完成（41794661470 API26/35各49项过），Kotlin descriptor也已设备验证（41794831403各19项过），本轮补了实际Bot服务会话token一致性。不要重做旧held-cap建议，不手写17个会话端口、不伪造注册、不回退同UID shell。rootfs/正式启动器仍缺；单纯改DSH argv不能算安全集成。
3. 注册内置 Bot 的可信身份/MCP/会话归属，再验收 Bot 自主创建任务和后台通知。现 MCP 测试传 caller_bot_id 不证明防伪造。
4. Android 安装策略定稿并由宿主强制后，才逐步开放插件变更；先处理 installed cursor 协议差异。不会因为 UI 测试能启停就开放实际执行。
5. 总工作区下实现项目管理与 Bot 绑定，遵守路径防护并分开 cwd 和访问许可；补资料库、附件、群聊/记忆/审批等全量 parity 未完成项。
6. 完整 APK、真机权限/Keystore/通知/后台/手机竖屏/平板横屏及 profile 性能验收。没有设备时如实标未验证。

## 本环境验证工具

环境曾中断，恢复后 `/workspace` 保留，`/tmp` 清空并部分重建。路径须实查。

- Node24.19.0；Kotlin `/workspace/toolchains/kotlinc/bin/kotlinc` 2.4.0；Flutter `/workspace/toolchains/flutter/bin/`。
- 当前组件构建：完整JDK `/tmp/mbot-jdk21`、Gradle9.3.1 `/tmp/mbot-gradle/gradle-9.3.1/bin/gradle`、SDK `/tmp/mbot-android-sdk`（platform36/build-tools36.0.0）；此次恢复改用持久缓存 `/home/agent/.gradle`。JDK/Gradle按发布SHA256验证，SDK/NDK/CMake按Google清单摘要恢复；旧/tmp缓存已清空，不能假定离线构建可用。
- `/tmp/mbot-native-deps`、`/tmp/mbot-gradle-cache`不存在；`/tmp/mbot-mcode-reference`本轮已恢复为干净钉点checkout，pin测试23项通过。缺失jar仍需按CI固定摘要恢复再运行对应测试，勿沿用旧路径判断。`/tmp/mbot-ndk27d`、`/tmp/mbot-node24-mobile.zip`、SQLite归档仍存在，JNI/SQLite准备输出保留；没有adb/设备/KVM，full-ICU源码编译在CI。
- Widget测试需 `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts`；只暂存明确路径，检查pub/analyze产生的无关修改。
- 完整命令见T-RuntimeBroker；组件APK编译不是完整Flutter Bot APK。

## 最终验证结果

- 最后完整设置包测试 **27 passed**；包括 4 个 motion 和 5 个插件测试。插件初轮发现测试 tooltip 定位错误和预览期间加载动画未停导致 settle 超时，均修正后完整通过。
- 最新插件 App 适配/入口 + 设置回归 **3 passed**；此前动画/会话/资料/通知/额度相关 **12 passed**，最终 UI 改动后相关子集 **7 passed**。
- mcode 插件协议 **4 passed**；协议客户端、协议测试和 runtime 测试文件静态分析通过。**本轮未设置 MCODE_WEBUI_ENTRY，没有跑真实 WebUI runtime 测试。**
- App `flutter analyze --no-pub lib/bot test/bots/bot_plugins_test.dart` 与设置包 `dart analyze` 无问题；最后对 cursor/subPath 补拒绝截断的校验并复跑插件测试。
- 已实查成功的 GitHub Actions：T-Schedule host **41554914121** / settings **41554913845** / scope **41554913796**；UI-Motion settings **41556055349** / scope **41556055408**。UI-only 提交没有触发 host workflow，不编造对应 run。
- `feat/t-plugins@f0d93cb9ed8fbc4254fefd476505793ef10c683a` 已推送并通过 ls-remote 确认。scope **41557103632 success**；settings **41557103607** 截至收尾实查仍在准备 Flutter，**尚不能算通过**。下一位运行 `gh run view 41557103607` 查看结论；以上 CI 均不构建 Android APK。后续仅文档提交不改变已测试代码。
