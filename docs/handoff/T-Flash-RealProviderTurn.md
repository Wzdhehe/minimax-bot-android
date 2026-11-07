<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 本机小卡：真实 provider 的首次文本回合

只验这轮新增接线。目录500在11437bb产品设备上已通过，不重跑目录/头像/附件/58/23组件。只用feat/t-plugins，不改源码/pin/依赖/权限；不force、不PR、不发布APK、不清数据、不wipe。不把Key写进脚本、命令参数、报告、日志或仓库，不导出凭据存储。用户已授权在模拟器App内使用自己的Key，沿用其中的加密存储，不从聊天或其它程序复制读取。

1. tracked源码干净后fetch、merge --ff-only最新远端，记录精确HEAD。不能快进即回报，不reset/stash。
2. 必须重建JS：沿用[供应商恢复卡](T-Flash-ProviderStartupRecovery.md)的partial→mobile→prepare-product→flutter build链，新建暂存后缀、原product按既有规则改名，不覆盖或手改摘要。复用已授权Node full-ICU/SQLite/backup，不重编Node。产物闸门拒绝即报有限错误并停。
3. 只向emulator-5554/API35/x86_64 install -r同一产品ID，记录APK大小/SHA256及bot_ready、独立UID。保留现有Bot与加密凭据。
4. 沿用App里的Anthropic供应商：协议anthropic-messages，Base URL为 `https://api.minimax.cn/anthropic/v1`，模型MiniMax-M3.1-Flash为默认。无需重新输入Key，不改上下文、模态或五档参数。设置页保存/引擎重启后**不退出再进入设置页**，就点一次测试连接；应取新的native endpoint，不再固定503 provider_unavailable。失败仅报稳定code、是否ready和时间，勿抄上游原文；先停，不反复点。
5. 测试连接成功后，向Chief发一条简短文本，如“只回复测试通过”。只发一次；最多观察90秒。必须有真实回复文字与流式/完成状态，首条欢迎语、delivered或modelCalls计数都不能当成功。若失败/空白，记录消息status/errorCode/errorReason（短标识）与UI是否有明确提示，不dump完整消息/descriptor/env。不要扩大到图片、工具或高消耗任务。
6. 首回合成功且还有时间，再发一条要求较长回答的测试并在输出时点停止，记录是否停止、是否还能继续下一条；这项未做就写未验证，不为它重复构建或清数据。

报告与APK只留本机，回报精确HEAD/工作树状态/各步退出码/APK摘要/连接探针和文字回合结果/少量截图路径。ARM64、图像理解、五档推理是否被供应商实际接受、工具权限、插件、群聊、通知仍不属于本卡。

云端已用完整产品bundle、实际host FD SQLite、原版factory/Bot/Anthropic SDK和模拟native FD供应商，走过真实HTTP连接探针→消息→模型请求→回复落库。此为宿主组合证据，未使用真实Key，不能称Android或真实MiniMax对话通过。

## 2026-11-07 结项：本机产品通过

用户转述：精确 e9d0bebd5fc2487b87d3b5a77b32c2107a0fe54e，tracked干净，零本机源码改动/提交，重建JS→prepare→产品APK各步退出0。APK 290,642,875字节，SHA256 `0686e6b46d779c0af056605089f3dce8e5f73dccaf3c0d18f25b1798b078bf53`。API35/x86_64/emulator-5554，install -r保留原Bot/加密凭据，未清数据/force-stop；Main u0_a152、guest u0_i9036，一次ready，三回合未重启，无FATAL/SIGSEGV。

同一设置页一次连接测试成功；Chief真实回复TEST PASS（done，1次modelCalls，6979ms），长回合约5秒后停止（aborted，无文字输出，busyChats清空），随后SECOND OK（done，1次modelCalls，5947ms）。不是欢迎语或空done。**逐字增量未逐帧取证，也没有证明首token后停止。** effort、图像/附件入模、工具/自动批准、ARM64/真机/插件/群聊/通知/平板仍未验证。报告留本机 flash-real-provider-turn.md，云端未直接读取原始产物，未接触真实Key。本卡结项，不重派；后续只按NEXT-AGENT的新任务推进。
