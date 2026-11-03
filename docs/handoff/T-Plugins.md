# T-Plugins 界面与只读适配交接（2026-11-03）

分支 `feat/t-plugins`，基于 `feat/ui-motion@f04ce36`，包含前面全部功能分支的代码。整卡未完成，不要将此阶段描述为 Android 插件市场已可安装运行。

## 本阶段范围

- `mbot_settings` 增加插件页：已安装/官方市场、搜索（300 ms 合并）、按需构建列表、初次加载骨架、市场 cursor 翻页、GitHub 固定 commit 预览。区分未连接、读取失败、确实为空、更多但暂无法翻页。切换列表的旧响应不会覆盖新页面。
- 展示投影只读取 name/displayName/description、source、enabled、installExists、installationPolicy 和能力数量；不把 `configJson`、MCP 环境变量/命令、Token 或整包 payload 搬进设置状态。身份和固定 commit 不能截断后使用。
- GitHub 预览显示官方解析出的完整 40 位 commit、仓库、子目录和 stdio MCP 提示；`canImport` 并不等于 Android 兼容。含 Mini App 的插件仅显示能力提示，不适配其可视化程序。
- UI 的安装/卸载/启停/导入流程可用测试 port 演练；每次操作等待实际回答，不做乐观成功；超时后不自动重试，关闭变更入口直到刷新核对。这个 port 能力位只是 UI 提示，不能充当执行安全边界。
- App `BotPluginsPort` 复用已有 `McodePluginsApi`（四种变更协议原本已具备）；对接官方市场和已安装列表/预览。**实际 App 没有可信启动 descriptor，默认显示未连接；即使手工注入 API，生产 adapter 的所有变更方法仍强制拒绝。** 没有伪造安装结果，也没有同 UID shell、Ubuntu 或 pnpm 安装链。

## 核对钉点上游发现的问题

实际读取 `mcode-webui/minimax-code@97818b2890b4acae1c6bd1200a226ff506680628`：

1. `packages/protocol/src/local.ts` 的 `ListInstalledPluginsInput` 用 cursor，结果含 nextCursor/hasMore。
2. `packages/webui/src/server/operation/plugin-management.ts` 的 installed input 只允许 keyword/limit/offset，额外 cursor 被拒绝。
3. `packages/local-runtime-v2/src/service/plugin-system/plugin/runtime/desktop-facade.ts` 的 `listInstalledPlugins` 读取 req.cursor，不读取 req.offset。

因此当前 adapter 不把 installed nextCursor 暴露成可用翻页键，保留 hasMore 并显示限制；不会重复拉第一页假装加载更多。市场 cursor 正常传递。这是待上游修复/正式宿主接入处理的接口差异，不修改钉点、不临时篡改上游快照。

## 验证与未完成

新增包测试覆盖只读/未连接、丢弃配置字段、身份与 commit 校验、旧列表响应、变更超时不重发、刷新核对、固定 GitHub 预览和只读导入禁用。App 测试覆盖真实 hub 入口、市场 source/cursor、installed 翻页限制和生产变更拒绝。最终命令结果以 [总交接](../HANDOFF.md) 为准。

**尚未完成**：Android runtime 打包/启动与可信 WebUI descriptor 注入、真实市场账号租约、Android 包兼容策略、独立 UID/broker 与执行权限强制、真实插件安装/卸载/启停、完整 APK/真机。不能只打开 canMutate 或绕过 adapter 就宣称完成：需要宿主独立校验策略。没有本轮真实 WebUI runtime 全量启动；已有 `mcode_plugins_runtime_test.dart` 需要设置已构建入口才能执行。
