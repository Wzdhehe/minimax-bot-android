<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash任务：Bot设置内的只读记忆页

前卡3c1f50d已源码审查接受。你在本机Windows做本卡，Codex在云端继续Android socketpair/launcher地基；不共享工具链与文件系统，不等云端APK。

## 目标：只读第一阶段

在现有BotProfilePage增加「记忆」入口，打开该Bot的记忆阅读页。只读已有真实API，不实现新增/编辑/删除记忆，不实现team scope，不创建另一套MemoryStore，不改客户端或服务端协议。产品尚无运行中服务时不造数据；测试假数据只放test。

已实查合同：`MiniMaxBotApi.memory(String scope)` GET `/api/memory/{scope}`；单Bot scope直接是`botId`，不是`bot:botId`/文件路径。官方`server.mjs`的`memoryView`返回`{scope, entries, chars, budget, contextBudget}`，条目文本在`entry.text`。先读这些真实源码，再实现；不要照抄旧DSH记忆的数据模型。

## UI与生命周期要求

- 仅在进入阅读页时发memory读取，不在每次profile重建时重复请求。Profile入口在没有_bot时禁用，导航返回后守mounted；不要改现有编辑、通知、定时任务行为。
- 新页接收现有MiniMaxBotApi实例与botId，API由父级拥有，页销毁不能close它。显示服务端条目text（可选择文字），不显示原始JSON/id/revision/异常字符串，不渲染任意HTML。
- 支持初次加载、空、错误与手动重试。读取失败不能显示为「还没有记忆」；载荷缺scope、scope不是本Bot、entries不是List或条目text不是String时按读取失败处理，不把错误Bot的数据展示出来。不自动重试。
- 使用既有MbotLoadTransition等轻量加载/转场组件，无需另造shimmer。复用现有双语文案`botsMemory`、`botsNoMemory`、`botsMemoryReadFailed`、`botsTapRetry`（以实际l10n属性为准），无新增翻译。不展示写操作按钮或暗示可编辑。
- 重试期间入口禁用，单个Bot同一时刻不重复发读取；若widget的api或botId变化，应加载新的scope，旧响应/旧异常不得覆盖新结果。销毁后完成不setState。用简单请求序号/身份检查，不引入公共状态框架或缓存。
- 内容限制最大宽度680并可滚动，390/1024宽度含长文本不overflow；保持已有减少动态效果语义，不加无界动画。只展示文本，budget等指标下一阶段再做。

## 唯一允许写入

1. 新`dsh-go/app/lib/bot/bot_memory_page.dart`。
2. `dsh-go/app/lib/bot/bot_profile.dart`，仅import与新增记忆导航行，不重构上一卡编辑流程。
3. 新`dsh-go/app/test/bots/minimax_bot_memory_page_test.dart`。
4. 新`docs/research/flash-bot-memory-read.md`。

新文件加AGPL-3.0 SPDX头。其他文件只读；特别不改已有测试、公共API/controller、l10n源/生成物、依赖/lockfile、BotHome、runtime/Endpoint/桥、CI、共享HANDOFF/NEXT-AGENT或本卡。确有卡外阻塞先记录交Codex，不自行扩范围。

## 定向验证

用假API和Completer驱动真实widget：

- 从profile点记忆后只读当前botId，条目正确显示且没有写请求，父API未被页close。
- 空entries显示空态；请求失败显示读取错误与重试，不显示空态；手动重试成功后恢复。确认等待期间不能重复请求，无自动重试。
- 错scope与错误载荷结构显示读取错误，不泄露该响应条目或原始异常。
- widget切换botId（保持同一State）后B响应先完成、旧A响应/旧A异常迟到，仍显示B；销毁后迟到响应无框架异常。
- 390/1024宽度长内容可滚动无overflow。

不得pumpAndSettle等待未完成Completer或无限动画。Flutter命令在dsh-go/app，Python在根目录：

```text
flutter analyze --no-pub lib/bot/bot_memory_page.dart lib/bot/bot_profile.dart test/bots/minimax_bot_memory_page_test.dart
flutter test --no-pub test/bots/minimax_bot_memory_page_test.dart test/bots/minimax_bot_profile_edit_test.dart test/bots/bot_profile_test.dart
flutter test --no-pub test/bots/minimax_bot_home_test.dart test/bots/bot_stream_performance_test.dart
python tooling/docs-links/check_links.py --root . docs/research/flash-bot-memory-read.md
```

报告只记实际命令/输出；无真实服务、设备或产品APK验证就明确未验证。不跑全量golden、不改测试基准。

## 同分支协作

开工及推送前fetch，沿用feat/t-plugins；只显式暂存上述4个路径。自己的未推送提交可rebase到最新远端，不覆盖其他工作，不force、不动main/dev/integration、不PR、不发布APK。交付完整SHA、远端实查、测试输出与未验证项，等Codex审查，不自行扩展记忆写入/下一任务。
