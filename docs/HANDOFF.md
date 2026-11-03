# 最新接手入口（2026-11-03）

**诊断已定位**：`09faafd`/run41603434315 的26失败有 `binderAlive=true` 和 isolated→app `tcp_socket read write` SELinux拒绝证据。新版17e7629只传Unix socketpair，run41604321437待观察。Android Node源码候选现找到Termux24.22.2（已钉recipe提交、未采用/构建），详见 [runtime计划](ANDROID-RUNTIME-INTEGRATION.md)。数据路径/SQLite/WAL仍是独立关口。

**当前候选传输**：native 主进程保留 TCP，向隔离端只传 Unix socketpair 端点；`NativeModelChannels` 最多4通道，每通道单 poll worker/32KiB缓冲，支持容量拒绝、取消、截止与owner关闭。新增仪器用例共八项已编译，API26/35新矩阵待观察。宿主 **JVM27 + Node30 全过**（含真实 Unix FD），组件 Gradle **BUILD SUCCESSFUL in 1m 24s**。不能提前说26兼容已解决。

**最新 FD 结果与后续**：Node FD 消费器、共有SSE消费和回调取消已实现；宿主脚本 **JVM27 + Node29 全过**，供应商/服务10、scope4通过。Android矩阵 `41602011149`：**API35六项成功，API26两项TCP FD传递失败（DeadObjectException）**，总体失败，仍须解决26兼容。API35才有直接文件/网络拒绝与FD流/取消实证，不等于已装入Node。下一步先诊断/修复26传输，再做Android Node/JNI与原生依赖。

**最新 FD 通道**：可信 native 可创建单个已认证模型请求的预连接 FD；只移交 FD，不移交 native endpoint/token/Key。新增测试专用隔离探针，验证直接文件/网络拒绝、通过 Binder 接收 FD 的真实 broker SSE、关闭 FD 取消停滞 provider。六项仪器测试已编译，API26/35 CI 待观察。组件 Gradle **BUILD SUCCESSFUL in 1m 30s**，JVM27项通过。Node FD 消费、runtime 加载、工具协议和正式生命周期仍未接；见 [T-RuntimeBroker 首节](handoff/T-RuntimeBroker.md)。

**最新工作区资源限制**：文件读取改为读取期间最多 1MiB +1 探测字节，不再只信 length；目录改为惰性枚举，最多1000条/128KiB文件名字节预算后才排序，超限拒绝而不返回截断列表。拒绝非普通文件。宿主脚本 **JVM27 + Node22 全过**；组件 Gradle构建再过，XML27项零失败/错误/跳过。这不解决路径检查到打开的 TOCTOU/hardlink 问题，设备结果仍另行观察。

**最新设备验证**：`2d7367b` 的 Android35/KVM 模拟器 run **41600336727 success**，日志确认 `Finished 3 tests on emulator-5554 - 15` / `BUILD SUCCESSFUL in 5m 20s`。覆盖真实 Android Keystore 存储、native HTTP 假 provider 注入/回显拦截、独立 UID 文件 IPC 与 broker 穿越拒绝；不证明直接私有文件/网络拒绝（另待探针）、硬件 Keystore 或真实 API。XML artifact 下载在本环境返回403，结论来自 run API 和执行日志，未宣称读过 XML。**物理真机未验证**。本地仪器 APK 编译：`BUILD SUCCESSFUL in 1m 27s`。验收工程复用生产 localhost-only network security config，不放宽 provider HTTPS。上一阶段 `5af2ed8` 的 GitHub native **41599240875**、host **41599240883**、scope **41599240858** 全部 success。

**最新流式补强**：native/host 增加完整结束标记、异常断流处理、背压、绝对截止时间、reader/call 清理及解析前 JSON 预算；proxy.dispose 会取消并收敛模型请求。最终本地 **JVM24 + Node22 全过**，Gradle组件构建再过、XML24项通过；设备测试仍未运行。说明见 [T-RuntimeBroker 首节](handoff/T-RuntimeBroker.md)。前一提交 `4c3e808` 的 GitHub **41591483093**：native/Android编译两项均 success。

**最新 Android 构建进展**：新增复用生产源的 `tooling/android-broker-probe` 验收工程，Gradle 9.3.1/AGP9.1/JDK21 下 `testDebugUnitTest assembleDebug assembleDebugAndroidTest` **BUILD SUCCESSFUL**。XML确认 JVM20项全过；Keystore/IPC 两类仪器 APK 已编译打包，但**未在设备执行**；不是完整 Flutter Bot APK，更不是 Node runtime 已移植。存储类从 Flutter 注册文件原样拆出供验收复用。后续依赖/隔离/模型传输关口见 [Android runtime 计划](ANDROID-RUNTIME-INTEGRATION.md)；实际命令/限制见 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)。

