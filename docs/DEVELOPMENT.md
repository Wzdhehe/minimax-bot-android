# 开发交接

## 产品边界

Android 本机多 Bot，参考 Grok Bot 布局及官方 MiniMax Bot 功能。用户在设置中输入 MiniMax 的 API Key、Base URL 和模型；模型服务需要联网，无需自建云或远控。手机固定竖屏，平板允许旋转并根据窗口宽度展开。不要加入手机 UI 操控或通用 Agent 工作台。

Mini App 是另一类可视化程序，不是插件，暂不适配通用 Mini App。官方 MiniMax Bot 上游以 Mini App 形式发布：仅复用其 Bot 业务逻辑，作为本应用的内置服务。MiniMax Code 的插件市场是另一个独立需求，尽可能实现，但不能假设 Mini App 可作为插件安装。详细目标和未完成项见 [功能清单](feature-parity.md)。

## 当前代码和真实进度

- `dsh-go/` 是 DSH Go 的 Android、Flutter 和本地 runtime 基线，已导入并保留许可；这是改造目标，但现有界面和引擎仍然是 DSH Go，不是完整的 MiniMax Bot 应用。远控相关源码、品牌及受限素材尚未全部移除，不能直接作为成品发布。
- `dsh-go/runtime/vendor/minimax-bot/` 固定官方 Bot 业务源码，MIT 许可。`packages/dsh_client/lib/src/minimax_bot_api.dart` 是对该服务的本机 HTTP 客户端，测试通过官方服务进行 Bot、群组、历史及事件的接口验证；这不意味着已集成 Android UI、模型引擎或插件市场。客户端只接受 loopback IP，并要求生产部署通过认证代理访问；目前还没有实现该代理。
- 已对 Android fork 的 manifest 收紧手机操控相关权限，并禁止启动相应通道；设备分类代码使小于 600 dp 的设备固定竖屏，平板遵从系统方向。Android 真机、折叠屏和完整 fork 构建尚未验证。
- 根目录 `app/` 是早期独立 Java 原型，**不是目标实现**；其应用内 shell 已禁用。不要将其已有能力误认为 fork 已完成的能力。
- DSH runtime 仍然是当前基线，尚未替换或接入 MiniMax Code/WebUI runtime，故目前不能使用官方插件市场。上游 WebUI 包含 `local-runtime-v2` 的市场、GitHub 插件预览/导入和启停等接口；本项目尚未接入、尚未验证 Android 插件兼容性，不能把 GitHub 下载等同于安全可用。
- 没有完成真正的隔离执行环境。PRoot 只提供兼容层，不是独立 Android UID 的安全沙箱；不要启用同 UID 任意代码执行作为替代。隔离和密钥设计见 [sandbox.md](sandbox.md)。

## 上游及授权

DSH Go 基线 `c1c0abb00907efdf48dcb443581686b67597c5a2`，AGPL-3.0，遵循 `dsh-go/AGENTS.md` 和其许可证、第三方声明及素材限制。MiniMax Bot 基线 `0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8`，MIT，见 `dsh-go/runtime/vendor/UPSTREAM.md`。参考 WebUI `mcode-webui/minimax-code` 的 `97818b2`，需在导入具体代码前核对其许可证及依赖。更多研究见 [research.md](research.md)。

## 下一位开发者的优先事项

1. 梳理 WebUI `local-runtime-v2` 与官方 Bot 所需 `context.agent` 的会话、流式事件、审批、模型及取消接口，制作明确的适配层；先在宿主机通过真实进程端到端测试，再处理 Android。
2. 将兼容的 MiniMax Code 运行时和插件管理服务打包到 Android，解决 Node 原生依赖、进程生命周期和 Android 架构支持；为本机服务实现防其他应用访问的认证。不要启动未隔离的工具执行。
3. 实现密钥安全存储和受控模型请求：用户 Key 不能直接暴露给可执行任意代码的 Bot/插件；将 Base URL、模型、密钥和调用错误接入设置。完善 Bot 服务客户端的审批、附件等能力。
4. 将 DSH Go UI 改成 Bot 列表、私聊和群聊，接入 Bot 服务及持久化；实现官方 Bot 的任务、记忆和定时等功能。再实现官方插件浏览、安装、启停和卸载，并验证实际插件兼容与权限边界。
5. 移除远控、手机操控及受限品牌素材，验证手机与多尺寸平板，最后进行真机、真实模型和后台中断恢复测试。按功能清单逐项验收。

### 运行时接口调查线索

固定版本的 WebUI 在 `packages/local-runtime-v2/src/application/session/process-local-application-contract.ts` 中已有 `miniAppAgent` 端口，提供 `run`、`models`、`reply`、`answer` 和事件回调；这与 Bot 的 `miniapp/node/engine.mjs` 对 `context.agent` 的需求接近，可以研究复用该端口或底层 gateway，而不在产品中提供通用 Mini App 功能。该端口要求可信 `caller`、运行代次和会话归属，不能直接伪造或忽略这些检查；需要先设计内置 Bot 的宿主身份并验证会话生命周期和取消行为。

市场操作入口位于 WebUI 的 `packages/local-runtime-v2/src/application/cli-management-application.ts`，包含 `listMarketplacePlugins`、`listInstalledPlugins`、`previewGithubPlugin`、`importGithubPlugin`、`installPlugin`、启停和卸载。研究市场需要同时核对 catalog 的登录要求、包格式、更新以及 Android 原生依赖和插件执行权限；当前这些均未打通。

## 开发验证

目标 fork 是 Flutter/Android 项目，需 Flutter、Android SDK 及 JDK；参考 `dsh-go/README.md`、`dsh-go/CONTRIBUTING.md` 和 `dsh-go/AGENTS.md`。宿主机 Bot 服务协议测试需要 Node.js 和 Dart：

```bash
cd dsh-go/packages/dsh_client
dart pub get
dart analyze lib/src/minimax_bot_api.dart test/minimax_bot_api_test.dart
dart test test/minimax_bot_api_test.dart
```

当前上游 `dsh_client/pubspec.lock` 与 `dart pub get --enforce-lockfile` 不完全一致，普通 `pub get` 可能修改锁文件；核对依赖变化，避免无关锁文件更新。上述测试使用真实上游 Bot 服务和临时数据目录，未启动模型 runtime，不能替代 Android 真机验证。当前开发环境曾通过 Flutter 自适应布局测试、Kotlin 设备分类单元测试和 vendored Bot 的 Node 测试；没有可用 Android 设备，未完成 fork APK 的完整验证。

使用小而可验证的提交，维护本文件及功能清单，并及时推送私有仓库。不要提交 API Key、令牌、用户数据、构建缓存或临时上游 checkout。
