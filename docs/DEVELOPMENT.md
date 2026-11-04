# 开发交接

**最新接手入口：[HANDOFF.md](HANDOFF.md)**。从 `feat/t-plugins` 继续；下文保留历史分轮记录，最新状态和未完成装配以总交接和专题 handoff 为准。

## 当前验证入口（2026-11-03）

当前运行时工作在 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)、[T-AndroidNode](handoff/T-AndroidNode.md)、[T-SQLite](handoff/T-SQLite.md)、[T-Persistence](handoff/T-Persistence.md)。仍是组件/模拟器，不是完整产品APK；默认入口仍DSH。

```bash
MBOT_NATIVE_DEPS=/path/to/pinned-jars KOTLINC=/path/to/kotlinc bash dsh-go/tooling/test-native-model-broker.sh
npm ci --ignore-scripts --no-audit --no-fund --prefix dsh-go/tooling/model-sdk-probe
node dsh-go/tooling/model-sdk-probe/prepare-pi.mjs /path/to/clean/pinned/minimax-code
npm test --prefix dsh-go/tooling/model-sdk-probe
npm run test:pi --prefix dsh-go/tooling/model-sdk-probe
python3 dsh-go/tooling/android-node-probe/test-fd-vfs.py --ndk /path/to/ndk27d
# 先prepare Node headers及SQLite wrapper后，可加 --node-probe 验证宿主真实addon跨进程
python3 dsh-go/tooling/android-node-probe/check-native-files.py \
  --ndk /path/to/ndk27d --cmake /path/to/android-sdk/cmake/3.22.1/bin/cmake
# 同一生产CMake的x86_64/arm64-v8a API26交叉编译/JNI/16KiB检查，不是ARM64运行
```

Android工具与实际命令见专题卡；`tooling/android-broker-probe`编译真实native源码/测试，`tooling/android-node-probe`另需先prepare-probe、prepare-sqlite（Node archive/NDK/esbuild均钉版本）。后者默认候选无Intl，full-ICU从源码独立CI构建（ccache/300分钟预算保留保存时间；缓存实际复用与完整编译待CI，见T-AndroidNode）；已有成功producer可按T-AndroidNode的PINNED_SOURCE_RUN_ID复用，不必重复长编译（此复用路径端到端未验证）。实际API26/35结果以HANDOFF中的run ID为准；宿主测试和编译不能代替设备执行，物理真机/ARM64未验证。

Main文件发布JNI现由App/Node验收工程共用生产CMake构建，需NDK27.3.13750724、SDK CMake3.22.1；`RuntimeAtomicPublisher.open`在Main worker admission后加载。组件17项设备基线与生产JNI迁移后的设备结果须区分，见[T-RuntimeFS](handoff/T-RuntimeFS.md)。

## 产品边界

Android 本机多 Bot，参考 Grok Bot 布局及官方 MiniMax Bot 功能。用户在设置中输入 MiniMax 的 API Key、Base URL 和模型；模型服务需要联网，无需自建云或远控。手机固定竖屏，平板允许旋转并根据窗口宽度展开。不要加入手机 UI 操控或通用 Agent 工作台。

Mini App 是另一类可视化程序，不是插件，暂不适配通用 Mini App。官方 MiniMax Bot 上游以 Mini App 形式发布：仅复用其 Bot 业务逻辑，作为本应用的内置服务。MiniMax Code 的插件市场是另一个独立需求，尽可能实现，但不能假设 Mini App 可作为插件安装。详细目标和未完成项见 [功能清单](feature-parity.md)。

## 许可证与代码落点（已拍板）

