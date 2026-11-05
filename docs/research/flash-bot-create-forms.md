<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash：完善已有 Bot/群创建表单

本轮只改两处：[`dsh-go/app/lib/bot/bot_home.dart`](../../dsh-go/app/lib/bot/bot_home.dart) 与 [`dsh-go/app/test/bots/minimax_bot_home_test.dart`](../../dsh-go/app/test/bots/minimax_bot_home_test.dart)。**没有**新增页面，**没有**改 `BotController`、`MiniMaxBotApi`、runtime、launcher、broker、Endpoint、CI、依赖或共享交接文档。

## 1. 改了什么

### 1.1 创建按钮跟着字段走

原来的对话框里，「创建」按钮始终可点，点击后才在 `onPressed` 里检查名字和成员，不满足就静默无响应。

现在：

- 名字输入框加 `onChanged` → `update(() {})`，对话框每次输入都会重建；
- 按钮的 `onPressed` 由 `ready` 决定：`name.text.trim().isNotEmpty && (!group || members.isNotEmpty)`，不满足时为 `null`（真正禁用，不是点了没反应）；
- 群创建另外要求至少一个成员；成员勾选本来就调 `update`，因此同样即时生效；
- 沿用现有文案（`mobileBotName` / `mobileBotMembers` / `mobileBotCreate` / `mobileBotCancel`），**没有新增翻译**。

### 1.2 同一时刻只有一个创建流程

新增状态位 `_creating`，覆盖**对话框打开、关闭过渡、API 等待、随后的 refresh/select** 全程：

- 入口（`PopupMenuButton`）用 `enabled: !_creating` 关闭；
- 方法入口本身也防重入：`_create()` 开头 `if (_creating) return;` —— 不只依赖视觉禁用；
- 取消（`accepted != true`）**不发任何网络请求**，直接返回；
- `finally` 里恢复入口：`if (mounted) setState(() => _creating = false);`；取消、成功、异常三条路径都走到；
- 异步之后更新 UI 全部先查 `mounted`（`if (!mounted) return;` 后才 `_composer.clear()`），两个 `TextEditingController` 仍在 `finally` 里释放；
- **没有**加重试，**没有**把超时当成功，**没有**改请求体或服务端语义。

## 2. 实跑命令与输出

环境：本机 Windows，Flutter 在 `D:\toolchains\flutter`，pub cache / TEMP / 字体在 D 盘（`D:\android-setup\flutter.sh`）。测试在 `dsh-go/app` 执行，链接检查在仓库根执行。

### 2.1 定向 analyze

```text
$ cd dsh-go/app && flutter analyze --no-pub lib/bot/bot_home.dart test/bots/minimax_bot_home_test.dart
Analyzing 2 items...
No issues found! (ran in 20.6s)
```

### 2.2 本任务的 widget 测试

```text
$ cd dsh-go/app && flutter test --no-pub test/bots/minimax_bot_home_test.dart
00:00 +0: loading D:/mbotapp/dsh-go/app/test/bots/minimax_bot_home_test.dart
00:00 +0: late history from a previous selection does not overwrite the current chat
00:00 +1: creating a Bot needs a name before the button is usable
00:01 +2: creating a group needs a member before the button is usable
00:01 +3: cancelling the creation dialog asks the service for nothing
00:01 +4: a waiting creation cannot be started twice and the entry comes back
00:02 +5: a failed creation is not retried and the entry comes back
00:02 +6: Bot conversation layout at width 390.0
00:02 +7: Bot conversation layout at width 1024.0
00:02 +8: All tests passed!
```

新增 5 个用例，原有的 1 个 controller 用例与 2 个 390/1024 宽度布局用例**原样保留**。

### 2.3 依赖同一文件的另一个测试文件

`test/bots/bot_stream_performance_test.dart` 也 import 了 `bot_controller.dart`，一并跑：

```text
$ cd dsh-go/app && flutter test --no-pub test/bots/bot_stream_performance_test.dart test/bots/minimax_bot_home_test.dart
00:02 +10: All tests passed!
```

### 2.4 新用例确实能抓住旧行为

把 `bot_home.dart` 临时换回改动前的版本（改完立即还原），同一份测试的结果：

```text
Failing tests:
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_home_test.dart: a waiting creation cannot be started twice and the entry comes back
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_home_test.dart: creating a Bot needs a name before the button is usable
  D:/mbotapp/dsh-go/app/test/bots/minimax_bot_home_test.dart: creating a group needs a member before the button is usable
```

三个新行为在旧代码上失败；「取消不发请求」「异常不自动重试」两条在旧代码上本来就成立，所以它们是回归护栏而不是新功能。

### 2.5 文档链接检查

```text
$ cd <repo root> && python tooling/docs-links/check_links.py --root . docs/research/flash-bot-ui-inventory.md docs/research/flash-bot-create-forms.md
checked 2 document(s), found 0 issue(s)
```

## 3. 未验证项

- **没有真机/模拟器执行**：本轮没有任何设备运行记录；widget 测试不能当成产品 APK 已验证。
- **没有跑全量 golden 套件**（按任务卡要求）。全量套件在本机的失败数是已知的 Windows 平台差异（golden/几何），本轮没有触碰那些页面。
- **没有真实 Bot 服务**：测试里的 `_Api` 是本地假实现，只覆盖「请求有没有发出去、发了几次、按钮状态对不对」。请求体形状仍由既有代码决定，本轮没有改。
- **没有验证大屏/折叠屏以外的形态**：只跑了既有的 390 与 1024 两个宽度。
- **没有验证无障碍/键盘导航**下按钮禁用态的语义（本轮只断言了 `onPressed` 为 `null`）。
