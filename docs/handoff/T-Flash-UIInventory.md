<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# M3.1 Flash当前任务：UI迁移接口清点与执行位修复

状态：待本机执行。用户已确定由Codex负责地基，Flash承担范围明确的外围工作。Endpoint代次、runtime runner、launcher、隔离和broker不在本任务内。

## 交付一：执行位修复

仅将`dsh-go/runtime/build-runtime.sh`恢复为Git mode 100755，脚本文本不变，单独提交。用`git diff --summary`和`git diff --numstat`核对仅mode变化，不改pin、不构建rootfs、不改launch.argv。

## 交付二：为后续UI编码准备接口对照

只新增`docs/research/flash-bot-ui-inventory.md`，文件加AGPL-3.0头。源代码只读，禁止直接替换API或重写状态层。

阅读`dsh-go/app/lib/state/bots_state.dart`、`dsh-go/app/lib/ui/bots/`、`dsh-go/app/lib/bot/`以及`dsh-go/packages/dsh_client/lib/`中实际的DsBotApi与MiniMaxBotApi定义。以源码为证据，列出：

- 用户当前能进入的Bot入口；旧DSH页面和新mbot页面分别依赖哪个controller/API。
- Bot列表/创建/编辑、群聊、消息发送与取消、事件流、定时任务、记忆、插件入口各自实际使用的方法和字段。
- 对应MiniMaxBotApi方法及事件字段；存在才写映射，不存在标缺口。区分「源码有页面」「已连真实服务」「设备验证」三种事实。
- 后续可单独派出的UI小任务，每项列准确文件、依赖的已存在接口及验收方式；不要自行创建协议、凭据字段或模拟已连接状态。

每项给出仓库相对路径和符号名，链接必须相对当前报告正确解析。不要复制Key/token/环境变量值。不要重跑数千项全量golden测试；本项只做文档链接检查，无需新增实现镜像测试。

## 写入与提交范围

仅上面的脚本执行位和一份新报告；执行位和报告各自独立提交。不改HANDOFF/NEXT-AGENT、CI、依赖、其他任务卡或源码。若已有未提交工作，保留并报告，不reset/stash覆盖。

仍在feat/t-plugins工作；开工与推送前fetch，快进最新远端，只暂存明确路径。自己的未推送提交可rebase到最新远端；卡外冲突停止并报告，不覆盖。不得force、不动main/dev/integration、不建PR、不发布APK、不删除/忽略golden资产。

验收报告命令：`python3 tooling/docs-links/check_links.py --root . docs/research/flash-bot-ui-inventory.md`。若本机python命令不同，记录实际命令。回报完整提交SHA、远端确认、两次提交各自的文件范围和检查输出。完成后等待Codex按对照表派具体UI实现，不自行扩大任务。
