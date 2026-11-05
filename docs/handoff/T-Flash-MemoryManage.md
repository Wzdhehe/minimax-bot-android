<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash大任务：现有Bot记忆页的新增、编辑、删除

用户约一小时后断电。本卡授权你在本机Windows独立推进；实现约40分钟后必须进入验证/交接，至少预留10分钟提交推送。仍用feat/t-plugins，先fetch同步。不要等云端APK；Codex只做native授予撤销，不改本卡UI文件。

前卡b36ade7已源码审查接受。本卡在现有BotMemoryPage上扩展，不新造记忆系统。目标是单Bot的记忆管理，团队scope/预算图表/实时事件订阅/插件/主入口不在本轮。

## 已核对、必须继续按源码确认的合同

- 读取：`api.memory(botId)`，返回scope、entries等；当前scope验证和迟到请求/异常守卫必须保留。
- 新增：`api.remember(botId, {'text': text.trim()})`，不要生成id、revision或额外key。
- 编辑：`api.remember(botId, {'text': text.trim(), 'replaceId': entry.id, 'expectedRevision': entry.revision})`。**revision是服务端字符串，不是计数整数**，取用户打开编辑时看到的条目；不能为绕过冲突自动换revision重发。不传key/pinned，服务端会保留已有值。
- 删除：`api.forget(botId, entry.id)`；现协议没有expectedRevision参数，**不能宣称删除具有修订保护**。须明确确认，不新增服务端协议。
- entry.id实际是8位小写hex，编辑还需非空字符串revision。原只读测试里只有text的条目仍可阅读；缺少合法id/revision时相应写动作禁用，不能拼造字段。
- `MiniMaxBotException.code == 'memory_conflict'`表示修订冲突，服务端目前返回409；按code识别，不用message原文做逻辑。memory_full/memory_too_long也是既有业务code，可给对应安全文案，但不能输出异常message/原始JSON。
- 所有变更均不自动重试。超时、连接断开、5xx、格式错误等可能已经写入，**不能断言未提交**；先从服务端重新读取核对，再由用户重新决定，绝不自动重发。

参考源码：`packages/dsh_client/lib/src/minimax_bot_api.dart`（在dsh-go下）；`runtime/vendor/minimax-bot/miniapp/node/server.mjs`的memory路由、`memory.mjs`的remember/conflict/forget（均在dsh-go下）。不要改它们。

## 行为要求

1. 新增入口、条目编辑/删除入口都放现有页。新增/编辑用同一轻量表单，文本预填、可多行、空白禁用保存；删除有确认，取消不请求。不显示内部id/revision。
2. 同一时刻只允许一个变更流程，含对话框、退出过渡、HTTP等待与重新读取；入口及方法都防重入。禁止自动提交、自动重试、乐观伪造数据。成功后以服务端读取结果更新列表；读取失败应提示需要刷新，不回退成空记忆。
3. 每次流程捕获当时api/botId/entry.id/revision。确认后如果页面已销毁或api/botId变了，不启动写入；已发出的旧请求不可撤回，但它的结果/异常不能覆盖新页，也不能清掉新scope的忙碌标记。借用API，不close它。
4. 写失败保留草稿供用户查看/复制。可选简单实现：失败时保持原表单和草稿可见，禁止再次保存，用户关闭后手动刷新；或者在页内保留本scope的失败草稿。无需跨页/磁盘持久化。**不得失败后直接自动重发**。冲突提示刷新后重新编辑；结果不明提示无法确认、请刷新核对，不用「保存失败所以没写进去」文案。
5. 冲突/结果不明后，本scope写入口保持禁用，直到一次成功的手动读取完成。读取恢复不自动重新执行变更；即便服务器已有该文本，也不能凭文本相同推断某次提交成功。用户重新打开编辑应使用刷新后的服务端revision并再次确认。
6. 删除失败同样不得重试或乐观移除；删除确认可说明「删除当前条目」，不能虚构版本比较。只读载荷缺写字段时仍可读，写入口禁用。
7. 使用现有轻量加载/过渡和减少动画能力；390/1024长文本无overflow。不要添新动画依赖。沿用已有新增/编辑/保存/取消/删除文案；新增冲突/结果不明等提示需中英文同步，禁止硬编码中文。

