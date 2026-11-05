<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash：旧 DSH Bot UI 与官方 mbot（MiniMaxBotApi）接口对照

本报告只记录**读源码得到的事实**：旧 DSH Bot UI 实际调用了哪些方法、读了哪些字段，以及官方 Bot 服务（`MiniMaxBotApi`）**实际存在**哪些对应方法与事件字段。不含实现，也不含任何推测出的接口。

- 旧 DSH 侧源码：[`dsh-go/packages/dsh_client/lib/src/dsbot_api.dart`](../../dsh-go/packages/dsh_client/lib/src/dsbot_api.dart)（`DsBotApi`）、[`dsh-go/app/lib/state/bots_state.dart`](../../dsh-go/app/lib/state/bots_state.dart)、[`dsh-go/app/lib/ui/bots/`](../../dsh-go/app/lib/ui/bots)
- 新 mbot 侧源码：[`dsh-go/packages/dsh_client/lib/src/minimax_bot_api.dart`](../../dsh-go/packages/dsh_client/lib/src/minimax_bot_api.dart)（`MiniMaxBotApi`）、[`dsh-go/app/lib/bot/`](../../dsh-go/app/lib/bot)、[`packages/mbot_settings/lib/`](../../packages/mbot_settings/lib)
- 服务端真值：[`dsh-go/runtime/vendor/minimax-bot/miniapp/node/server.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/server.mjs)、[`team.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/team.mjs)、[`store.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/store.mjs)、[`engine.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/engine.mjs)

## 0. 事实分级

全文用三个等级标注，不混用：

| 等级 | 含义 |
|---|---|
| **源码有页面** | 仓库里存在该 UI 与它调用的方法 |
| **已连真实服务** | 该路径会真的请求一个在跑的服务，并按它的回答渲染 |
| **设备验证** | 已在真机或模拟器上跑过并留下可查证据 |

本轮**没有做任何设备验证**，也没有改任何 UI 源码；下文凡标「设备验证：无」即表示本报告未提供该证据。

## 1. 用户当前能进入的 Bot 入口

产品里存在**两套互不相通的 Bot 界面**：

| 界面 | 入口 | 依赖的 controller / API | 源码有页面 | 已连真实服务 | 设备验证 |
|---|---|---|---|---|---|
| 旧 DSH Bot | 功能目录项 `ds-bot` → [`BotsPage`](../../dsh-go/app/lib/ui/bots/bots_page.dart)（注册于 [`feature_catalog.dart`](../../dsh-go/app/lib/ui/features/feature_catalog.dart)）；会话抽屉 [`sessions_drawer.dart`](../../dsh-go/app/lib/ui/shell/sessions_drawer.dart) 与 [`drawer_row.dart`](../../dsh-go/app/lib/ui/bots/drawer_row.dart) 也各有一处 | [`DsBotProvider`](../../dsh-go/app/lib/state/bots_state.dart) → `DsBotApi.of(client)` → DSH 引擎里的 DS Bot 插件 | 是 | 是（依赖 DSH 引擎在跑；这是当前 Android 基线的既有路径） | 本轮无新证据 |
| 新 mbot Bot | **产品 App 里没有入口**。只有 dev 入口 [`dsh-go/app/lib/dev/minimax_bot_main.dart`](../../dsh-go/app/lib/dev/minimax_bot_main.dart) 能到 [`BotHome`](../../dsh-go/app/lib/bot/bot_home.dart) | [`BotController`](../../dsh-go/app/lib/bot/bot_controller.dart) → `MiniMaxBotApi`，端点来自 [`BotRuntimeEndpoint`](../../dsh-go/app/lib/bot/bot_runtime_endpoint.dart) | 是 | **否**：生产代码里**没有一处**调用 `BotRuntimeEndpoint.publish(...)`，native 侧不会报出活服务，dev 入口只会显示「No Bot service is running」 | 无 |

已连真实服务（服务端侧）：官方 Bot 的 JSON 服务本身在 host 上被真实跑通过 —— 钉住 fork factory + 官方 Bot 服务的组合可冷启动/重开、鉴权后可建 Bot 并持久化（见 [`dsh-go/tooling/runtime-host-probe/`](../../dsh-go/tooling/runtime-host-probe)）。但**App 侧目前没有连到它的那一段**。

## 2. 旧 DSH Bot UI：实际使用的方法与字段

### 2.1 状态层（[`bots_state.dart`](../../dsh-go/app/lib/state/bots_state.dart)）