**继续推进 IPC 装配**：补 `AgentIsolationClient`、owner UID/会话 gate、请求 ID 关联、超时/断线/close 与保守 Binder payload 预算；仍是文件占位服务，不是 Node/runtime。JUnit 现 **20 passed** + Node/Kotlin **5 passed**，API36 下 isolation 源码独立编译通过、scope 4 passed。设备 Binder/UID/生命周期用例已写但**未验证**。下一步应先解决 isolated UID 的 Node 加载和受控模型 transport，不能假定 loopback 可访问，见 [T-Isolation 最新节](handoff/T-Isolation.md)。

该阶段提交 `a743d5f` 的 GitHub native **41588261052 success**、scope **41588261051 success** 已实查。

**本次最新进展**：直接从远程 `feat/t-plugins@b7ca158` 续写 native Kotlin provider broker（未新建分支）；四类文本协议、SSE、socket 取消、native Key 回显防护已实现，JUnit 6 + 跨进程 4 + 宿主回归 19 + scope 4 通过。Key 防泄露测试的假 Key 只在 native JVM 生成，检查 runtime descriptor/env/log/Agent 可读文件。正式启动/配置同步/Android 设备验证仍待完成，见 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)。用户最新要求持续按文档开发、阶段 push，覆盖了之前“只做一项就停”的限制。

**进一步进展**：设置页供应商 probe 的 Node 持 Key/直连旧方案已删除，服务只接受 `modelBroker` 流能力，经 native 完成请求；首个文本输出后取消、超时不重试，跨进程验证增加到 **5 passed**（加 JUnit 6、宿主 CI 集合 18）。native 阶段提交 `fad4f3d` 的 GitHub native CI **41586158979 success**、scope **41586158832 success**；旧 host CI 在临时目录删除遇到竞态，测试清理已加有限 retry，生产 shutdown/drain 未因此宣称完成。

`19d487d` 已经远程实查三组 CI **success**：native **41586891156**、host **41586891056**、scope **41586891057**。原失败记录保留作原因说明，不能继续说 host CI 仍是红的。

用户要求额度结束前推送代码、维护文档，方便其他 Agent 接手。本文件优先描述当前状态；`DEVELOPMENT.md` 下面较旧的逐轮记录只作历史，不能据此判断 App 已完成。

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
- 设置输入 Key / Base URL / 模型。保存的 Key 留在 native；provider broker、native→Node 和 WebUI 配置同步尚未装配。
- 手机固定竖屏；平板允许横屏和自适应展开。参考 Grok 布局，不复制其商标图案。
- **自动任务在各 Bot 资料/设置页**，与 Bot 经 mbot MCP 创建的任务共用数据源；全局设置七组，无自动任务组、无电脑行。进程被杀后不执行、不补跑。
- **单一总工作区 `filesDir/workspace`，项目放子目录，多个 Bot 可绑定同项目协作**。仅总根和路径守卫已落地，项目管理/目录绑定待开发。cwd 不等于授权范围，PRoot 不等于沙箱。
- 页面要流畅、有过渡/轻量骨架，尊重减少动态效果/DSH 性能降级；不宣称未测的真机 FPS。
- Mini App 是另一个可视化程序类型，暂不适配。mcode 插件市场单独做，不能把能从 GitHub 下载等同于在 Android 上可安装执行。

## 已做的事情与边界

按顺序阅读各专题 handoff，避免只看提交标题：

1. [T-Sandbox](handoff/T-Sandbox.md)：路径防护、宿主媒体检查、targetSdk36、去 FileProvider 宽外部路径、凭据备份排除。**不是独立 UID 隔离**。
2. [T-Settings](handoff/T-Settings.md)：设置接入、通知偏好、native 系统跳转。旧八组已被每 Bot 任务决定覆盖。
3. [T-Providers](handoff/T-Providers.md)：四 API 协议多供应商/独立 native 加密保存、metadata UI、宿主 probe。真实 broker 桥和模型同步未接。
4. [T-Usage](handoff/T-Usage.md)：只读额度白名单投影、页面和 API provider 不冒充账号登录；真实 OAuth/UsageQuotaClient 未装配。
5. [T-Schedule](handoff/T-Schedule.md)：Bot 资料/任务管理、同源 MCP 宿主测试、进程内 SSE→native 通知桥。调用者身份可信绑定、真机通知、断线/页面外通知尚未完成。
6. [UI-Motion](handoff/UI-Motion.md)：DSH 路由过渡、轻量骨架、会话入场、lazy lists、SSE 直接合并/32ms 通知与晚到历史竞态保护。
8. [T-RuntimeBroker](handoff/T-RuntimeBroker.md)：native Kotlin broker、四协议文本流/取消、host 代理/descriptor 与 Key 边界测试；JVM24 + Node22 通过，Android组件编译通过，正式 runtime 装配尚未完成。
9. [T-Isolation](handoff/T-Isolation.md)：isolatedProcess 文件占位、主进程 broker、身份/请求关联客户端、纯 JVM 路径/会话测试；Android组件 Gradle构建通过，Android35 模拟器文件 IPC 用例通过；完整 Flutter/Node 构建未完成。
7. [T-Plugins](handoff/T-Plugins.md)：插件页和只读 mcode adapter、GitHub 固定版本预览、协议字段/installed cursor 差异。**生产变更入口强制关闭，当前无正式运行时 descriptor，非已完成安装市场。**