## 唯一允许写入

- `dsh-go/app/lib/bot/bot_memory_page.dart`（可在同文件加私有条目/表单类，不改公共API）。
- `dsh-go/app/test/bots/minimax_bot_memory_page_test.dart`（保留原13项，可补充更真实id/revision，但不能删弱化原断言）。
- 新报告`docs/research/flash-bot-memory-manage.md`（AGPL-3.0头）。
- 可新增一对文案源`dsh-go/app/lib/l10n/parts/mbot_memory_zh.arb`、`mbot_memory_en.arb`，只放本卡确需文案，键使用mbotMemory前缀。它们是JSON，不能加破坏JSON的注释；许可证归现有项目，不为加头改坏ARB。
- **只允许由`python tool/l10n_merge.py`生成**的`dsh-go/app/lib/l10n/app_zh.arb`、`app_en.arb`及`lib/l10n/gen/app_localizations*.dart`。不得手写生成物、提交不相关重排/lockfile。若重生成引入大面积无关差异，先查SDK/生成配置，记录阻塞，不自行升级依赖。

其余全部只读，特别不改BotProfile/BotHome、公共客户端/controller、runtime/broker/Endpoint/桥、CI、依赖、共享HANDOFF/NEXT-AGENT或任务卡。也不改前轮只读报告，它作为历史保留。

## 验收最小集合

除原只读13项，新增widget交互至少覆盖：

- 新增空白禁用、有效文本提交一次、成功读取服务端实际结果。
- 编辑预填、请求带原始id/字符串revision且无多余字段；模拟memory_conflict不自动重试、不伪造保存，草稿仍可见，刷新之前写入口不可用。
- 超时/断连一次请求后进入「结果不明、需刷新」，草稿保留，无自动重发；刷新成功也不重发。测试成功写回执后GET失败与写失败要区分。
- 删除取消不请求；确认后只调用对应scope/id一次，成功后读取；失败不乐观移除、不重试。
- Completer挂起写请求时连点只一次；页面退出或scope/api改变前后，不越scope发写请求、不让旧结果改新页；确认后退出过渡中销毁不得启动请求。
- 新旧API对象切换、父API销毁所有权守卫，及390/1024长表单/列表无overflow。不要pumpAndSettle等未完成Completer或无限骨架。

本机执行，按实际命令如实记录（Flutter在dsh-go/app；链接检查在根）：

```text
python tool/l10n_merge.py
python tool/l10n_merge.py --check
flutter analyze --no-pub lib/bot/bot_memory_page.dart test/bots/minimax_bot_memory_page_test.dart
flutter test --no-pub test/bots/minimax_bot_memory_page_test.dart test/bots/minimax_bot_profile_edit_test.dart test/bots/bot_profile_test.dart
flutter test --no-pub test/bots/minimax_bot_home_test.dart test/bots/bot_stream_performance_test.dart
python tooling/docs-links/check_links.py --root . docs/research/flash-bot-memory-manage.md
```

无设备/真实服务就写未验证，不跑全量golden，不把假API说成真实Bot连通。

## 断电前交付

按可验证阶段及时commit/push，可拆成「新增与删除」「修订编辑与失败恢复」等连贯提交，但不得将未完成入口伪装可用。每次fetch，自己的未推送提交可rebase，不force，不覆盖云端工作。仅显式暂存允许路径，不动main/dev/integration、不PR、不发布APK。

若40分钟仍未完成全部：停止扩功能，完成当前可验证小阶段，其余动作保持禁用/不露入口，报告逐项写明剩余内容、实际SHA、实际测试及未验证；最后10分钟必须用于测试、文档与推送，不留未推送源码等断电。不要为了赶时间删除修订守卫、关闭失败提示或编造测试。