| 环节 | 实际调用 | 出处 |
|---|---|---|
| 建立 API | `DsBotApi.of(connection.client)`；HTTP 载体走 `DsBotHttpTransport`，否则探测 `DsBotRpcTransport` | `bots_state.dart`（`dsBotProvider`） |
| 握手 | `api.hello(version: AppInfo.version)` → `DsBotHello`（`api`/`apiMin`/`apiMax`/`pluginVersion`/`capabilities`/`boot`/`seq`/`revision`/`readOnly`/`memory`） | `DsBotApi.hello` |
| 拉全量状态 | `api.state(since:, modelsKey:, locale: 'zh-CN')` → `DsBotState` | `DsBotApi.state` |
| 事件流 | `api.feed()` → `DsBotFeed`（自动重连，backoff 0.5/1/2/5/10 秒） | `DsBotApi.feed` |
| 未读通知补齐 | `api.notices(boot:, since:)` → `DsBotNotices` | `DsBotApi.notices` |
| 头像 | `api.avatar(botId)` → `{ref, image}` | `DsBotApi.avatar` |
| 单 Bot 聊天历史 | `api.chat(botId)` / `api.chat(botId, cursor:)` → `DsBotChatPage`（`sessionId`/`items`/`cursor`） | `DsBotApi.chat` |
| 群聊历史 | `api.roomChat(roomId)` / `api.roomChat(roomId, cursor:)` → `DsBotRoomPage`（`roomId`/`items`/`cursor`/`running`/`speaking`） | `DsBotApi.roomChat` |
| 发消息 | `api.send(conversationId, text)`（Bot 传 botId，群传 roomId） | `DsBotApi.send` |
| 记忆 | `api.memory(botId:/roomId:)` → `DsBotMemoryView` | `DsBotApi.memory` |
| 用量 | `api.usageSummary(botId)` → `DsBotUsageSummary` | `DsBotApi.usageSummary` |

### 2.2 页面层（[`dsh-go/app/lib/ui/bots/`](../../dsh-go/app/lib/ui/bots)）