关键现实：默认正式启动器仍是 DSH 基线。`lib/dev/minimax_bot_main.dart` 只是 UI 联调入口。没有成功构建完整 fork APK，没有真实 Android 设备，不能说 App 已写完/模型已跑通/完整沙箱可用。

## 接下来优先做

1. 阅读 `docs/architecture.md` 卡与 `docs/sandbox.md`，先落实独立 UID + broker 的 Android 最小实证、Node/runtime 原生依赖与宿主生命周期；禁止回退同 UID 任意 shell。**独立 UID + broker 的代码层最小实证见 [T-Isolation](handoff/T-Isolation.md)**：`isolatedProcess` 服务 + 主进程 broker + 纯 JVM 路径守卫单测已落地；真机行为、Node 原生依赖与宿主生命周期仍未验证。
2. 打包/启动钉点 runtime，可信 descriptor 私有传递；接 provider native broker、配置/模型同步，真实模型流和取消。API Key 不传入 Agent/插件环境。**进度见 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)**：host 代理/descriptor 与 native Kotlin broker 都有实现；跨进程模拟 provider 流/取消、Key 不进 runtime descriptor/env/log/Agent 文件的测试通过。下一步是正式 Android 启动器/隔离装配与可信 descriptor 传递、模型配置/工具协议同步；**Android Keystore/完整 APK/真机及真实 MiniMax API 均未验证**。
3. 注册内置 Bot 的可信身份/MCP/会话归属，再验收 Bot 自主创建任务和后台通知。现 MCP 测试传 caller_bot_id 不证明防伪造。
4. Android 安装策略定稿并由宿主强制后，才逐步开放插件变更；先处理 installed cursor 协议差异。不会因为 UI 测试能启停就开放实际执行。
5. 总工作区下实现项目管理与 Bot 绑定，遵守路径防护并分开 cwd 和访问许可；补资料库、附件、群聊/记忆/审批等全量 parity 未完成项。
6. 完整 APK、真机权限/Keystore/通知/后台/手机竖屏/平板横屏及 profile 性能验收。没有设备时如实标未验证。

## 本环境验证工具

- Flutter/Dart：`/workspace/toolchains/flutter/bin/`；App widget 测试加 `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts`，否则字体配置会使测试加载失败。
- Node24.19.0；JDK21 位于 `/usr/lib/jvm/java-21-openjdk-amd64`；Kotlin 编译器在 `/workspace/toolchains/kotlinc/bin/kotlinc`。
- `/tmp/mbot-api36/android-36/android.jar` 仅供独立 Kotlin 编译，**不是完整 SDK**。无 adb/device/NDK，不要把它当作构建完成。
- `/tmp/mbot-plugin-reference/` 是本轮临时读取钉点上游协议的文件，不是完整源码/构建，不入仓库。临时 widget 预览图片在 `/tmp/mbot-*.png`，不是真机截图。
- `flutter pub get` 可能自动改 settings 的 analysis_options，`dart test` 可能改 dsh_client 锁文件；复核并恢复无关变更，只暂存明确路径。

## 最终验证结果

- 最后完整设置包测试 **27 passed**；包括 4 个 motion 和 5 个插件测试。插件初轮发现测试 tooltip 定位错误和预览期间加载动画未停导致 settle 超时，均修正后完整通过。
- 最新插件 App 适配/入口 + 设置回归 **3 passed**；此前动画/会话/资料/通知/额度相关 **12 passed**，最终 UI 改动后相关子集 **7 passed**。
- mcode 插件协议 **4 passed**；协议客户端、协议测试和 runtime 测试文件静态分析通过。**本轮未设置 MCODE_WEBUI_ENTRY，没有跑真实 WebUI runtime 测试。**
- App `flutter analyze --no-pub lib/bot test/bots/bot_plugins_test.dart` 与设置包 `dart analyze` 无问题；最后对 cursor/subPath 补拒绝截断的校验并复跑插件测试。
- 已实查成功的 GitHub Actions：T-Schedule host **41554914121** / settings **41554913845** / scope **41554913796**；UI-Motion settings **41556055349** / scope **41556055408**。UI-only 提交没有触发 host workflow，不编造对应 run。
- `feat/t-plugins@f0d93cb9ed8fbc4254fefd476505793ef10c683a` 已推送并通过 ls-remote 确认。scope **41557103632 success**；settings **41557103607** 截至收尾实查仍在准备 Flutter，**尚不能算通过**。下一位运行 `gh run view 41557103607` 查看结论；以上 CI 均不构建 Android APK。后续仅文档提交不改变已测试代码。
