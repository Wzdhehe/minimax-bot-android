<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash大任务：现有Bot记忆页的新增、编辑、删除

状态：收到6d08ad7并快进纳入以便审查，**尚未审查接受**。以下文末三项修复为当前任务，不重做整卡。按用户最新反馈，本轮报告只留本机，不要求补交`docs/research/flash-bot-memory-manage.md`；共享交接由Codex维护。

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

## Codex审查6d08ad7：先修三项，不扩功能

只允许改`bot_memory_page.dart`与其`minimax_bot_memory_page_test.dart`，其余沿用本机报告，不提交报告、不改文案/生成物/共享交接/本卡。可拆有证据的小提交，修完后运行原定向analyze及memory/profile/home/stream测试，推送回报。云端Codex正在做Windows验收工具，勿碰tooling/runtime/CI。

### R1：写流程代次没有在scope/API切换时失效

真实源码：didUpdateWidget仅清blocked/failure/draft/refreshNeeded并调用_load，没有递增_change；_runChange/_readAfter只比较change与_change，不核对当前scope/api。旧A写请求在等待中切到B，B读取完成后，A成功会调用api.memory(A)并把A条目写进B页；A失败则会把A错误/草稿写到B并锁住B。两个finally还无条件清_changing。现测试只测对话框确认前切Bot，不覆盖已发出的写请求。

建立在流程打开时就捕获的代次（包含对话框阶段），切Bot或API时失效并恢复新上下文自己的流程状态。确认前、写回/错误、写后读、finally均检查代次；旧流程不能发起新的A读取、覆盖B结果或解除B正在进行的忙碌。不要仅在_runChange开始后才分配代次；否则A→B→A的旧对话框仍可误启动。

回归：Completer挂起A写请求，切同一State到B并完成B读取，再让A成功/失败，B文本/错误/草稿不变、不得额外读A。另保持B自己的变更挂起，A的finally不能解锁B。补同botId但API实例变化及旧对话框跨上下文后确认的场景。

### R2：写入准入遗漏读取状态，且手动读取可与写竞争

_writesAllowed只有changing/blocked/scope/api，_load开始就设scope/api，因此初始读取尚未成功、读取失败、写后GET失败(_refreshNeeded)时新增仍可用；_readAfter失败未blocked，旧条目的编辑/删除也重新启用。_openForm/_confirmDelete方法入口只查changing，不校验完整写入准入。结果未核对时不能继续写。

统一准入：同一已成功读取的上下文，且非loading/failed/refreshNeeded/blocked/changing，才允许新增/编辑/删除；按钮和方法入口都使用它，确认后还须复查上下文/代次。写后读取失败必须阻止后续写，直到成功的手动读取；保留原列表与正确提示，不显示假空态。

现失败横幅在手动读成功后仍可能存在，retry按钮仅看_loading，因而写入期间可再_load，迟到的手动GET可能覆盖写后的结果。手动读的按钮和方法在变更流程期间均须拒绝；内部写后读取仍由同一变更流程执行并检查其代次。成功手动核对后正确清理过期失败提示，草稿可保留供复制，但不自动重发。

回归：初次读取挂起/失败时无写；写成功但GET抛错或坏scope时三种写入口全禁用，重读成功后才恢复；写Completer等待中不能手动刷新发额外GET；旧读不覆盖较新结果。不要仅测出现提示文案。

### R3：memory_pending的HTTP400不是明确拒绝

实际memory.mjs的commit注释明确durable pending是acceptance point；finish在其后失败会抛MemoryError(code='memory_pending')。server.mjs把该已知错误映射为400。当前_kindOf把所有<500且非conflict的异常当rejected，会给已接受事务显示「服务端没有接受」。

按业务code优先：memory_pending归结果不明/待恢复；memory_conflict仍冲突。只对白名单中明确拒绝的业务code/状态给rejected（如memory_invalid/full/too_long以及实际确认的前置拒绝），未知业务code和不认识的非2xx保守归unknown。不读取原始message，不改协议，也不因未知结果自动重试。

回归至少注入MiniMaxBotException(400,'memory_pending','不可回显的内容')，显示结果不明、保留草稿、锁写等待成功手动读，不显示拒绝、不回显message、不重发。原memory_conflict、显式拒绝和5xx/timeout用例也保留。