- **接受 AGPL 开源**：产品基于 [DSH Go](https://github.com/ccch1mneyyy/dsh-Go)（AGPL-3.0，导入为 `dsh-go/`，钉 `c1c0abb00907efdf48dcb443581686b67597c5a2`）外壳，核心换为官方 MiniMax Bot + mcode-webui/minimax-code runtime；新代码可直接在 `dsh-go/` 内改，根下 `packages/` 已有独立模块可继续使用；分发 APK 须遵守 AGPL-3.0 源码提供义务。
- 考前任务卡与验收命令见 [architecture.md](architecture.md)（T-Runtime → T-Sandbox → T-Settings → T-Providers → T-Usage → T-Schedule → T-Plugins）。外部用 MiniMax Code 按卡开发，本团队验收。
- **Runtime 跟 [mcode-webui/minimax-code](https://github.com/mcode-webui/minimax-code) fork**（MIT，`97818b2`）；官方 [MiniMax-AI/minimax-code](https://github.com/MiniMax-AI/minimax-code) 作上游，更新由我们自行合并进 fork。
- 考前节奏：小步继续开发。插件在 Android 上的安装策略、以及定时验收档位（报告 F.7）**仍未定**。

## 当前代码和真实进度

- **T-Plugins 界面/只读阶段**（最新 `feat/t-plugins`）：全局设置接入插件页，已安装/官方市场、搜索、市场翻页和 GitHub 固定 commit 预览复用 mcode 协议。生产 adapter 未连接正式 WebUI descriptor，所有变更强制拒绝；Android 包/执行策略和真实运行时装配尚未完成，整卡不算完成。见 [handoff](handoff/T-Plugins.md)。

- **UI 动画与性能**（`feat/ui-motion`，基于 `feat/t-schedule@5e1860e`）：沿用 DSH 页面过渡/预测返回与低性能减少动态效果机制；主要初次加载入口加入单 ticker 骨架，页面内会话切换短过渡，供应商/任务按需构建。消息流直接合并 SSE，合并 UI 通知并处理晚到历史/摘要竞态。相关 widget/状态测试和分析通过；**未做 Android profile 帧率实测**。最新续写分支为此分支，详见 [handoff](handoff/UI-Motion.md)。

- **T-Schedule 进行中**：按用户最新参考图增加 Bot 资料页；自动任务入口下沉到该 Bot 设置，支持同源列表/创建/编辑/开关/删除/立即执行，以及每 Bot 通知开关。全局设置不再显示任务组。Bot 自建任务复用 mbot MCP 并通过真实宿主 HTTP 测试验证，不另做系统 scheduler。新增进程内到点事件通知桥；**真实 runtime 的 MCP 注册、Android 通知实测及断线场景仍未完成**。详见 [handoff](handoff/T-Schedule.md)。

- **T-Usage 契约/界面阶段**（`feat/t-usage`）：账号与模型内增加用量入口，区分无登录租约、自定义 API 供应商不支持、真实查询失败与夹具账号额度；只经本机宿主读取白名单计划/积分/5h/周字段，不读取 Key。已准备 WebUI `getUsageQuota` 注入槽，**尚未装配真实 OAuth 租约和 UsageQuotaClient，不能宣称已查到真实账号额度**。详见 [handoff](handoff/T-Usage.md)。

- **T-Providers 阶段一**（`feat/t-providers`）：账号与模型页已改为多供应商列表/编辑、四种 `apiFormat`、模型列表和本机默认模型选择；native 每供应商独立 Keystore AES-GCM 存储，并原生迁移旧单 Key 配置。增加宿主 provider probe 和仅接收 ID 的鉴权 HTTP 接口，模拟 native resolver 下验证四类请求与 Key 不回显。**真实 native→宿主桥、WebUI 配置同步和 Bot 模型同步仍未完成，整张 T-Providers 未验收。** 详见 [handoff](handoff/T-Providers.md)。

- **T-Settings 本轮进展**：`feat/t-settings` 基于已推送的 T-Sandbox 分支，`mbot_settings` 已接入 Bot 和旧外壳的设置入口；新 hub 有全部八组，通知/后台为可用页面，账号模型暂接既有加密配置页。独立 Android 通道负责通知渠道、状态检测及用户点击后的系统跳转。包测试 12 项、App 测试 4 项、静态检查及独立 Kotlin 编译通过；原生系统跳转/权限表现未做真机验收，运行时也没有因此完成接入。详见 [T-Settings handoff](handoff/T-Settings.md)。

- **T-Sandbox 本轮进展**：在 `feat/t-sandbox`（自 `dev/integration@891aefa`）接入严格工作区守卫到宿主文件接口和真实媒体读取；修复悬空 symlink 漏洞；删除宽外部 FileProvider 映射、升 targetSdk 到 36、配置 Key prefs 的云备份/迁移排除。守卫 10 项、宿主 5 项、钉点 19 项、Android 源码策略 4 项在 Node 24 环境通过；**Android 构建与 `/sdcard` 仪器用例未运行，整张卡尚未完整验收**。详见 [本轮交接](handoff/T-Sandbox.md)。未改动远程 `main`，未创建 PR。

- `dsh-go/` 是产品外壳（AGPL-3.0）；现有界面和引擎仍是上游 DSH Go，核心尚未替换。远控、品牌及受限素材仍在其中，成品发布前须移除。下文 `dsh-go/` 内的宿主/UI/客户端进展可直接沿用。
- `dsh-go/runtime/vendor/minimax-bot/` 固定官方 Bot 业务源码，MIT 许可。`dsh-go/packages/dsh_client/lib/src/minimax_bot_api.dart` 是对该服务的本机 HTTP 客户端（**不是**根下产品 `packages/`），测试通过官方服务进行 Bot、群组、历史及事件的接口验证；这不意味着已完成 Android UI、模型引擎或插件市场集成。客户端只接受 loopback IP。
- `dsh-go/runtime/minimax-bot-host/service.mjs` 已实现本机 Bot 服务启动封装：每次启动随机生成 256 bit 凭据，所有路由（含 SSE、静态资源和 MCP）在真实监听端口上认证，拒绝浏览器 Origin 和不匹配 Host，重启后旧凭据失效。Node 服务测试已通过。为此对上游 server.mjs 增加了可选宿主认证参数，修改记录见 vendor/UPSTREAM.md。正式 Android 宿主仍需通过私有通道传递 descriptor，且 Agent 执行隔离尚未解决。
- `dsh-go/runtime/minimax-bot-host/agent-port.mjs` 是适配官方 Bot 与 WebUI 进程内 Agent 接口的薄层。宿主测试覆盖流式事件、会话续接和取消信号传递；另一个测试串联了真实 Bot 服务、WebUI 源码中的真实 Agent 网关与模拟模型/会话端口，验证网关授权、撤销、消息回传。完整 WebUI 运行时、正式身份注册、Android 部署和安全边界仍未完成。
- 已对 Android fork 的 manifest 收紧手机操控相关权限，并禁止启动相应通道；设备分类代码使小于 600 dp 的设备固定竖屏，平板遵从系统方向。Android 真机、折叠屏和完整 fork 构建尚未验证。
- 根目录 `app/` 是早期独立 Java 原型，**不是目标实现**；其应用内 shell 已禁用。不要将其已有能力误认为产品已完成的能力。
- 选定 runtime 为 mcode-webui/minimax-code fork；插件管理计划直接复用其插件市场（`packages/webui`）、plugin-management 与 `local-runtime-v2` 的 plugin-system。`dsh-go/` 内仍是 DSH runtime，**尚未**接入该 runtime，故目前不能使用插件市场。本项目尚未接入、尚未验证 Android 插件兼容性；插件 Android 安装策略**仍未定**。不能把 GitHub 下载等同于安全可用。
- `dsh-go/packages/dsh_client/lib/src/mcode_plugins_api.dart` 已实现 WebUI 插件管理协议客户端：市场、已安装列表、GitHub 预览及固定 commit 导入、安装/卸载/启停。仅连接本机，携带宿主启动凭据，无自动重试。Linux 上已用完整上游 WebUI runtime 验证读取已安装插件；插件变更操作目前只通过协议测试，尚未在 Android 或真实插件上验证。该客户端尚未接入产品 UI。
- `dsh-go/app/lib/bot/` 已增加 Bot 原生会话界面和状态控制器：创建 Bot/群聊、历史、发送、停止、搜索、事件刷新、390 dp 单列和 1024 dp 双栏布局。3 项 Flutter 测试通过，包含切换会话时旧历史不得覆盖新会话及不重复发送。尚未接入正式 Android 启动器；联调入口为 `lib/dev/minimax_bot_main.dart`，仅用于连接已有本机 Bot 服务；它不是新增的独立应用，也不是已完成的引擎移植。正式默认入口暂时仍为 DSH 基线。
- Bot 列表的设置入口已增加 Base URL、模型 ID、Key 输入和删除 Key。`BotSettingsPlugin.kt` 在 Android 宿主中用 Keystore AES-GCM 加密 Key，Dart 读取设置时只得到 `hasKey`，不会得到保存的 Key。此设置尚未接入实际 MiniMax Code provider 或原生模型请求 broker；不能声称填写后即可进行模型对话。加解密需要 Android 真机验证，独立 Kotlin 编译与 Dart 检查不能替代它。
- 没有完成真正的隔离执行环境。PRoot 只提供兼容层，不是独立 Android UID 的安全沙箱；不要启用同 UID 任意代码执行作为替代。隔离和密钥设计见 [sandbox.md](sandbox.md)。
- 上游 runtime 钉点已落地（**T-Runtime 卡**）：`dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs` 是机器可读钉点（`mcode-webui/minimax-code@97818b2890b4acae1c6bd1200a226ff506680628`，2026-10-31，MIT），`upstream-runtime-pin-check.mjs` 强制「钉点文件 ↔ 文档 ↔ checkout ↔ bundle 字节」四方一致，`docs/UPSTREAM-RUNTIME.md` 是人类可读的钉点文档。宿主测试与上游网关 smoke 均通过；19 项钉点测试通过。**这只钉住了宿主契约层**，不证明完整 runtime、插件市场或 Android 可用；上游真实的 session 端口实现从未被打包执行。


## 设置页 IA 与定时任务口径

完整验收与触碰路径见 [architecture.md](architecture.md) 任务卡 **T-Settings**、**T-Usage**、**T-Schedule**；分组常量以 `packages/mbot_settings/lib/src/ia/settings_groups.dart` 为准。

摘要：设置分组为账号与模型 · 插件 · 通知 · 后台运行 · 工作区 · 外观 · 关于（无远控/操控）。后台运行只检测+跳转系统设置；须写明进程被杀后定时不会执行。保活由用户在系统设置完成；死后补跑档位未定，UI/文档不得承诺。

## 上游及授权

- **Runtime（已选定）**：[mcode-webui/minimax-code](https://github.com/mcode-webui/minimax-code) fork 的 `97818b2`（MIT；原生依赖与第三方组件导入前再核）。官方 [MiniMax-AI/minimax-code](https://github.com/MiniMax-AI/minimax-code) 为上游，由我们合并其更新。
- **MiniMax Bot**：基线 `0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8`，MIT；快照说明见 `dsh-go/runtime/vendor/UPSTREAM.md`。
- **DSH Go（产品外壳）**：`c1c0abb00907efdf48dcb443581686b67597c5a2`，AGPL-3.0；遵守其许可证与素材限制，分发时提供源码。
- 更多研究见 [research.md](research.md)。

## 下一位开发者的优先事项

按 [architecture.md](architecture.md) 考前任务卡顺序交付（每卡一 PR）：**T-Runtime** → **T-Sandbox** → **T-Settings** → **T-Usage** → **T-Schedule** → **T-Plugins**。禁止同 UID 任意 shell、禁止宽存储权限、Key 不进 Agent/插件。插件 Android 安装策略与定时 F.7 仍未定。

### 运行时接口调查线索

选定 runtime 为 mcode-webui/minimax-code fork；下列路径取自其 `97818b2` 快照。WebUI 在 `packages/local-runtime-v2/src/application/session/process-local-application-contract.ts` 中已有 `miniAppAgent` 端口，提供 `run`、`models`、`reply`、`answer` 和事件回调；这与 Bot 的 `miniapp/node/engine.mjs` 对 `context.agent` 的需求接近，可以研究复用该端口或底层 gateway，而不在产品中提供通用 Mini App 功能。该端口要求可信 `caller`、运行代次和会话归属，不能直接伪造或忽略这些检查；需要先设计内置 Bot 的宿主身份并验证会话生命周期和取消行为。

市场操作入口位于 WebUI 的 `packages/local-runtime-v2/src/application/cli-management-application.ts`，包含 `listMarketplacePlugins`、`listInstalledPlugins`、`previewGithubPlugin`、`importGithubPlugin`、`installPlugin`、启停和卸载。研究市场需要同时核对 catalog 的登录要求、包格式、更新以及 Android 原生依赖和插件执行权限；当前这些均未打通。

## 开发验证

**现状：** Android/Flutter 产品工程在 `dsh-go/`（需 Flutter、Android SDK 及 JDK；见该目录 README / CONTRIBUTING / AGENTS）。宿主机上针对 Bot 宿主的协议测试需要 Node.js 和 Dart：

```bash
cd dsh-go/packages/dsh_client
dart pub get
dart analyze lib/src/minimax_bot_api.dart test/minimax_bot_api_test.dart
dart test test/minimax_bot_api_test.dart
```

Bot 到运行时端口的宿主机测试（使用 Node 24）：

```bash
cd dsh-go
node --test runtime/minimax-bot-host/agent-port.test.mjs runtime/minimax-bot-host/service.test.mjs
```

有已安装依赖的固定版本 WebUI 源码时，还可执行跨上游网关的宿主机测试。**必须使用钉点 commit 的源码**，否则钉点校验会失败；钉点与升钉流程见 [UPSTREAM-RUNTIME.md](UPSTREAM-RUNTIME.md)：

```bash
WEBUI_SOURCE=/path/to/minimax-code@97818b2 bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh
```

非 git 的导出目录（tarball 等）没有 HEAD，必须显式声明 commit：

```bash
WEBUI_SOURCE_COMMIT=97818b2890b4acae1c6bd1200a226ff506680628 \
  bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh
```

此测试会使用该源码自带的 esbuild 将真实网关与本仓库的适配层打包到临时文件；模型和会话执行仍为测试桩，不证明完整 runtime、插件安装或 Android 可用。测试结束删除临时数据。该脚本还会校验 checkout 的 tree、工作树洁净状态与实际进入 bundle 的每个上游文件的 sha256，被改动过的工作树会被拒绝。

钉点机制自身的测试：

```bash
cd dsh-go && node --test runtime/minimax-bot-host/upstream-runtime-pin.test.mjs
```

插件客户端验证：

```bash
cd dsh-go/packages/dsh_client
dart analyze lib/src/mcode_plugins_api.dart test/mcode_plugins_api_test.dart test/mcode_plugins_runtime_test.dart
dart test test/mcode_plugins_api_test.dart
MCODE_WEBUI_ENTRY=/path/to/minimax-code/dist-webui-npm/bin/mcode-webui.mjs dart test test/mcode_plugins_runtime_test.dart
```

最后一项会启动完整上游 WebUI runtime，使用临时数据目录验证已安装插件查询并清理进程；需先根据上游说明构建 WebUI，Node 原生依赖须可用。此处的上游预览启动器使用开发认证模式，仅用于宿主机测试；Android 正式启动器须配置启动凭据并关闭开发模式。

2026-11-02 尝试完整 Android fork 调试构建：Flutter 依赖解析通过，Gradle 下载直连失败后改用本地 Gradle 9.3.1；随后因 `org.gradle.kotlin.kotlin-dsl:6.4.2` 无法从 Gradle Plugin Portal 解析而失败。尚未进入 Android 源码编译，未产出 fork APK。需要修复环境的 Maven/Gradle 下载及证书/代理配置后重试；不能将此构建失败归因于已修改的 Kotlin 源码，也不能声称源码编译已通过。

后续构建加入宿主机 Gradle init 镜像配置和系统 Java truststore 后，已通过此前依赖解析并编译了 Flutter Gradle 插件；随后 Gradle daemon 意外退出，原因尚待确认。完整 Android 构建仍未通过。UI 测试可单独运行：`cd dsh-go/app && flutter test test/bots/minimax_bot_home_test.dart`，所需字体目录按 `test/support/test_fonts.dart` 配置。

再次使用单 worker、1 GB Gradle 堆内存和进程内 Kotlin 编译后，构建已进入应用 Gradle 配置并自动安装 NDK `28.2.13676358`，随后 `/tmp` 空间不足导致下载失败。已清理未修改且已保存来源的临时 DSH Go/MiniApps 重复 checkout 及下载压缩包，但尚未完成下一次构建。下一位开发者需为 SDK/NDK 下载与解压预留足够空间；不要把 partial NDK 目录当作已安装工具链。

## 本轮暂停交接（用户要求保存后结束）

### 本轮（2026-11-03）做了什么

本轮工作在分支 `feat/t-runtime`（已快进合入 `dev/integration`）；**远程 `main` 未改动**，未 force push。

1. **集成分支**：从 `origin/main`（`e32fbd7`）新建 `dev/integration`，依次合并 `docs/arch-baseline`、`feat/settings-skeleton`、`feat/mbot-workspace-guard`。唯一冲突在 `docs/DEVELOPMENT.md`（两边都改了同一条进度条目），已手工合并：保留主干更完整的 Bot 界面条目与 Keystore 条目，其余按 `docs/arch-baseline` 的改写，两边信息都不丢。`feat/mbot-workspace-guard` 与 `feat/settings-skeleton` 合并无冲突。
2. **T-Runtime 卡**（分支 `feat/t-runtime`，见上文「当前代码和真实进度」最后一条）：把上游 runtime 钉点从散文变成被强制的事实，并经两轮独立评审后加固。

### 实际执行过的验证

```bash
cd dsh-go
node --test runtime/minimax-bot-host/agent-port.test.mjs runtime/minimax-bot-host/service.test.mjs   # 3 pass
WEBUI_SOURCE=<97818b2 的 checkout> bash runtime/minimax-bot-host/run-upstream-gateway-smoke.sh         # PASS
node --test runtime/minimax-bot-host/upstream-runtime-pin.test.mjs                                   # 19 pass
```

`.github/workflows/bot-host.yml` 的 `upstream-runtime-pin` job 在一个全新 worktree（零 `node_modules`）里按 workflow 逐步本地复现通过；YAML 语法已校验。

第一版钉点机制经对抗性评审**发现真实缺陷并已修复**：当时只校验 checkout 的 HEAD 与文件存在性，工作树被改动（未提交）时检查与 smoke 仍全绿，而实际打进 bundle 的是被改过的代码。现在钉点额外绑定 tree hash、工作树洁净状态与每个进入 bundle 的上游文件 sha256，并从 8 个只测「常量自洽」的用例扩到 19 个含反向用例的测试。

### 未验证 / 不能当作已完成

- **真 GitHub Actions 从未运行过**（不推送即无法触发）。CI 结论均来自本地逐步复现；`actions/checkout` 以 40 位 sha 作 ref 的行为未在真 runner 上实测。
- **Node 24 未实跑**（本地 22.19.0，上游 engines 允许 `>=22.19 <23`）；**bash 3.2（macOS）未实测**（脚本已避免 bash 4+ 特性）。
- 钉点只覆盖**宿主契约层**；上游真实 session 端口实现从未被打包执行，smoke 证明的是适配层契约形状，不是上游 runtime 行为。
- 钉点树**尚未**打包进 APK；`dsh-go/` 内当前仍是 DSH runtime。
- Android 构建与真机验证本轮**完全未做**，见下方遗留项。

### 下一位的顺序

按 [architecture.md](architecture.md) 任务卡顺序：**T-Sandbox** → **T-Settings** → **T-Providers** → **T-Usage** → **T-Schedule** → **T-Plugins**。每卡一分支、一 PR。T-Runtime 已完成并通过验收。

更新：T-Sandbox 的宿主实现与 Android 源码收紧已在本轮分支落地，优先补设备验收，具体以 [T-Sandbox handoff](handoff/T-Sandbox.md) 为准；不要把旧 handoff 中尚未实现的状态当作当前源码，也不要把宿主测试当作 Android 沙箱验收。

### 遗留项与注意事项

- **CI 触碰范围的一处判断**（供审阅者复核）：T-Runtime 卡的「触碰」列的是 `dsh-go/runtime/minimax-bot-host/`、上游钉点文档、必要时 vendor/锁文件，但卡的「目标」写明「文档与 **CI/smoke** 钉」，故本轮改了 `.github/workflows/bot-host.yml`（新增 `upstream-runtime-pin` job，并把 `docs/UPSTREAM-RUNTIME.md` 加进触发路径；同时补上 `pull_request` 触发）。若认为这超出卡面，请在 `docs/architecture.md` 的 T-Runtime「触碰」行补上 CI 文件。
- `README.md`、`docs/architecture.md`、`docs/feature-parity.md` 与本文件正文里仍散落着硬编码的短 sha `97818b2`；**升钉时它们会与新钉点矛盾且当前无人检查**。升钉流程见 [UPSTREAM-RUNTIME.md](UPSTREAM-RUNTIME.md)，其中也列出了本卡的边界。
- 最后一批设置修改已通过 Bot 目录与联调入口的 Flutter 静态检查、3 项 Bot 界面/状态测试，以及 `BotSettingsPlugin.kt` 针对 Android 35 API 和 Flutter embedding 的独立 Kotlin 编译。Keystore 实际加解密、完整 MainActivity/Android APK 构建和真实模型调用仍未验证。
- 当前上游 `dsh_client/pubspec.lock` 与 `dart pub get --enforce-lockfile` 不完全一致，普通 `pub get` 可能修改锁文件；核对依赖变化，避免无关锁文件更新。上述测试使用真实上游 Bot 服务和临时数据目录，未启动模型 runtime，不能替代 Android 真机验证。当前开发环境曾通过 Flutter 自适应布局测试、Kotlin 设备分类单元测试和 vendored Bot 的 Node 测试；没有可用 Android 设备，未完成 fork APK 的完整验证。
- 2026-11-02 的 Android 构建尝试记录仍然有效：Gradle 依赖解析与 Flutter Gradle 插件编译已过，随后 Gradle daemon 意外退出；再试时进入应用 Gradle 配置并自动安装 NDK `28.2.13676358`，随后 `/tmp` 空间不足导致下载失败。**完整 Android 构建仍未通过**；不要把 partial NDK 目录当作已安装工具链，也不要把构建失败归因于已修改的 Kotlin 源码。

使用小而可验证的提交，维护本文件及功能清单，并及时推送私有仓库。不要提交 API Key、令牌、用户数据、构建缓存或临时上游 checkout。

`.github/workflows/bot-host.yml` 在相关源码推送后运行 Bot 适配层和 Dart 协议测试，并在钉点相关文件变动时 checkout 钉点 commit 跑上游网关 smoke。此 CI 不构建 Android、不启动完整 WebUI runtime；完整上游联调需要按前述命令单独运行。
