<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 本机小卡：没有默认模型时仍能读取目录

只复查上一卡新发现的 `/api/models` 500。此前 `016f409` 的供应商启动与恢复已经通过，不重复头像、附件、整套表单或58/23组件验收。只用 feat/t-plugins；零源码改动，不改pin/依赖/权限，不force、不PR、不发布APK，不清数据、不wipe。没有用户本人在App里配置Key就不测试连接、不发模型消息，不读取别处的Key。

1. 确认tracked工作树干净，fetch并merge --ff-only最新远端，记录精确HEAD。无法快进即报告，不reset/stash。沿用已授权full-ICU、SQLite、backup及上次依赖，不重编Node。
2. 新JS修复必须进包。按[上一张卡的构建命令](T-Flash-ProviderStartupRecovery.md)重新build partial、build mobile、prepare-product，再build产品x64 APK。暂存目录换成全新后缀；已有product目录按上一卡规则改名，不覆盖，不手改摘要/标志位、不跳过闸门。准备失败即给出有限错误并停止，不自行扩大调查。
3. 仅向 emulator-5554/API35/x86_64 install -r，应用仍为 io.github.ccch1mneyyy.dsh_app.mbot。记录APK大小、SHA256、bot_ready和独立UID。使用上一卡的专用无Key供应商/模型，不动其它用户数据。
4. 先有默认模型，再停用该供应商并保存，随后重新启用并保存；**此时不点“设为默认”**。等待ready后，native应仍无默认模型，guest真实 `GET /api/models` 必须200、含该已启用模型和对应推理等级、default缺失或null。新建Bot页应显示“尚未设置默认模型”提示，模型及推理等级可手选；打开检查后取消即可，不必再建Bot。不以native设置页回显代替guest目录证据。
5. 再手动设为默认，guest仍应200且default恢复，关闭设置后列表应恢复，无旧失败横幅。只回报精确HEAD/工作树状态/各步退出码/APK摘要/两种目录结果及少量截图路径。不要dump descriptor、prefs、env、完整JSON或凭据。报告与APK只留本机，不入库、不发布；本卡不证明真实模型对话或ARM64。

云端对照：旧完整产品bundle在无默认模型时真实HTTP500；修复后的完整产品bundle以host Node24.22.2、真实FD SQLite、官方factory/Bot及owner-fixture FDs验HTTP。宿主夹具不等于本卡Android设备验证。
