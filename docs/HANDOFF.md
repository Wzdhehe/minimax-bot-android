# 最新接手入口（2026-11-03）

继续 **feat/t-plugins**。用户已授权持续推进、每个可验证阶段更新文档并 push。不新开分支、不动main/dev/integration，不force push、不建PR、不发布APK。

## 当前状态与证据

- **Node动态请求JNI已编译（设备待CI）**：linked binding返回Promise，libuv worker→隔离Kotlin→固定UID/路由native Binder→Unix模型FD；带取消在先检查。Key/endpoint/native token/RPC session不进入Node；64KiB上限，大请求FD待做。JNI/SQLite重编成功，两个APK **BUILD SUCCESSFUL in 1m46s**，host1+输入拒绝8过。新增设备断言待CI，正式启动器仍未装配，见T-AndroidNode。
- **SQLite设备基线已过**：bc07b1c/run41663798958的API26/35各1项用例通过（0失败/错误/跳过），实读日志确认SQLite源码构建和隔离内存数据库检查均执行。不是持久化/WAL/真机，见T-SQLite。

- **动态模型Binder组件已编译**：隔离端ModelRequestClient→NativeModelRequestHost→native Unix FD，绑定实测UID/单路由/会话/peer生命周期；64KiB首版body限制、取消在先/晚到FD、关闭与路由检查。Gradle组件 **BUILD SUCCESSFUL in 2m 31s**，JVM32项通过；新增设备用例待CI。**Node JNI async binding后续已编译待设备CI，生产启动器仍未接；大请求FD协议未做**。见T-RuntimeBroker首节。

- **SQLite Android验收候选已编译**：钉点better-sqlite3 12.11.1源码→NDK27d→x86_64 addon；固定隔离Node脚本加内存事务/回滚/中文/JSON/序列化恢复，两个仪器APK最后 **BUILD SUCCESSFUL in 43s**，host1项+输入拒绝8项过。**SQLite设备结果见上方最新基线；持久化/WAL/ARM64/真机未验证**。见 [T-SQLite](handoff/T-SQLite.md)。

- **四类真实pi-ai provider组件与CI已过**：不改钉点源码，验证commit/tree/洁净和SDK依赖后打包provider，8项工具翻译/取消测试通过；Google用AsyncLocalStorage model fetch scope解决无fetch注入点的问题。dispatcher仅在测试子进程安装，生产启动器仍未接。实际body快照由Kotlin消费，确认四类请求通过native白名单和认证。最新完整脚本JUnit32+Node41；另SDK9项、pi-ai8项；7ce84ec的native41659981145/host41659981258/scope41659981166全部success。见T-RuntimeBroker。

- **SDK→模型FD适配已实现（宿主组件）**：新增有界、无网络fallback的 `createNativeModelFetch`，固定provider/model/SDK URL，只传IDs+JSON到native通道工厂，丢弃SDK认证头。流式背压、取消、总截止、晚到FD关闭、协议event恢复已测试；实际OpenAI6.26.0两种协议和Anthropic0.91.1共9项SDK测试通过。完整脚本JUnit32+Node37通过；不是完整pi-ai/mcode，也未实现生产动态Binder/JNI请求桥。Google宿主provider接入已在下一阶段验证，生产装配仍待做。见T-RuntimeBroker和 `dsh-go/tooling/model-sdk-probe/README.md`。

- **native模型broker支持SDK请求体**：兼容原messages路径，新增互斥request路径。四类provider的工具schema、tool call/result、采样/思考/缓存参数保留；模型白名单、HTTPS endpoint、认证头和Key仍由native决定。拒绝未知顶层字段、URL/headers/Key/model覆盖；不是工具执行器，也不是mcode已接通。
- 恢复环境后实跑 **JUnit32 + Node31 + provider/service10 + scope4** 全过；Android组件 `testDebugUnitTest assembleDebug assembleDebugAndroidTest` **BUILD SUCCESSFUL in 5m 29s**，XML32项零失败/错误/跳过。跨进程工具请求经过真实Kotlin/OkHttp和假上游；假Key在native生成，检查descriptor/env/log/Agent文件不泄露。见 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)。
- **Android已观察基线**：a094a85/run41622547971，API26/35各10项通过（Keystore、独立UID文件/网络拒绝、Unix FD流/取消/容量/截止、IPC生命周期）；04d7e97/run41627233229，两版本均通过真实Node24/JNI固定脚本、模型FD与Key边界。都是组件/模拟器和假provider，**物理真机、硬件Keystore、真实模型、完整Bot未验证**。
- **full-ICU源码CI进行中**：4df803e/run41630373256仍在build，不能记为通过。默认预编译候选没有Intl，不能直接启动官方Bot。见 [T-AndroidNode](handoff/T-AndroidNode.md)。
- 正式入口仍为DSH。插件变更保持关闭。better-sqlite3仅编译了内存验收候选；隔离持久化、正式启动器、可信Bot身份/MCP尚未装配。

