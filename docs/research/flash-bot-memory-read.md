<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash：Bot 设置内的只读记忆页（第一阶段）

本轮只改两处源码并新增两个文件：[`dsh-go/app/lib/bot/bot_memory_page.dart`](../../dsh-go/app/lib/bot/bot_memory_page.dart)（新）、[`dsh-go/app/lib/bot/bot_profile.dart`](../../dsh-go/app/lib/bot/bot_profile.dart)（只加 import 与一行导航）、[`dsh-go/app/test/bots/minimax_bot_memory_page_test.dart`](../../dsh-go/app/test/bots/minimax_bot_memory_page_test.dart)（新）与本报告。

**没有**实现记忆写入、编辑、删除，**没有** team scope，**没有**另建一套 MemoryStore，**没有**改客户端或服务端协议、公共 API / controller、l10n 源与生成物、依赖 / lockfile、`BotHome`、runtime / Endpoint / 桥、CI 或共享交接文档。

## 1. 源码变化

### 1.1 读的是已有的真实接口

实查过的合同（`dsh-go/runtime/vendor/minimax-bot/miniapp/node/server.mjs`）：

- `GET /api/memory/{scope}`，`scope` 是 `team` 或 `bot_*`；
- 单 Bot 的 scope **就是 `botId`**，不是 `bot:botId`、也不是文件路径；
- 回答是 `{scope, entries, chars, budget, contextBudget}`（`memoryView`），条目文本在 `entry.text`。

页面只用 `MiniMaxBotApi.memory(scope)` 这一个已有方法，没有新增接口。

### 1.2 新页面 `bot_memory_page.dart`

- **只读**：显示服务端给的 `entry.text`（`SelectableText`，可选择文字），不显示 id、revision、原始 JSON、budget 或异常字符串；没有写操作按钮，也不暗示可编辑；不渲染 HTML。
- **API 由父级拥有**：页面接收 `MiniMaxBotApi` 与 `botId`，**从不 `close()` 它**。
- **只在进入阅读页时读一次**：`initState` 读一次；profile 每次重建**不会**重复读记忆。
- **一次只读一个 Bot**：`didUpdateWidget` 在 `api` 或 `botId` 变化时读新的 scope。
- **身份检查**：每次读带一个自增序号；旧读的回答或旧读的错误**不得**覆盖新结果（`read != _read` 时直接丢弃）。销毁后完成的读不 `setState`。
- **读取失败 ≠ 空记忆**：失败显示 `botsMemoryReadFailed` 与 `botsTapRetry`；空 entries 才显示 `botsNoMemory`。
- **坏载荷按失败处理**：载荷缺 `scope`、`scope` 不是本 Bot、`entries` 不是 List、条目 `text` 不是 String —— 一律按读取失败处理，**那条响应的条目一个字都不显示**。
- **不自动重试**：失败后只有用户手动点重试才会再读一次；重试进行中重试按钮禁用，方法自身也不重复发读。
- **复用既有组件**：初次加载用 `MbotLoadTransition`（不另造 shimmer），宽度上限 680、内容可滚动，沿用它对「减少动态效果」的语义；没有无界动画。

### 1.3 profile 上的入口

`bot_profile.dart` 只加了一个 import 和一行导航（`Card` + `ListTile`，key `mbot.bot.memory`）：没有 `_bot` 时禁用；点开用 `MaterialPageRoute` 进入 `BotMemoryPage`，返回后按既有写法守 `mounted` 再 `_reload()`。现有编辑、通知、定时任务行为**一行没改**。

## 2. 实际命令与输出

环境：本机 Windows，Flutter 在 `D:\toolchains\flutter`，pub cache / TEMP / 字体在 D 盘（`D:\android-setup\flutter.sh`）。Flutter 命令在 `dsh-go/app` 执行，链接检查在仓库根执行。

### 2.1 定向 analyze

