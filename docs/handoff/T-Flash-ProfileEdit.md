<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash任务：Bot设置页编辑表单

执行地点：用户的本机Windows电脑。云端Codex负责Android socketpair连接桥，不等它的工具链或APK。沿用feat/t-plugins；先读根AGENTS.md、dsh-go/AGENTS.md及本卡，再fetch并快进到远端，不覆盖未推送工作。

## 目标与已核对源码

已有`BotProfilePage._edit`在`dsh-go/app/lib/bot/bot_profile.dart`，不要新造页面。它已有`await route.completed`之后的`accepted != true || !mounted`守卫，必须保留。目前保存按钮始终可点击，空白名称仅在点击处理器中被忽略；`_busy`到对话框退出后才置真，方法本身没有编辑流程防重入。

只完善这个编辑流程：

- 名称为空或仅空白时保存按钮真正禁用；输入有效名称立即启用，清空后禁用。保留初始名称/角色和原trim请求形状，角色允许空，不新增校验规则。
- 同时最多一个编辑流程，覆盖对话框打开、退出过渡、updateBot等待和随后的_reload；入口与方法均防重入。新的流程标记不要借用会被_reload重置的_busy标记；取消、成功、失败均恢复入口。
- 保留现有取消不请求、不自动重试、mounted与两个TextEditingController释放。不能将超时当成功；更新失败沿用既有失败反馈，不清空原Bot、不乐观伪造保存成功。
- 不新增翻译，沿用现有文案；不改通知、定时任务、服务端语义、API/controller。若发现必须改卡外文件，记录阻塞交Codex。

## 唯一允许写入

1. `dsh-go/app/lib/bot/bot_profile.dart`，仅编辑流程及直接相关编辑入口状态。
2. 新增`dsh-go/app/test/bots/minimax_bot_profile_edit_test.dart`，开头AGPL-3.0 SPDX头。
3. 新增`docs/research/flash-bot-profile-edit.md`，开头AGPL-3.0 SPDX头；记录源码变化、实际命令/输出、旧行为对照与未验证项。

其他文件只读。不要改BotHome、其创建测试、公共API/controller、runtime/broker/Endpoint/桥、依赖/lockfile/生成文件、CI、共享HANDOFF/NEXT-AGENT或任务卡。卡内引用的既有测试可运行但不修改。

## 必须验证

使用真实BotProfilePage和假MiniMaxBotApi/PrefsStore进行widget交互，依照已有`minimax_bot_profile_test.dart`（如存在，以rg查找实际文件）了解测试注入；不要生产代码加仅供测试的接口。

- 初始名称预填；空白禁用，有效启用，再清空禁用。
- 对话框打开时编辑入口禁用；取消不请求，关闭过渡后入口恢复。
- 用Completer保持updateBot等待时只调用一次，入口保持禁用；收到成功后重读state显示服务端结果，结束后恢复。
- 更新异常不自动重试，入口恢复，沿用现有失败反馈。
- 用户已经确认，但退出过渡结束前移除页面，不得再updateBot且无框架异常（沿用上一卡学到的时间窗口）。
- 390/1024宽度无overflow；不要用pumpAndSettle等待故意未完成的Completer或无限动画。

证明至少即时禁用与编辑入口单流程的新断言在旧源码失败；临时对照完立即还原并核对diff。无需重新删除已有mounted守卫制造另一个源码回归。

本机实际运行（Flutter命令在dsh-go/app，Python在根目录）：

```text
flutter analyze --no-pub lib/bot/bot_profile.dart test/bots/minimax_bot_profile_edit_test.dart
flutter test --no-pub test/bots/minimax_bot_profile_edit_test.dart
flutter test --no-pub test/bots/minimax_bot_home_test.dart test/bots/bot_stream_performance_test.dart
python tooling/docs-links/check_links.py --root . docs/research/flash-bot-profile-edit.md
```

可额外运行现有BotProfile相关测试，不跑全量golden。不装设备就写设备未验证；widget测试不代表APK已验证。不存在的文件/命令先查找实际位置，不伪造结果。

## 提交与交付

显式暂存上述3个路径；提交推送前fetch，如远端前进，仅自己的未推送提交可rebase，不force、不改main/dev/integration、不PR、不发布APK。共享文件冲突停止并说明，不覆盖云端工作。交付完整提交SHA、远端确认、实际输出和未验证项，之后等待Codex源码审查，不自动扩展下一任务。
