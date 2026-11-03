# 最新接手入口（2026-11-03）

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
  → feat/t-plugins（本轮最终提交，git log / ls-remote 获取精确 HEAD）
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
7. [T-Plugins](handoff/T-Plugins.md)：插件页和只读 mcode adapter、GitHub 固定版本预览、协议字段/installed cursor 差异。**生产变更入口强制关闭，当前无正式运行时 descriptor，非已完成安装市场。**

关键现实：默认正式启动器仍是 DSH 基线。`lib/dev/minimax_bot_main.dart` 只是 UI 联调入口。没有成功构建完整 fork APK，没有真实 Android 设备，不能说 App 已写完/模型已跑通/完整沙箱可用。

## 接下来优先做

1. 阅读 `docs/architecture.md` 卡与 `docs/sandbox.md`，先落实独立 UID + broker 的 Android 最小实证、Node/runtime 原生依赖与宿主生命周期；禁止回退同 UID 任意 shell。
2. 打包/启动钉点 runtime，可信 descriptor 私有传递；接 provider native broker、配置/模型同步，真实模型流和取消。API Key 不传入 Agent/插件环境。
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
- `feat/t-plugins` 推送后会触发 settings/scope，最终状态待收尾实查并补记；不要默认成功。以上 CI 均不构建 Android APK。
