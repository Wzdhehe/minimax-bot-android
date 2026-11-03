# T-Providers 阶段一交接（2026-11-03）

**当前源码更新**：本文保留最初阶段记录。后续同一 `feat/t-plugins` 分支已实现 Kotlin native model broker，并**删除** Node probe 的 `resolveProvider/apiKey/fetch` 旧方案。现在 `createProviderBroker({ modelBroker })` 与 `startBotService({ modelBroker })` 只走 native 流，Node 不拿 Key；详见 [T-RuntimeBroker](T-RuntimeBroker.md)。不要按下文旧 probe 描述恢复 Node 读 Key。正式 Android 启动、provider/model 同步及设备验收仍未完成。

分支 `feat/t-providers`，基于 `feat/t-settings@1e4b97e`，包含之前已推送的 T-Sandbox/T-Settings 进展。`main` 与 `dev/integration` 保持不动，无 PR。**整张 T-Providers 尚未完成，不能把本阶段当作 runtime 已接通。**

## 已实现

- `BotProviderStore.kt`：最多 100 个供应商，各有名称、协议、HTTPS Base URL、模型列表、enabled；每个 Key 使用独立 AndroidKeyStore alias、AES-GCM 和绑定 provider ID 的 AAD。元数据、密文和 IV 均在 `minimax_bot_provider` 同一私有 prefs 文件中，继承 T-Sandbox 的备份排除。
- 首次使用新目录时，旧单供应商 Key 在 native 内解密并重新加密；原密文与旧元数据在同一 prefs commit 中删除，避免用户删除新供应商后仍遗留可读取的旧 Key 副本。旧单 Key 的 Dart 页面已不从产品 hub 导航，保留文件尚未归档。
- `minimax/bot-settings` 新增 provider 列表/保存/删除/默认模型方法。读取只回元数据、`hasApiKey` 和固定掩码；没有暴露 `readKeyForBroker`。用户新输入的 Key 会经表单送 native，但保存后绝不从 native 回给 Dart。
- `mbot_settings` 新增供应商列表与编辑器，支持四种与 WebUI 钉点一致的 `apiFormat`。选择默认模型目前只是本机元数据；编辑模型或停用供应商会清理无效默认值。增删改与默认选择的 widget 测试通过。
- 修复弹窗退场动画尚未结束就释放输入控制器的竞态，并同步修复 Bot 创建弹窗的同类问题。
- `provider-broker.mjs` 提供 host-only probe：可信 resolver 解析 provider，再在 HTTPS 请求时注入 Key；禁止重定向，限制响应大小与请求时间，不回显服务响应和异常。四种协议有模拟请求验证。没有新增同 UID shell 或把 Key 写入 runtime config/env。
- 鉴权 `/api/providers/test` 只接受 `providerId`/`modelId`，拒绝附带 Key。真实 HTTP→broker→模拟 provider 的链路测试通过；未装配 broker 时返回 unavailable。Dart Bot API 接入该调用；从 Bot 页面进入设置时显示模型测试项，未就绪有明确提示，从旧外壳无 Bot API 的入口不显示测试项。

## 通过的本地检查

- `packages/mbot_settings` 全套 Flutter 测试 14 项、`dart analyze` 通过。
- App Bot 目录静态检查通过；既有 Bot/设置 hub 的 4 项测试通过。
- `dsh_client` 的 Bot HTTP 测试包含 broker 未就绪响应，需按当前分支复跑见下方命令。
- Node provider broker 6 项、服务鉴权与实际 HTTP probe 2 项通过；工作区/Agent 既有测试同时复跑通过。
- `BotSettingsPlugin.kt` 与 `BotProviderStore.kt` 对 Android API 36 + Flutter embedding jar、JDK 21、JVM target 17 的独立 Kotlin 编译通过。不是完整 MainActivity/APK 编译，也不是 Keystore 真机验证。
- 增加 `ProviderStorageTest` 仪器源码，验证目录不含明文、密文存储、host-only 解密、清除 Key 和默认模型失效；使用独立的测试 prefs 名，不覆盖用户真实 prefs。**该仪器测试尚未编译或运行。**

```bash
cd packages/mbot_settings
flutter pub get && flutter test && dart analyze
cd ../../dsh-go/app
DSH_TEST_FONT_DIR=/path/to/noto-cjk flutter test --no-pub test/bots/minimax_bot_home_test.dart test/bots/bot_settings_hub_test.dart
flutter analyze --no-pub lib/bot
cd ../packages/dsh_client
dart test test/minimax_bot_api_test.dart
cd ../../runtime/minimax-bot-host
node --test agent-port.test.mjs service.test.mjs workspace.test.mjs provider-broker.test.mjs
```

## 下一步必须补齐

真实 CI 补记：`4c88198` 的 [宿主/协议检查](https://github.com/Wzdhehe/minimax-bot-android/actions/runs/41551612068)、[设置集成](https://github.com/Wzdhehe/minimax-bot-android/actions/runs/41551612131) 和 [源码 scope](https://github.com/Wzdhehe/minimax-bot-android/actions/runs/41551612320) 已通过 GitHub API 实查为 completed/success。仍不含 Android APK/Keystore 仪器验收。

1. native→可信宿主的私有 bridge 尚未实现。当前 broker 的 resolver 在测试中为模拟函数；不能通过新增 Flutter「读 Key」方法、写明文 YAML 或将 Key 塞进 Agent 环境来接通。
2. WebUI BYOK provider/model 管理、模型 ID 映射与实际会话 resolver 尚未接入。本机生成的 provider UUID 不能直接当成 WebUI 的 `custom_provider:<key>`；需要明确映射。全局默认值也尚未同步到各 Bot。
3. 新目录当前只覆盖自定义 API provider；MiniMax OAuth 账号租约不在这里，不能拿 API Key 充当 T-Usage 的登录态。高级自定义请求头、OAuth 编辑和模型发现未实现。
4. 没有真实 API 调用/费用测试；HTTP probe 仅用假 fetch 和假 native resolver 验证，不证明完整 runtime。不要将表单保存或 probe 协议通过描述成模型对话已可用。
5. Android 完整构建、升级迁移、实际 Keystore、系统权限、T-Sandbox 的 `/sdcard` 仪器验收仍待补。当前无设备。

本阶段不进入下一张卡的产品验收；可独立准备 T-Usage 的 signed-out/unsupported 契约，但不能掩盖上述依赖缺口。