| 功能 | 页面 / 组件 | 实际调用 | 实际读的字段 |
|---|---|---|---|
| Bot 列表 | [`bots_page.dart`](../../dsh-go/app/lib/ui/bots/bots_page.dart) | 只读状态层 | `DsBotStatus.availability`/`team`/`hello`/`feedConnected`/`error`；`DsBotTeam.bots`/`rooms`/`mainBotIds`/`models`/`defaultModel`/`modelsKey`/`readOnly`；`DsBotLive.activity`/`bots`/`rooms`/`approvals`/`memoryReviews` |
| 建 Bot | [`new_bot_page.dart`](../../dsh-go/app/lib/ui/bots/new_bot_page.dart) | `createBot(name:, role:, instructions:, color:, model: '<provider>/<model>', effort:)` | 返回 `DsBot`（`id`/`name`/`role`/`instructions`/`color`/`shape`/`imageRef`/`model`/`appliedModel`/`effort`/`sessionId`/`parts`/`waiting`/`createdAt`/`createdBy`/`workDir`/`ink`/`modelRef`） |
| 改 / 删 Bot | [`bot_details_page.dart`](../../dsh-go/app/lib/ui/bots/bot_details_page.dart) | `updateBot(id, name:/role:/instructions:/color:/model:/effort:)`、`deleteBot(id)`、`setMain(id, main:)` | 同上 |
| 复制 Bot | [`duplicate_bot_row.dart`](../../dsh-go/app/lib/ui/bots/duplicate_bot_row.dart) | `duplicateBot(id)` | 返回 `DsBot` |
| 单 Bot 聊天 | [`bot_chat_page.dart`](../../dsh-go/app/lib/ui/bots/bot_chat_page.dart) + [`bot_chat_kit.dart`](../../dsh-go/app/lib/ui/bots/bot_chat_kit.dart) | `send` | `DsBotItem`（`kind`/`id`/`text`/`time`/`botId`/`name`/`color`/`event`/`code`/`role`/`roomId`/`images`/`duration`/`live`/`whole`）；`DsBotItemKind` = `user`/`bot`/`event`/`answer`/`divider`/`unknown` |
| 群里 @ 人 | [`bot_mention.dart`](../../dsh-go/app/lib/ui/bots/bot_mention.dart) | 无网络调用（纯文本规则） | 从 `DsBotTeam.bots` 取成员名 |
| 建群 | [`new_room_sheet.dart`](../../dsh-go/app/lib/ui/bots/new_room_sheet.dart) | `createRoom(members:, name:, admin:, greet:)` | 返回 `DsBotRoom`（`id`/`name`/`members`/`admin`/`mode`/`relayLimit`/`relayLimitDefault`/`pausedCallIds`/`notice`/`preview`/`createdAt`/`workDir`/`parts`/`paused`/`adminFirst`） |
| 群设置 | [`room_details_page.dart`](../../dsh-go/app/lib/ui/bots/room_details_page.dart) | `updateRoom(id, name:/notice:/mode:/admin:/add:/remove:)`、`deleteRoom(id)`、`stopMemberWork(roomId, botId)`、`answerApproval(id, allow:)` | `DsBotRoom`、`DsBotApproval`（`id`/`sessionId`/`botId`/`roomId`/`tool`/`reason`/`at`/`answerable`） |
| 群接续 / 限流 | [`room_chat_page.dart`](../../dsh-go/app/lib/ui/bots/room_chat_page.dart)、[`room_relay_limit_sheet.dart`](../../dsh-go/app/lib/ui/bots/room_relay_limit_sheet.dart) | `continueRoom(roomId)`、`setRoomRelayLimit(roomId, limit)` | `DsBotRoom.pausedCallIds`/`relayLimit`/`relayLimitDefault` |
| 待答卡片 | [`bot_pending_card.dart`](../../dsh-go/app/lib/ui/bots/bot_pending_card.dart) | `answerQuestion(bot, question, selected:, custom:)`、`dismissQuestion(bot)`、`answerView(bot, view, action, values)`、`dismissView(bot)` | `DsBotQuestion`（`id`/`question`/`detail`/`options`/`multiSelect`/`allowCustom`/`askedAt`）、`DsBotViewCard`（`id`/`title`/`view`/`askedAt`） |
| 审批 | [`room_approval_action.dart`](../../dsh-go/app/lib/ui/bots/room_approval_action.dart) | `answerApproval(id, allow:)` | `DsBotApproval` |
| 记忆面板 | [`memory_card.dart`](../../dsh-go/app/lib/ui/bots/memory_card.dart) | `addMemory(text, botId:/roomId:)`、`editMemory(entry, next)`（需 `memory-edit` 能力）/`forgetMemory(entry)` | `DsBotMemoryView`（`entries`/`summary`/`summarizing`/`error`）、`DsBotMemoryEntry`（`scope`/`text`/`topic`/`added`/`by`/`source`） |
| 头像绘制 | [`bot_avatar.dart`](../../dsh-go/app/lib/ui/bots/bot_avatar.dart) | 无网络调用（纯绘制） | `DsBot.imageRef`/`shape`/`color`、`DsBot.ink` |
| 工作目录 | [`working_folder_sheet.dart`](../../dsh-go/app/lib/ui/bots/working_folder_sheet.dart) | `folders()`、`setBotWorkDir(id, path)`、`setRoomWorkDir(id, path)` | `DsBotFolders.team`/`folders` |
| 长文读取 | [`room_full_text_sheet.dart`](../../dsh-go/app/lib/ui/bots/room_full_text_sheet.dart) | `readFileText(long)` | `DsBotFileText.text`/`whole`、`DsBotLongText.length`/`file` |
| 停止生成 | [`bot_stop_guard.dart`](../../dsh-go/app/lib/ui/bots/bot_stop_guard.dart) | **不走 `DsBotApi`**，走引擎的 `DshClient.cancel(sessionId)`（`session/cancel`） | `DsBotStatus.readOnly`、`DsBotTeam.bot(...).sessionId`、`DsBotTeam.room(...)` |
| 插件入口（旧路） | [`install_card.dart`](../../dsh-go/app/lib/ui/bots/install_card.dart) | 装 **DSH 插件** `ds-bot`（`dsh plugin --profile web add ds-bot`），不是 mcode 插件市场 | [`plugins_state.dart`](../../dsh-go/app/lib/state/plugins_state.dart) |

### 2.3 旧路的事件流字段（`DsBotFeedEvent.parse`）

| 事件名 | 解析出的类 | 字段 |
|---|---|---|
| `hello` | `DsBotFeedHello` | `revision`、`api`、`boot`、`seq` |
| `revision` | `DsBotFeedRevision` | `revision` |
| `live` | `DsBotFeedLive` | 无 |
| `message` | `DsBotFeedMessage` | `botId`、`sessionId`、`item`（`DsBotItem`） |
| `stream` | `DsBotFeedStream` | `botId`、`sessionId`、`attemptId`、`phase`（`start`→分片→`end`）、`text` |
| `notice` | `DsBotFeedNotice` | `DsBotNotice`：`seq`/`kind`/`botId`/`name`/`roomId`/`title`/`sessionId`/`text`/`time`；`kind` ∈ `message`/`group`/`done`/`question`/`view`/`approval` |
| `room` | `DsBotFeedRoom` | `roomId` |
| 其它 | `DsBotFeedOther` | 原始 `event` + `data`（原样保留） |
| 断线 | `DsBotFeedLost` | `error`（由 `DsBotFeed` 自己产生，不是服务端事件） |

