<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Flash任务：完善已有Bot/群创建表单

状态：已交付5afbd76/0fc0315/16f6ba3及审查修复82343ad，均已快进纳入；Codex已复核恢复的mounted守卫和特定回归用例。Flash本机自报analyze及相关widget11项通过，云端无Flutter未复跑，设备未验证。下一张卡见[T-Flash-ProfileEdit](T-Flash-ProfileEdit.md)，本卡以下保留历史验收要求。

## 先修正接口报告（独立文档提交）

只修`docs/research/flash-bot-ui-inventory.md`中下述结论及重复的小结，不将建议任务当作已授权计划：

1. `MiniMaxBotApi.updateBot(id, Map)`可携带`isMain`，服务端`Team.updateBot`处理`patch.isMain`。没有专用setMain方法不等于缺少可表达的置主能力。
2. `MiniMaxBotApi.remember(scope, Map)`可携带`replaceId/expectedRevision`；服务端记忆路由接受这些字段并要求revision。缺少单独edit方法不等于客户端不能编辑。
3. `Team.updateGroup`的请求字段是`admin/members/replyMode/maxHops/handover/notes`等；`adminId/memberIds`是记录字段，不能写成PATCH请求字段。maxHops等也可以更新，并非仅创建时设置。
4. U3—U6所列不少行为已在`lib/bot/bot_home.dart`实现，U10也已有`BotController._applyEvent`。区分已有实现的缺口与需要新建的页面；不重复造页面。U1修改公共API返回类型、U2事件重连均需Codex另行设计，不自行开工。

必须用实际源码核对，不照抄本段形成新误报；记录路径与符号，不扩展本轮功能。

## 编码目标（第二个独立提交）

完善现有`BotHome._create`对话框的即时校验和防重复提交。保留原BotController/MiniMaxBotApi接口及现有对话框，不新增另一套创建页。

当前问题：创建按钮始终可点，空白名称/未选成员时点击仅无响应；创建API尚未结束时入口仍可再次打开。实现：

- Bot名称为空或仅空白时，创建按钮禁用；输入有效名称即时启用，清空后再次禁用。
- 群创建同时要求名称有效且至少一个成员。随输入/成员选择即时更新按钮状态。
- 同时最多一个创建流程（含对话框打开、关闭过渡、API等待、后续refresh/select）。入口在该期间不可再触发创建；方法入口也需防重入，不能只依赖视觉禁用。
- 用户取消不发网络请求；无论取消、成功或异常，流程结束后恢复入口。异步后更新UI须检查mounted，保持TextEditingController正确释放。
- 不增加自动重试，不把超时当成功，不改变请求体/服务端语义或凭据处理。沿用现有中英文文案，无需新增翻译。

## 允许写入

- `dsh-go/app/lib/bot/bot_home.dart`
- `dsh-go/app/test/bots/minimax_bot_home_test.dart`
- 上述接口报告（限纠错的独立提交）
- 新报告`docs/research/flash-bot-create-forms.md`（加AGPL-3.0头，记录改动、实跑命令/输出和未验证项）

其他文件只读。不改controller、客户端API、底层runtime/broker/Endpoint、主入口、CI、依赖、共享HANDOFF/NEXT-AGENT或其他任务卡；不新增页面，不批量改类型。若发现完成本卡必须改卡外文件，先记录原因交Codex，不自行扩权。

## 验收

在现有测试中补必要交互验证：Bot空白名称禁用/有效启用/清空禁用；群名有效但无成员禁用、选成员后启用；取消不调用API；Completer保持创建API等待时连续操作只产生一次创建，结束后入口恢复；异常不自动重试。不要用pumpAndSettle等待无限动画或故意未完成的Completer。

沿用现有390/1024宽度布局测试和本机字体环境，检查无overflow。按本机实际工具运行：

```text
flutter analyze --no-pub lib/bot/bot_home.dart test/bots/minimax_bot_home_test.dart
flutter test --no-pub test/bots/minimax_bot_home_test.dart
python tooling/docs-links/check_links.py --root . docs/research/flash-bot-ui-inventory.md docs/research/flash-bot-create-forms.md
```

Flutter命令在dsh-go/app执行，链接检查在仓库根执行；若需pub get，避免提交lockfile/生成物变动。不跑全量golden。没有模拟器/真机执行就写未验证，widget测试不能写成已验证产品APK。

仍用feat/t-plugins，开工/推送前fetch，只暂存允许路径。自己的未推送提交可rebase至最新远端；不force、不覆盖卡外冲突、不动main/dev/integration、不建PR、不发布APK。回报两个提交完整SHA、远端确认、实际输出；完成后等Codex审查，不自行开U1—U12。

## Codex源码审查：当前只修这一项

`BotHome._create`在`await route.completed`之后，原来的`if (accepted != true || !mounted) return;`被改成了仅检查accepted。若用户已确认，但BotHome在对话框退出过渡结束前被移除，后续仍会尝试访问页面的widget/controller并进入创建流程。需要恢复此处的mounted检查；后面清空输入和finally的mounted检查要保留。用户显式确认不代表已销毁的页面还应继续发起尚未开始的操作。

补一项定向widget回归：确认对话框后，在route.completed尚未结束时移除BotHome，验证不发create请求、无框架异常且controller安全释放。不要只测未确认时关闭页面；尽量证明测试能抓住本次被删的检查。沿用本卡允许的两个源码/测试文件及本机创建表单报告，不改共享交接或此卡。

运行原定向analyze、创建表单与流式性能测试，记录实际输出；单独一个修复提交推送后回报。云端仍在做BotLoopbackBridge，勿碰runtime/Endpoint或其测试/脚本。