```text
$ cd dsh-go/app && flutter analyze --no-pub lib/bot/bot_memory_page.dart lib/bot/bot_profile.dart test/bots/minimax_bot_memory_page_test.dart
Analyzing 3 items...
No issues found! (ran in 2.4s)
```

### 2.2 卡内测试 + 既有的两个 profile 测试

```text
$ cd dsh-go/app && flutter test --no-pub test/bots/minimax_bot_memory_page_test.dart test/bots/minimax_bot_profile_edit_test.dart test/bots/bot_profile_test.dart
00:02 +22: All tests passed!
```

（记忆页 13 个用例，上一卡的 profile 编辑 7 个，既有的 `bot_profile_test.dart` 2 个。）

### 2.3 未改动文件的回归

```text
$ cd dsh-go/app && flutter test --no-pub test/bots/minimax_bot_home_test.dart test/bots/bot_stream_performance_test.dart
00:03 +11: All tests passed!
```

### 2.4 用例覆盖

| 场景 | 断言 |
|---|---|
| 从 profile 进入 | 只读 `['bot_a']`（scope 就是 `botId`）、条目文本显示、没有写请求、**父 API 未被 close**、载荷里的 id / revision 不显示 |
| 空 entries | 显示 `还没有记忆`，不显示读取失败 |
| 读取失败 | 显示 `没能读取记忆`、**不显示** `还没有记忆`、**不显示**原始异常字符串、**不自动重试**（`scopes` 仍是 1） |
| 手动重试 | 成功后显示条目，`scopes` 变成 2 |
| 重试进行中 | 重试按钮 `onPressed` 为 null，再点也不会多发一次读 |
| 坏载荷（4 种） | 缺 scope / scope 是别的 Bot / entries 不是 List / 条目 text 不是 String —— 都按失败处理，那条响应的条目不显示 |
| 切换 botId（同一 State） | B 先回答、旧 A 的回答迟到 → 仍显示 B，A 的内容不显示 |
| 切换 botId（同一 State） | 旧 A 的**异常**迟到 → 仍显示 B，不变成失败，不泄露异常文本 |
| 页面销毁后迟到 | 无框架异常 |
| 390 / 1024 宽度 | 长中文与长英文内容都可滚动、无 overflow |

### 2.5 身份检查确实被覆盖

把页面里的 `read != _read` 判断临时去掉（对照完立即还原，已核对 `git diff`），同一份测试的结果：

```text
Failing tests:
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_memory_page_test.dart: a late answer from the previous Bot cannot replace the new one
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_memory_page_test.dart: a late failure from the previous Bot cannot replace the new one
```

### 2.6 文档链接检查

```text
$ cd <repo root> && python tooling/docs-links/check_links.py --root . docs/research/flash-bot-memory-read.md
checked 1 document(s), found 0 issue(s)
```

## 3. 未验证项

- **没有真机/模拟器执行**：本轮没有任何设备运行记录；widget 测试不能当成产品 APK 已验证。
- **没有真实 Bot 服务**：测试里的 `_Api` 是本地假实现，只覆盖「读了哪个 scope、读了几次、显示了哪些文本」。真实服务的 `memoryView` 形状是读源码确认的，**没有**对真实服务发过一次请求。
- **没有跑全量 golden 套件**（按任务卡要求）。只跑了上列四个文件。
- **没有实现也没有验证**：记忆写入 / 编辑 / 删除、team scope、budget 与 contextBudget 指标、记忆条目的来源与时序。
- **没有验证**折叠屏、中屏与无障碍语义；只跑了 390 与 1024 两个宽度。
- **没有验证**真实 profile 页面上的滚动位置与入口可达性 —— 测试里用 `scrollUntilVisible` 找到入口，真实设备的滚动手感未验证。
- **没有验证**多个 Bot 之间切换 profile 时记忆页的重建顺序（只测了同一 State 内切换 `botId`）。
