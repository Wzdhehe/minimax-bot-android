# T-Schedule 交接（2026-11-03）

分支 `feat/t-schedule`，基于 `feat/t-usage@ce00692`，包含此前所有分支进展。`main` 和 `dev/integration` 保持不变，无 PR。

## 最新用户决定

参考用户 1000251070.jpg：**自动任务设置在 Bot 设置里**，Bot 也能自行设置任务，符合 mbot/mcode runtime。实现为「会话信息按钮 → Bot 资料设置 → 自动任务」；独立 Bot 通知开关受全局开关和系统权限共同限制。不增加图片里的电脑/远控入口，不复制 Grok 商标和图形。

全局 IA 改为七组，原 schedules 枚举保留作模块标识，但不再列入 `MbotSettingsIa.groups`。旧 T-Settings handoff 中的八组描述是历史状态，已由本次用户决定覆盖。

## 实现

- `BotProfilePage`：Bot 名称、原创首字头像、名字/角色编辑，自己的任务开启数量、自动任务入口和通知偏好。没有电脑入口。
- `BotSchedulesPort`：从真实 Bot 服务读取，并只向当前资料页提供该 Bot 与它的任务。写入固定 botId；编辑/删除/运行前复查任务归属，避免界面误操作其他 Bot。这里是用户界面范围限制，不是替代服务端授权的安全边界。
- 设置包任务页复用既有 API：每天 HH:MM、间隔（至少 5 分钟）、一次（分钟后）；同一 `saveSchedule` 做创建/修改，支持启停、删除和立即执行。没有 Android Alarm/WorkManager/Ubuntu 定时器，不承诺被杀后执行或补跑。
- 官方 Bot 的 MCP `create_schedule` / `list_schedules` / `delete_schedule` 继续使用原 Team/Store 和调用者语义；宿主测试经 `/mcp/minimax-bot` 自建、列举、删除，并验证数据能从用户 state API 读取。普通 Bot 在声明自身调用上下文时，不能把 targetBot 指向另一个 Bot；Chief 上游语义保留。
- `BotController` 转发实时事件，Bot 页面监听真正的 `message.upsert` + `role=schedule`，不拿普通回复冒充到点事件。通知桥按消息 ID 去重，检查全局开关、schedule 分类和 Bot 开关后调用 native；不向通知正文传递任务 prompt。
- native 通过自己的通知渠道发送，先检查系统与渠道状态；点击只打开 App，没有后台拉活、权限自动弹窗或回调执行代码。文字明确是「已触发，打开查看状态」，不假装模型任务已成功完成。

## 已验证与待验证

- 宿主调度/MCP 测试：预置 due once 与 disabled task，真实 Bot 服务启动后调用模拟 Agent 一次；一次性任务移除、disabled 不运行。随后通过真实 MCP HTTP 操作验证 Bot 自建任务与 UI 同一数据源。没有执行真实 MiniMax 模型，也没有启动 mcode 实例来主动调用该工具。
- Bot 资料页、任务归属防误操作、创建与当前 Bot 绑定、启用数、每 Bot 通知偏好均有 widget/状态测试。通知桥测试覆盖总关、分类关、Bot 关、重复事件与 native 拒绝投递。
- native 通知插件独立 Kotlin 编译通过；生成 ARB 中英文字。**未完成完整 APK、真实 Android 权限/通知投递、系统结束进程和后台 Activity 生命周期验收。**

## 必须如实保留的缺口

1. mcode runtime 尚未打包/启动，MCP 服务的正式注册、调用者身份绑定和受控权限传递未完成。当前测试用声明的 caller_bot_id 验证上游工具语义，**不证明能防 Agent 伪造 caller**，也不能说真机 Bot 已能自主调度。不能把宿主 bearer 直接暴露给任意 Agent/插件。
2. 通知桥当前属于 Bot 页面/Flutter 进程的实时连接生命周期：连接断开或页面不存在时不会补发。独立宿主到 native 的通知桥、App 冷启动前的 due 事件等仍需生产装配；不宣称「只要 Node 在，任意后台状态都保证通知」。
3. 同 UID 执行和 Key 隔离仍受 T-Sandbox/T-Providers 未完成项限制。不能用这些 GUI 和模拟测试宣称完整 Agent 沙箱可用。
4. 需在目标 Android 设备上验收进程存活时通知、关闭总开关不发、系统渠道禁用、进程被杀后不执行，并复跑完整测试。本卡保留未完整验收状态。

继续开发时优先完成剩余装配与设备验收，再处理插件市场 UI/Android 包策略。最新改动均在此分支链，其他 Agent 接手应 checkout 最新分支而不是仍未改动的 main。
