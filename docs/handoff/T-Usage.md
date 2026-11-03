# T-Usage 契约与界面交接（2026-11-03）

分支 `feat/t-usage`，基于 `feat/t-providers@4c88198`，包含此前三张卡的分支进展；不改 `main` / `dev/integration`，不建 PR。T-Providers 的真实 bridge、WebUI provider 同步和所有 Android 真机验收仍未完成，本卡不覆盖这些依赖缺口。

## 已做

- 参考固定上游 `UsageQuotaClient.getUsageQuota` / `WebuiUsageQuotaResult`，在宿主提供可注入的同形 `getUsageQuota({forceRefresh})` 能力。没有该能力时明确 signed out；真实 callback 失败则保持错误，不伪装成零消耗或未登录。
- `usage-quota.mjs` 只投影计划名、订阅类型、积分字符串及 5 小时/周窗口的有效百分比、重置时间、不限额标记。租约/Key/未知字段不透传，异常不回显凭据。
- 鉴权只读 `/api/usage-quota`、Dart `MiniMaxBotApi.usageQuota`、严格 UI DTO 和用量页；入口在「账号与模型」页顶栏，不与工作区占用混淆。
- 本机 native 目录当前全部是自定义 API provider（包括 MiniMax API Key），选中它们时显示「不支持」，不调用 MiniMax 账号额度路径，不会发送三方 Key。未连接本机账号服务显示失败/刷新；宿主确认无 OAuth 能力时显示未登录。
- 夹具账号能显示计划名、积分、5h/周百分比和重置时间；未知/非法数值显示缺失数据，不填 0。不做热力图、兑换、充值或登录流。

## 验证入口

```bash
cd packages/mbot_settings
flutter test test/usage_quota_test.dart
dart analyze
cd ../../dsh-go/app
DSH_TEST_FONT_DIR=/path/to/noto-cjk flutter test --no-pub test/bots/bot_usage_test.dart
flutter analyze --no-pub lib/bot test/bots/bot_usage_test.dart
cd ../packages/dsh_client
dart test test/minimax_bot_api_test.dart
cd ../../runtime/minimax-bot-host
node --test service.test.mjs usage-quota.test.mjs
```

本地已通过：4 项 quota DTO/页面测试、2 项 App 路由选择测试、3 项宿主额度投影测试，以及 service 的真实鉴权 HTTP quota 路由测试；后者使用模拟账号 callback 并断言租约字段被剔除。Dart API 的 Bot 服务测试覆盖 signed-out 返回。源码/宿主测试不能证明真实账号额度或 Android 可用。

## 仍未完成

完整 runtime 的 composition root 尚未组装，本轮没有实际构造上游 `UsageQuotaClient` 或读取任何真实 OAuth 租约。最终接入须将已绑定实例的 `getUsageQuota`（保留 `this`）交给 `startBotService`，其 tokenProvider 仍由可信宿主从 OAuth core 租约获取，不能从 API Key 推导、不能经 Dart 暴露租约。后续若 native provider 目录加入 OAuth 账号种类，必须更新 `BotUsageQuotaPort` 的「自定义 API provider」判断，不能按所有 provider 一律不支持。

本卡暂为可验证的 UI/契约准备，尚不能宣称真实 MiniMax 账号用量端到端已完成。T-Schedule/T-Plugins 也仍需后续实现，不因已有上游功能就标完成。