## 3. 新 mbot：`MiniMaxBotApi` 实际存在的方法与字段

`MiniMaxBotApi`（[`minimax_bot_api.dart`](../../dsh-go/packages/dsh_client/lib/src/minimax_bot_api.dart)）只有 HTTP/JSON 一层，全部返回 `Map<String, Object?>`，**没有 Dart 模型类**。

### 3.1 方法一览

| 方法 | HTTP | 服务端真值（[`server.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/server.mjs)） |
|---|---|---|
| `state()` | `GET /api/state` | `snapshot()` → `bots`/`groups`/`noteLimits`/`schedules`/`helpers`/`tasks`/`busyChats`/`turns`/`chats`/`approvals`/`sessions`/`agentAvailable`/`settings` |
| `models()` | `GET /api/models` | `{supported, models, default}`；不支持时 `supported:false` |
| `usageQuota(forceRefresh:)` | `GET /api/usage-quota` | 账号配额 |
| `testProvider(providerId, modelId)` | `POST /api/providers/test` | 探针；503 归一成 `{ok:false, code:'provider_unavailable'}` |
| `updateSettings(settings)` | `PUT /api/settings` | `{settings, locale}` |
| `createBot(properties)` | `POST /api/bots` | `{bot}` |
| `updateBot(id, properties)` | `PATCH /api/bots/{bot_*}` | `{bot}`；**patch 是自由 JSON**，可含 `name`/`role`/`instructions`/`skin`/`face`/`autoHelper`/`pinned`/`model`/`effort`/`isMain`/`avatar` |
| `deleteBot(id)` | `DELETE /api/bots/{bot_*}` | — |
| `createGroup(properties)` | `POST /api/groups` | `{group}` |
| `updateGroup(id, properties)` | `PATCH /api/groups/{grp_*}` | `{group}` |
| `deleteGroup(id)` | `DELETE /api/groups/{grp_*}` | — |
| `messages(chat, limit:, before:)` | `GET /api/chats/{chat}/messages` | `{chatId, messages, hasMore, nextBefore}` |
| `sendMessage(chat, text, clientMessageId:, attachments:)` | `POST /api/chats/{chat}/messages` | `{message}` |
| `cancelChat(chat)` | `POST /api/chats/{chat}/cancel` | `{cancelled}` |
| `resetChat(chat)` | `POST /api/chats/{chat}/reset` | `{ok:true}`；忙时 409 `busy` |
| `resumePending(chat)` | `POST /api/chats/{chat}/resume-pending` | 收件箱恢复 |
| `retryMessage(chat, message)` | `POST /api/chats/{chat}/messages/{msg_*}/retry` | 重发 |
| `react(chat, message, emoji:, selected:)` | `PUT /api/chats/{chat}/messages/{msg_*}/reactions` | 表情；随后广播 `message.reactions` |
| `answerQuestion(chat, message, answers)` | `POST /api/chats/{chat}/messages/{msg_*}/answer` | `{ok:true}` |
| `memory(scope)` | `GET /api/memory/{team\|bot_*}` | 记忆视图 |
| `remember(scope, entry)` | `POST /api/memory/{team\|bot_*}` | 记忆视图 + `entry` + `unchanged`；**entry 是自由 JSON**，可含 `text`/`key`/`scope`/`targetBot`/`source`/`replaceId`/`pinned`/`expectedRevision` |
| `forget(scope, entry)` | `DELETE /api/memory/{team\|bot_*}/{8位hex}` | 记忆视图 |
| `createSchedule(schedule)` | `POST /api/schedules` | `{schedule}` |
| `deleteSchedule(id)` | `DELETE /api/schedules/{sch_*}` | `{ok:true}` |
| `runSchedule(id)` | `POST /api/schedules/{sch_*}/run` | `{ok:true}` |
| `stopTask(id)` | `POST /api/tasks/{task_*}/stop` | 停任务 |
| `stopHelper(id)` | `POST /api/helpers/{hlp_*}/stop` | 停助手 |
| `usage(days:)` | `GET /api/usage` | 用量 |
| `events()` | `GET /api/events`（SSE） | 见 3.3 |

标识符格式由客户端强校验：`bot_`、`grp_`、`bot:`/`group:`、`msg_`、`sch_`、`task_`、`hlp_`、8 位 hex（记忆条目 id）。

### 3.2 关键记录字段（服务端真值）

| 记录 | 字段 | 出处 |
|---|---|---|
| Bot | `id`/`name`/`role`(≤60)/`instructions`(≤4000)/`skin`/`color`/`face`/`autoHelper`/`model`/`effort`/`isMain`/`pinned`/`rev`/`createdAt`/`updatedAt`/`avatar?` | `team.mjs` `createBot` |
| 群**记录**字段 | `id`/`name`/`notice`(≤500)/`adminId`/`memberIds`/`replyMode`(`admin`\|`everyone`)/`pinned`/`createdAt`/`maxHops`/`handover`/`notes` | `team.mjs` `createGroup` |
| 群**请求**字段 | `name`/`notice`/`replyMode`/`pinned`/`maxHops`/`handover`/`notes`/`clearNotes`/`members`/`admin` —— **请求用 `members`/`admin`，返回的记录才是 `memberIds`/`adminId`** | `team.mjs` `updateGroup` |
| 消息 | `id`/`chatId`/`ts`/`status`(`done`\|`streaming`\|`pass`)/`seq`/`role`/`botId`/`text`/`parts`/`kind`/`question?`/`answered?`/`taskId?`/`sourceMessageId?`/`startedAt?` | `store.mjs` `appendMessage` |
| 聊天摘要 | `preview`/`lastTs`/`lastRole`/`pending`/`count` | `store.mjs` `summary` |

### 3.3 新路的事件流字段（`GET /api/events`，SSE）

| `type` | 字段 | 出处 |
|---|---|---|
| `hello` | 无（连接首帧） | `server.mjs` |
| `message.upsert` | `chatId`、`message`（整条消息记录） | `engine.mjs` |
| `chat.summary` | `chatId`、`summary`（`preview`/`lastTs`/`lastRole`/`pending`/`count`） | `engine.mjs` `broadcastSummary` |
| `chat.reset` | `chatId` | `server.mjs` |
| `message.reactions` | `chatId` + 反应结果 | `server.mjs` |
| `approval.removed` | `requestId` | `engine.mjs` |
| `state.changed` | 无 | `server.mjs` |

`MiniMaxBotApi.events()` 原样返回解析后的 `Map`，**未知类型不丢**（客户端不做类型白名单）。

### 3.4 新路已经在用的 UI

| 界面 | 文件 | 用到的 mbot 接口 |
|---|---|---|
| 列表 + 聊天 | [`bot_home.dart`](../../dsh-go/app/lib/bot/bot_home.dart) | `state()`、`messages()`、`sendMessage()`、`createBot()`、`createGroup()`、`cancelChat()`、`events()` |
| Bot 资料页 | [`bot_profile.dart`](../../dsh-go/app/lib/bot/bot_profile.dart) | `state()`、`updateBot()` |
| 定时任务 | [`bot_schedules.dart`](../../dsh-go/app/lib/bot/bot_schedules.dart) | `state()`、`createSchedule()`、`deleteSchedule()`、`runSchedule()` |
| 插件市场 | [`bot_plugins.dart`](../../dsh-go/app/lib/bot/bot_plugins.dart) | **不是** `MiniMaxBotApi`，走独立的 [`McodePluginsApi`](../../dsh-go/packages/dsh_client/lib/src/mcode_plugins_api.dart)（`marketplace`/`installed`/`previewGithub`/`install`/`uninstall`/`enable`/`disable`）；安装/启停/卸载/导入在 Android 上一律 `UnsupportedError` |
| 供应商与用量 | [`bot_providers.dart`](../../dsh-go/app/lib/bot/bot_providers.dart)、[`bot_usage.dart`](../../dsh-go/app/lib/bot/bot_usage.dart) | `usageQuota()`、`testProvider()`；供应商增删改走原生通道 `minimax/bot-settings` |
| 设置中枢 | [`bot_settings_hub.dart`](../../dsh-go/app/lib/bot/bot_settings_hub.dart) | 组装 [`packages/mbot_settings/lib/src/ui/settings_hub_page.dart`](../../packages/mbot_settings/lib/src/ui/settings_hub_page.dart) 的插件 / 账号模型两组 |
| 定时任务通知 | [`bot_schedule_notifications.dart`](../../dsh-go/app/lib/bot/bot_schedule_notifications.dart) | 只监听 `message.upsert` 且 `message.role=='schedule'`、`chatId` 形如 `bot:bot_*`、`message.id` 形如 `msg_*` |

## 4. 对应关系（存在才写映射）

| 旧 DSH 能力 | `DsBotApi` 方法 | `MiniMaxBotApi` 对应 | 差异（按源码） |
|---|---|---|---|
| Bot 列表 | `state()` → `team.bots` | `state()` → `bots` | mbot 的 bot 记录**没有** `sessionId`/`parts`/`waiting`/`appliedModel`/`workDir`/`shape`/`imageRef`；多出 `skin`/`face`/`autoHelper`/`isMain`/`pinned`/`rev`/`avatar` |
| 群列表 | `state()` → `team.rooms` | `state()` → `groups` | mbot 群没有 `parts`/`pausedCallIds`/`preview`/`relayLimit`；用 `adminId`/`memberIds`/`replyMode`/`maxHops`/`handover`/`notes` |
| 建 Bot | `createBot(name, role, instructions, color, model, effort, brief)`（页面只传 name/role/instructions/color/model/effort） | `createBot({name, role, ...})` | mbot 收的是**自由 JSON**（`team.mjs` 读 `skin`/`face`/`autoHelper`/`model`/`effort`/`isMain`/`avatar`），无 `brief`；旧路有 `color` |
| 改 Bot | `updateBot(id, name/role/instructions/color/model/effort)` | `updateBot(id, {...})` | mbot `PATCH` 接受自由 patch（`team.mjs` 逐字段校验），可改的字段比旧路多 |
| 删 Bot | `deleteBot(id)` | `deleteBot(id)` | 一致 |
| 复制 Bot | `duplicateBot(id)` | **无** | 缺口 |
| 置主 Bot | `setMain(id, main)` | `updateBot(id, {'isMain': true/false})` | **不是缺口**：`Team.updateBot` 处理 `patch.isMain`（取消置主时若这是最后一个主 Bot，服务端报 `invalid`） |
| 建群 | `createRoom(members, name, admin, greet)` | `createGroup({name, members})` | mbot 无 `greet`；管理员用 `admin` 字段，缺省取首个成员 |
| 改群 | `updateRoom(id, name/notice/mode/admin/add/remove)` | `updateGroup(id, {name/notice/replyMode/pinned/maxHops/handover/notes/clearNotes/members/admin})` | **请求字段与返回记录字段不同**：请求用 `members`（整表）与 `admin`，服务端算出的记录字段才是 `memberIds`/`adminId`；`mode` 在 mbot 叫 `replyMode` |
| 删群 | `deleteRoom(id)` | `deleteGroup(id)` | 一致 |
| 群接续 | `continueRoom(roomId)` | **无** | 缺口（mbot 的 `busyChats`/`turns` 能表达占用，但没有「解除限流接续」接口） |
| 群接龙上限 | `setRoomRelayLimit(roomId, limit)` | `updateGroup(id, {'maxHops': n})` | **不是缺口**：`Team.updateGroup` 处理 `patch.maxHops`（0 = 不限），另有 `handover`/`notes` 同为可更新字段 |
| 停成员工作 | `stopMemberWork(roomId, botId)` | `cancelChat(chat)` | 粒度不同：mbot 只能按 chat 取消 |
| 发消息 | `send(conversationId, text)` | `sendMessage(chat, text, clientMessageId:, attachments:)` | mbot 强制 `clientMessageId`；支持 `attachments` |
| 消息历史 | `chat(botId, cursor:)` → `{sessionId, items, cursor}` | `messages(chat, limit:, before:)` → `{chatId, messages, hasMore, nextBefore}` | 游标语义不同（旧：`cursor` 泛型；新：`before` = 消息 id，`nextBefore`） |
| 房间消息历史 | `roomChat(roomId, cursor:)` | 同上（`chat` 用 `group:grp_*`） | 同上 |
| 流式回复 | feed `stream` 事件（`attemptId`/`phase`/`text`） | **无独立流事件**；靠 `message.upsert` 里 `status:'streaming'` 的消息 | 缺口：没有 attempt 级增量文本 |
| 取消生成 | `DsBotApi` **没有**取消方法；旧 UI 的「停止」走引擎的 `DshClient.cancel(sessionId)`（`session/cancel`），不是 Bot 服务接口 | `cancelChat(chat)` | mbot 的取消落在 Bot 服务自己身上，不依赖引擎会话 |
| 重置会话 | **无** | `resetChat(chat)` | mbot 多此一项 |
| 重发消息 | **无** | `retryMessage(chat, message)` | mbot 多此一项 |
| 表情回应 | **无** | `react(chat, message, emoji:, selected:)` | mbot 多此一项 |
| 待答问题 | `answerQuestion(bot, question, selected:, custom:)` | `answerQuestion(chat, message, answers)` | 入参形状不同：旧的是选项数组 + 自定义串，新的是 `answers` 数组 |
| 卡片回复 | `answerView(bot, view, action, values)` | **无**（mbot 没有 view/卡片这套） | 缺口 |
| 关闭待答 | `dismissQuestion` / `dismissView` | **无** | 缺口 |
| 审批 | `answerApproval(id, allow:)` | **无**；mbot 审批在 `state.approvals` 里，但 `MiniMaxBotApi` 没有审批回复方法 | 缺口（服务端有 `POST /api/approvals/{id}`，客户端没暴露） |
| 记忆（读） | `memory(botId:/roomId:)` | `memory(scope)`，scope ∈ `team`/`bot_*` | mbot 无「按群记忆」scope，只有 team / 单 Bot |
| 记忆（写） | `addMemory(text, scope:)` | `remember(scope, entry)` | 入参不同 |
| 记忆（改） | `editMemory(entry, next)` | `remember(scope, {'text': next, 'replaceId': id, 'expectedRevision': rev})` | **不是缺口**：客户端的 `remember` 收自由 JSON，服务端记忆路由接受 `replaceId`/`expectedRevision` 并要求 revision 一致 |
| 记忆（删） | `forgetMemory(entry)` | `forget(scope, entry)` | 一致 |
| 记忆摘要 | `DsBotMemoryView.summary`/`summarizing` | 记忆视图字段形状未在客户端建模 | 缺口（客户端是裸 Map，无 Dart 模型） |
| 定时任务 | **无** | `createSchedule`/`deleteSchedule`/`runSchedule` | **mbot 独有** |
| 停任务 / 停助手 | **无** | `stopTask`/`stopHelper` | **mbot 独有** |
| 用量 | `usageSummary(botId)` → 按天/周/月/模型 | `usage(days:)` → 天数窗口 | 形状不同；mbot 另有 `usageQuota()`（账号配额） |
| 模型目录 | `state(modelsKey:)` → `team.models`（`DsBotModel`） | `models()` → `{supported, models, default}` | 形状不同 |
| 通知（离线提醒） | `notices(boot:, since:)` + feed `notice` 事件（`DsBotNotice`） | **无等价接口**；只有 `message.upsert` + `state.changed` | 缺口（mbot 没有「按 seq 补齐通知」的机制） |
| 事件流 | `feed()`：SSE + 自动重连 | `events()`：SSE，**无自动重连** | 缺口：mbot 客户端断流后要调用方自己重订 |
| 头像 | `avatar(botId)` → `{ref, image}` | `state().bots[].avatar`（服务端字段）；`/api/avatars` 路由存在但客户端未暴露 | 部分缺口 |
| 工作目录 | `folders()`/`setBotWorkDir`/`setRoomWorkDir` | **无** | 缺口 |
| 长文读取 | `readFileText(long)` | **无** | 缺口 |
| 插件市场 | 旧路装的是 DSH 插件 `ds-bot` | `McodePluginsApi.marketplace/installed/previewGithub`（读可用）；安装/启停/卸载**全部抛 `UnsupportedError`** | 读：mbot 有；写：缺口（`BotPluginsPort.canMutate == false`） |
| 供应商配置 | **无**（旧路模型由 DSH 侧管） | 原生通道 `minimax/bot-settings`（`providers`/`saveProvider`/`deleteProvider`/`selectDefaultProviderModel`） | **mbot 独有** |
| 群公告 | `DsBotRoom.notice` | 群记录 `notice` | 一致 |

## 5. 缺口小结（按功能域）

**不是缺口**（客户端的自由 JSON + 服务端的 patch 处理已经能表达，见第 4 节）：置主 Bot、记忆编辑、群接龙上限（`maxHops`）、群成员/管理员调整。

真正的缺口：

1. **Bot 复制**：mbot 侧没有对应接口。
2. **卡片 / view 回复**、**关闭待答**、**审批回复**：mbot 客户端未暴露（服务端 `/api/approvals/{id}` 存在）。
3. **群接续**、**停单个成员的工作**：mbot 无对应粒度。
4. **流式增量文本**（attempt 级）：mbot 只有整条 `message.upsert`。
5. **离线通知补齐**（按 seq）：mbot 无。
6. **事件流自动重连**：mbot 客户端无。
7. **工作目录**、**长文读取**、**头像读取接口**：mbot 客户端无。
8. **模型/记忆/用量全部是裸 `Map`**，没有 Dart 模型类；旧路的 `DsBotTeam`/`DsBotItem`/`DsBotMemoryView` 等类型在 mbot 侧没有对应物。
9. **插件写操作**（安装/启停/卸载/导入）在 Android 上一律未实现（读与预览可用）。
10. **Bot 入口**：产品 App 里没有 mbot 的 Bot 入口，只有 dev 入口。

## 6. 后续可单独派出的 UI 小任务

### 6.1 已有实现，不要重复造页面

以下行为**已经在仓库里**，本报告早前版本把它们列成待办是错的：

| 行为 | 已有实现的位置 |
|---|---|
| Bot 列表 / 群列表（mbot 版） | [`bot_home.dart`](../../dsh-go/app/lib/bot/bot_home.dart) 的 `_list()` |
| 单 Bot 聊天页（mbot 版） | [`bot_home.dart`](../../dsh-go/app/lib/bot/bot_home.dart) 的 `_chat()` |
| 群聊页（mbot 版）+ 群成员名标注 | [`bot_home.dart`](../../dsh-go/app/lib/bot/bot_home.dart) 的 `_chat()`（`isGroup` 分支，用 `message['botId']` 反查 Bot 名） |
| 建 Bot / 建群对话框 | [`bot_home.dart`](../../dsh-go/app/lib/bot/bot_home.dart) 的 `_create()` |
| 事件流 → 状态同步 | [`bot_controller.dart`](../../dsh-go/app/lib/bot/bot_controller.dart) 的 `_applyEvent()`（`message.upsert` / `chat.summary` / `chat.reset`） |
| 定时任务设置页接到 mbot | [`bot_profile.dart`](../../dsh-go/app/lib/bot/bot_profile.dart) 打开 `MbotScheduleSettingsPage(port: BotSchedulesPort(...))`，端口在 [`bot_schedules.dart`](../../dsh-go/app/lib/bot/bot_schedules.dart) |
| 插件市场写操作的禁用态与说明 | [`packages/mbot_settings/lib/src/ui/plugin_settings_page.dart`](../../packages/mbot_settings/lib/src/ui/plugin_settings_page.dart) 已按 `MbotPluginPort.canMutate` 禁用安装/启停/卸载/导入并给出说明文案 |

### 6.2 仍可独立派出的任务

以下是**建议**，不是已授权的计划；是否开工由 Codex 逐项派发。每项都只依赖**已经存在**的接口，不新增协议、不造凭据字段、不假设已连接。

| # | 任务 | 精确文件 | 依赖的已存在接口 | 验收方式 |
|---|---|---|---|---|
| U7 | 记忆面板（mbot 版） | 新增 `dsh-go/app/lib/bot/bot_memory_card.dart` | `memory()`、`remember()`、`forget()` | widget 测试：读/写/删三条路径 |
| U8 | 消息重发 / 重置会话按钮 | 改 [`bot_home.dart`](../../dsh-go/app/lib/bot/bot_home.dart) 的 `_chat()` | `retryMessage()`、`resetChat()` | widget 测试：按钮分别调对应方法 |
| U9 | 产品 App 里的 Bot 入口（把 mbot 界面接到抽屉/功能目录） | [`dsh-go/app/lib/ui/features/feature_catalog.dart`](../../dsh-go/app/lib/ui/features/feature_catalog.dart)、[`dsh-go/app/lib/ui/shell/sessions_drawer.dart`](../../dsh-go/app/lib/ui/shell/sessions_drawer.dart) | `BotRuntimeEndpoint` 报出活服务后才有真实数据 | 功能目录项可达；**无服务时显示「没有 Bot 服务在运行」而不是假列表** |

U1（把裸 `Map` 换成 Dart 模型，会改公共 API 的返回类型）与 U2（事件流自动重连，会改公共 API 的行为）都动公共客户端 API，**需 Codex 另行设计后再派**，本报告不自行开工。

## 7. 本报告的边界

- 只读源码，**没有**改任何 UI 源码、状态层、API、依赖或 CI。
- **没有**做设备验证，**没有**重跑全量 golden 套件。
- 服务端字段以 [`server.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/server.mjs) / [`team.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/team.mjs) / [`store.mjs`](../../dsh-go/runtime/vendor/minimax-bot/miniapp/node/store.mjs) 为准；`MiniMaxBotApi` 只是它的一个很薄的 HTTP 封装，**两者不等价**：服务端有而客户端未暴露的路由，本文一律记为缺口。
- 旧路的 DSH 插件服务与新路的官方 Bot 服务是**两套不同的服务端**，字段名相似处不代表同一语义；本文按各自源码分别记录。
- 本文不包含任何 key、token 或环境变量值。
