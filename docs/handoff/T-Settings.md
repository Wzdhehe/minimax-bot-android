# T-Settings 交接（2026-11-03）

分支 `feat/t-settings`，基于 T-Sandbox 的 `a1f9738`。该分支包含此前的守卫/存储实现，但其 Android 仪器验收仍待补；不修改 `main` 或 `dev/integration`，不创建 PR。

## 实现

- App 通过 path dependency 复用根 `packages/mbot_settings`，从 Bot 顶栏和旧外壳 SettingsScreen 都可进入新设置 hub；此新入口不要求启动 DSH 引擎。
- 八组保持 IA 顺序。账号与模型接原有 Keystore 设置页（尚是单供应商、尚未联通 runtime）；通知和后台打开实页，其余占位保持可见。hub 支持由产品注入页面，后续 T-Providers/T-Plugins 可按组接入。
- 新写的 `MbotSystemSettingsPlugin` 与 Dart `BotSettingsPlatform` 使用 `mbot/system-settings` 通道，不调用旧 DSH 通道。只检测当前 App 的通知与电池优化状态，创建五个 mbot 通知渠道，以及响应用户点击后打开系统设置；不申请后台拉活、不自动弹权限、不获取其他 App 状态。厂商页保留通用指导文案。
- 总通知开关关闭时分类和渠道入口灰显，设置持久化到私有偏好。修复保存失败仍发布「已改成功」状态的问题；失败时保留旧值并提示。通知页返回前台会刷新系统状态。
- 修正旧文案：关闭通知只影响提醒，不会停止仍存活的调度进程；只有进程结束才会令进程内定时任务停止。没有承诺进程被杀后仍执行。
- 新增设置 CI，覆盖包与 App hub 集成测试。App 锁文件仅增加 `mbot_settings` 的 path dependency，没有升级其他依赖。

## 验证

- `cd packages/mbot_settings && flutter test`：原有 11 项通过；新增 `test/prefs_failure_test.dart` 单独运行，1 项通过。
- `cd packages/mbot_settings && dart analyze`：通过。
- `cd dsh-go/app && flutter analyze --no-pub lib/bot lib/ui/settings/settings_screen.dart test/bots/bot_settings_hub_test.dart`：通过。
- `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts flutter test --no-pub test/bots/minimax_bot_home_test.dart test/bots/bot_settings_hub_test.dart`：4 项通过，包含新 hub 不依赖引擎启动、八组存在、关闭通知后分类灰显及进入后台页不自动打开系统设置。
- `MbotSystemSettingsPlugin.kt` 以 Android API 36 + Flutter embedding jar、JDK 21、JVM target 17 做独立 Kotlin 编译：通过。不是 APK 构建，未编译整个 MainActivity。

真机上的通知渠道、系统设置跳转、重建 Activity 和权限表现未验证；完整 APK/运行时接入也未验证。新增 CI 的真实 runner 结果需推送后观察。新 hub 没有远控/手机操控入口；旧 DSH 全部入口的关闭与模块删除仍是架构文档里的后续独立事项，不能说整个旧外壳已清理完成。

## 后续

继续 T-Providers：将账号与模型页扩展为多供应商，保留 Key 仅 native 可读；Dart 只能读取元数据与 hasKey/掩码。尚未实现模型 broker 或把 Key 注入 MiniMax runtime，不要通过把 Key 写入 Agent 可读配置或环境来绕过。
