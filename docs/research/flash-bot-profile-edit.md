<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash：完善已有 Bot 设置页编辑表单

本轮只改两处：[`dsh-go/app/lib/bot/bot_profile.dart`](../../dsh-go/app/lib/bot/bot_profile.dart) 与新增的 [`dsh-go/app/test/bots/minimax_bot_profile_edit_test.dart`](../../dsh-go/app/test/bots/minimax_bot_profile_edit_test.dart)。**没有**新造页面，**没有**改 `BotHome`、公共 API / controller、runtime、broker、Endpoint、桥、依赖 / lockfile / 生成文件、CI 或共享交接文档。

## 1. 源码变化

### 1.1 保存按钮跟着名字走

原来的编辑对话框里，「保存」始终可点，空白名字只在点击处理器里被忽略，点击后毫无反应。

现在：

- 名字输入框加 `onChanged` → `update(() {})`，对话框每次输入都会重建；
- 保存按钮的 `onPressed` 由 `name.text.trim().isNotEmpty` 决定，不满足时为 `null`（真正禁用）；
- **角色允许为空**，不新增校验规则；初始名字与角色照旧预填；
- 请求形状不变：`updateBot(botId, {'name': name.text.trim(), 'role': role.text.trim()})`；
- 沿用现有文案（`mobileBotEdit` / `mobileBotSave` / `mobileBotCancel` / `mobileBotName` / `mobileBotRole`），**没有新增翻译**。

### 1.2 同时最多一个编辑流程

新增独立标记 `_editing`。**它没有借用 `_busy`** —— `_busy` 会被 `_reload()` 重置，而编辑流程必须跨过那次重置仍然保持关闭。

覆盖范围：**对话框打开 → 退出过渡 → `updateBot` 等待 → 随后的 `_reload`**：

- 入口（`TextButton('编辑')`）用 `_busy || _editing || _bot == null` 关闭；
- 方法入口自身防重入：`_edit()` 开头 `if (_editing) return;`；
- `finally` 里 `setState(() { _busy = false; _editing = false; })`，取消、成功、失败三条路径都恢复入口；
- **原有的 `if (accepted != true || !mounted) return;` 守卫原样保留**，`_composer` 式的 `mounted` 检查与两个 `TextEditingController` 的释放也原样保留。

### 1.3 未改变的部分

取消不请求、无自动重试、超时不当成成功、更新失败沿用既有的失败反馈（`mobileBotRetry` 按钮）、不清空原 Bot、不乐观伪造保存成功 —— 全部保持原样。

## 2. 实际命令与输出

环境：本机 Windows，Flutter 在 `D:\toolchains\flutter`，pub cache / TEMP / 字体在 D 盘（`D:\android-setup\flutter.sh`）。Flutter 命令在 `dsh-go/app` 执行，链接检查在仓库根执行。

### 2.1 定向 analyze

```text
$ cd dsh-go/app && flutter analyze --no-pub lib/bot/bot_profile.dart test/bots/minimax_bot_profile_edit_test.dart
Analyzing 2 items...
No issues found! (ran in 2.7s)
```

### 2.2 本卡新增的 widget 测试

```text
$ cd dsh-go/app && flutter test --no-pub test/bots/minimax_bot_profile_edit_test.dart
00:00 +0: loading D:/mbotapp/dsh-go/app/test/bots/minimax_bot_profile_edit_test.dart
00:00 +0: the edit form starts from the current name and needs one to save
00:01 +1: the edit entry is closed while the dialog is open and a cancel asks for nothing
00:01 +2: a waiting update runs once and the entry stays closed until it ends
00:01 +3: a failed update is not retried and the page offers its own retry
00:01 +4: a page that leaves while the dialog closes does not update
00:01 +5: the Bot profile edit form has no overflow at width 390.0
00:01 +6: the Bot profile edit form has no overflow at width 1024.0
00:02 +7: All tests passed!
```

用例覆盖的阶段：

| 阶段 | 断言 |
|---|---|
| 打开表单 | 名字预填为 `Alice`、角色预填为 `Research`；当前名字即可保存 |
| 输入中 | 空白禁用、有效启用、再清空禁用；角色清空后仍可保存 |
| 对话框打开 | 编辑入口关闭（`_editing` 生效，此时 `_busy` 还是 false） |
| 取消 | 不发请求、入口恢复 |
| `updateBot` 等待中 | 只调用一次、请求体是 trim 后的 `{name, role}`、入口保持关闭 |
| 成功 | 重读 `state`，显示服务端回答的 `研究助手·服务端`（不是回显输入）、入口恢复 |
| 失败 | 只尝试一次、沿用「重新连接」按钮、原 Bot 名字仍在、入口恢复 |
| 退出过渡中页面被移除 | 不发 `updateBot`、无框架异常 |
| 390 / 1024 宽度 | 页面与对话框都无 overflow |

### 2.3 未改动文件的回归

```text
$ cd dsh-go/app && flutter test --no-pub test/bots/minimax_bot_home_test.dart test/bots/bot_stream_performance_test.dart
00:02 +11: All tests passed!

$ cd dsh-go/app && flutter test --no-pub test/bots/bot_profile_test.dart
00:00 +0: profile task port scopes reads and refuses accidental cross-Bot mutations
00:01 +1: Bot profile opens its own automations and persists its notification preference
00:01 +2: All tests passed!
```

### 2.4 旧行为对照

把 `bot_profile.dart` 临时换回改动前的版本（对照完立即还原，已核对 `git diff` 恢复），同一份测试的结果：

```text
Failing tests:
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_profile_edit_test.dart: a waiting update runs once and the entry stays closed until it ends
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_profile_edit_test.dart: the edit entry is closed while the dialog is open and a cancel asks for nothing
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_profile_edit_test.dart: the edit form starts from the current name and needs one to save
```

三条新行为在旧源码上失败：

| 失败项 | 旧行为 |
|---|---|
| 保存按钮即时禁用 | 按钮始终可点，空白名字被点击处理器静默忽略 |
| 对话框打开时入口关闭 | 入口只看 `_busy`，对话框期间 `_busy` 为 false，入口仍可点 |
| 等待期间单流程 | 没有流程标记，入口与方法都不防重入 |

「取消不请求」「失败不重试」「页面离开时不更新」三条在旧源码上本来就成立（旧源码已有 `!mounted` 守卫），所以它们是回归护栏而不是新功能；**没有**为了制造对照而删掉那个既有守卫。

### 2.5 文档链接检查

```text
$ cd <repo root> && python tooling/docs-links/check_links.py --root . docs/research/flash-bot-profile-edit.md
checked 1 document(s), found 0 issue(s)
```

## 3. 未验证项

- **没有真机/模拟器执行**：本轮没有任何设备运行记录；widget 测试不能当成产品 APK 已验证。
- **没有真实 Bot 服务**：测试里的 `_Api` 是本地假实现，只覆盖「请求有没有发出去、发了几次、按钮状态对不对、服务端回答是否被重读」。
- **没有跑全量 golden 套件**（按任务卡要求）。只跑了本卡两个文件、既有的 `bot_profile_test.dart` 与上一卡的 `minimax_bot_home_test.dart` / `bot_stream_performance_test.dart`。
- **没有验证**通知开关、定时任务、服务端语义、API / controller 行为 —— 本轮没有改它们。
- 只跑了 390 与 1024 两个宽度；折叠屏、中屏与无障碍语义未验证。
- 「重新连接」这条失败反馈沿用既有实现，本轮只断言它仍然出现，**没有**验证它之后真的能重连。
