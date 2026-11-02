# T-Sandbox 交接

基线：`origin/dev/integration` 的 `891aefa71c5aea952ec8aa2502c2ada854854042`。本卡分支 `feat/t-sandbox`，不修改 `main`，不创建 PR，不重写历史。

## 实现

- MIT 守卫新增任务卡规定的 `resolveWorkspacePath(root, userPath)` 严格入口：拒绝空/纯空白、NUL、任何绝对路径、`..` 段、反斜杠和盘符。既有内部绝对路径 API 保持兼容；修复悬空 symlink 被误判为普通缺失路径的漏洞。
- `workspace.mjs` 根据可信 `filesDir` 固定创建 `workspace/`，拒绝重定向的工作区根；宿主文本读写必须先过守卫。`startBotService` 现在要求 `filesDir`；正式 Android 调用方必须由 `Context.getFilesDir()` 提供，不能从网络请求指定。正式 Android launcher 尚未实现，这个接入点不能算作 launcher 已完成。
- 守卫接到真实 `/api/media` 读取点。即使已鉴权且 Bot 在消息里提及了外部文件，也不能读取它；合法工作区相对路径仍能下载。为此增加最小 vendor hook，修改记录在 `runtime/vendor/UPSTREAM.md`。宿主状态/头像/用户上传仍由宿主自己的私有 dataDir 管理；它们不是 Agent 的任意路径文件工具。
- 删除 FileProvider 的整片外部存储映射，增加私有 `workspace/` 映射，保留原有 rootfs/cache 映射以免扩大本卡到旧模块删除。
- `targetSdk = 36`，去掉过期 target 的 lint 豁免，补齐语音服务包可见性查询。依据 [Google Play 要求](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)（2026-11-02 实查）：2026-08-31 起新应用和更新要求 API 36；不能沿用根原型的 35。
- 继续 `allowBackup=false`，同时用 legacy `fullBackupContent` 和 Android 12+ 的 `dataExtractionRules` 排除 `minimax_bot_provider.xml` 及 legacy runtime 数据；云备份和设备迁移均覆盖。多供应商若换 prefs 文件名，T-Providers 必须同步规则。
- 新文件保留对应 MIT/AGPL 标识；新增 CI 入口覆盖守卫、宿主回归和 Android 源码策略检查。

## 本地验证（Node 24.19.0）

```bash
cd packages/mbot-workspace-guard && npm test
# 10 pass：原有 8 项 + 严格输入/悬空链接

cd dsh-go
node --test runtime/minimax-bot-host/agent-port.test.mjs runtime/minimax-bot-host/service.test.mjs runtime/minimax-bot-host/workspace.test.mjs
# 5 pass：含真实 HTTP 媒体路由、授权、私有文件读写和根目录替换
node --test runtime/minimax-bot-host/upstream-runtime-pin.test.mjs
# 19 pass：未改 runtime 钉点
python3 -m unittest discover -s app/test_scope -v
# 4 pass：仅源码策略，非合并后的 manifest 或设备行为
```

## 真实 GitHub Actions

提交 `4286799` 推送后，已通过 GitHub API 实查：

- [Bot host and protocol checks](https://github.com/Wzdhehe/minimax-bot-android/actions/runs/41548601872)：`completed / success`，包括 Node 24 守卫/宿主测试、Dart 客户端检查，以及固定上游 checkout 的钉点校验和真实网关 smoke。
- [Bot fork scope checks](https://github.com/Wzdhehe/minimax-bot-android/actions/runs/41548601901)：`completed / success`，包括四项 Android 源码策略检查。

这补上了旧 handoff 中「没有观察过真实 runner」的验证空白，但依然不是 Android 构建或设备验证。

## 尚未验证 / 卡片未完整验收

当前环境没有 Android SDK、adb 或连接设备，未进行完整 Android 构建、安装、备份迁移或仪器测试。此前 Gradle/NDK 阻塞记录仍保留，不能宣称已经修复。已提供仪器用例 `ExternalStorageBoundaryTest`：shell 在 `/sdcard/Download` 写入受控的非媒体哨兵文件，App 在无 SAF 授权时读取必须失败，并清理哨兵。需要 Android 11+ 真机/模拟器，命令：

```bash
cd dsh-go/app/android
./gradlew :app:connectedArm64DebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ccch1mneyyy.dsh_app.bot.ExternalStorageBoundaryTest
# x86_64 模拟器可改 connectedX64DebugAndroidTest
```

仪器依赖/用例本轮尚未编译或运行。API 36 的合并 manifest、通知/前台服务与存储兼容行为也需要设备回归。target 提升会让旧 DSH 的 app-private 二进制执行受新系统限制；不为它恢复旧 target，也不以手机内 Ubuntu+pnpm 或同 UID shell 绕过。该执行链本就不属于本产品的新运行时方案。

路径守卫不是系统沙箱：仍不保证对抗并发目录替换（TOCTOU）或 hardlink，也未隔离 UID、网络和密钥。正式执行器只能通过受控 broker 获取能力，不能给任意 Agent/插件直接 `fs`/shell 后仍声称路径策略可强制。当前真实强制点是宿主文件接口和媒体读取；完整 runtime/工具系统尚未接入，不能用这张卡宣称其已经被隔离。

下一步先补 Android 构建及 `/sdcard` 仪器验收，审查本分支后再合入 `dev/integration`；随后按 T-Settings → T-Providers → T-Usage → T-Schedule → T-Plugins 继续。本轮未处理散落的上游短 sha 文案自动扫描，那是 T-Runtime 遗留的独立事项。