原先逐轮“最新/待观察”已移到 [过程记录](handoff/RuntimeBroker-2026-11-03-history.md)，旧失败与未验证描述不代表当前结论。

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

1. 观察full-ICU源码CI run41630373256，处理真实失败并取得API26/35证据；观察新SQLite API26/35矩阵，再解决隔离UID数据持久化。目录FD/cwd不自动授予主App路径下SQLite/WAL创建权限，禁止回退同UID任意shell。
2. 接钉点mcode SDK到native-only模型FD：请求体已扩展，fetch/SSE adapter及宿主SDK探针已过，已有native动态Binder组件，下一步是JNI异步open/cancel与Node Promise、生产模型scope装配；四类pi-ai宿主provider现已通过，完整runtime仍待验证。**Google的钉点pi-ai实现不使用options.fetch**，宿主已验证async scope dispatcher，正式隔离进程还须显式安装并绑定生命周期。正式runtime、配置同步、工具执行和真实MiniMax未验证。参看T-RuntimeBroker和T-AndroidNode。
3. 注册内置 Bot 的可信身份/MCP/会话归属，再验收 Bot 自主创建任务和后台通知。现 MCP 测试传 caller_bot_id 不证明防伪造。
4. Android 安装策略定稿并由宿主强制后，才逐步开放插件变更；先处理 installed cursor 协议差异。不会因为 UI 测试能启停就开放实际执行。
5. 总工作区下实现项目管理与 Bot 绑定，遵守路径防护并分开 cwd 和访问许可；补资料库、附件、群聊/记忆/审批等全量 parity 未完成项。
6. 完整 APK、真机权限/Keystore/通知/后台/手机竖屏/平板横屏及 profile 性能验收。没有设备时如实标未验证。

## 本环境验证工具

环境曾中断，恢复后 `/workspace` 保留，`/tmp` 清空并部分重建。路径须实查。

- Node24.19.0；Kotlin `/workspace/toolchains/kotlinc/bin/kotlinc` 2.4.0；Flutter `/workspace/toolchains/flutter/bin/`。
- 当前组件构建：完整JDK `/tmp/mbot-jdk21`、Gradle9.3.1 `/tmp/mbot-gradle/gradle-9.3.1/bin/gradle`、SDK `/tmp/mbot-android-sdk`（platform36/build-tools36.0.0）；缓存 `/tmp/mbot-gradle-cache`。
- 固定测试jar `/tmp/mbot-native-deps` 已按CI摘要重取。本轮又恢复 `/tmp/mbot-ndk27d` x86编译器/sysroot/clang runtime及 `/tmp/mbot-node24-mobile.zip`，已重建JNI/SQLite；没有adb/设备/KVM，full-ICU Node源码编译在CI。
- Widget测试需 `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts`；只暂存明确路径，检查pub/analyze产生的无关修改。
- 完整命令见T-RuntimeBroker；组件APK编译不是完整Flutter Bot APK。

## 最终验证结果

- 最后完整设置包测试 **27 passed**；包括 4 个 motion 和 5 个插件测试。插件初轮发现测试 tooltip 定位错误和预览期间加载动画未停导致 settle 超时，均修正后完整通过。
- 最新插件 App 适配/入口 + 设置回归 **3 passed**；此前动画/会话/资料/通知/额度相关 **12 passed**，最终 UI 改动后相关子集 **7 passed**。
- mcode 插件协议 **4 passed**；协议客户端、协议测试和 runtime 测试文件静态分析通过。**本轮未设置 MCODE_WEBUI_ENTRY，没有跑真实 WebUI runtime 测试。**
- App `flutter analyze --no-pub lib/bot test/bots/bot_plugins_test.dart` 与设置包 `dart analyze` 无问题；最后对 cursor/subPath 补拒绝截断的校验并复跑插件测试。
- 已实查成功的 GitHub Actions：T-Schedule host **41554914121** / settings **41554913845** / scope **41554913796**；UI-Motion settings **41556055349** / scope **41556055408**。UI-only 提交没有触发 host workflow，不编造对应 run。
- `feat/t-plugins@f0d93cb9ed8fbc4254fefd476505793ef10c683a` 已推送并通过 ls-remote 确认。scope **41557103632 success**；settings **41557103607** 截至收尾实查仍在准备 Flutter，**尚不能算通过**。下一位运行 `gh run view 41557103607` 查看结论；以上 CI 均不构建 Android APK。后续仅文档提交不改变已测试代码。
